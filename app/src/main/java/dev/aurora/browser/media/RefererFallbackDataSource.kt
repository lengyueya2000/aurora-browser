package dev.aurora.browser.media

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.util.concurrent.ConcurrentHashMap

/**
 * 会带 Referer 的 HTTP 源；被 CDN 以 401/403 拒了就换成不带 Referer 的源重开一次。
 *
 * 为什么需要这一层——有一类站点把正片放在别人的 CDN 上，那个 CDN 的 Referer ACL 只放行
 * 空 Referer 和白名单站点，站点自己在页面里用 `<meta name="referrer" content="no-referrer">`
 * 让浏览器**不发** Referer，从而绕过热链保护。浏览器里播得好好的，原生播放器按惯例带上
 * Referer 反而被拦下来。
 *
 * 4kvm.net 就是这种：分片在 `file.icve.com.cn`（阿里云 Tengine），
 * `Referer: https://4kvm.net/play/…` → `403 denied by Referer ACL`，不带 Referer → 200。
 *
 * 反过来也有站点必须带 Referer 才给数据，所以不能一律不发：先按带 Referer 试，只有被
 * 明确拒绝（401/403）才退到不带 Referer 的源。其它错误照原样抛出，不做无谓重试。
 *
 * 拒绝记录按**主机**记，而且存在 [Factory] 上而不是本类实例上：HLS 一次播放要开几百次
 * 连接，每次连接都是新建一个 `DataSource`，不共享这份记录的话每个分片都要先撞一次 403。
 */
@UnstableApi
internal class RefererFallbackDataSource(
    private val base: HttpDataSource.Factory,
    /** 每次开连接时现取，取值可能变（下载任务共用一套数据源，每条流的页面地址不同）。 */
    private val referer: () -> String?,
    /** 已经拒过 Referer 的主机。由 [Factory] 持有，所有实例共用。 */
    private val rejected: MutableSet<String>,
) : DataSource {

    /**
     * 监听器由加载器在 `open()` 之前挂上，而那时还不知道这次会用哪个委托对象，
     * 所以先存着，建委托时一并补挂。
     */
    private val listeners = mutableListOf<TransferListener>()

    private var delegate: HttpDataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        listeners += transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        val host = dataSpec.uri.host.orEmpty()
        val value = referer()
        // 没得带、或者这个主机已经明确拒过，就直接不带。
        val withReferer = !value.isNullOrBlank() && (host.isEmpty() || host !in rejected)
        val first = newSource().apply { if (withReferer) setRequestProperty("Referer", value) }
        delegate = first
        try {
            return first.open(dataSpec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            // 这次本来就没带 Referer，再失败就跟 Referer 无关了。
            if (!withReferer || !isRejection(e.responseCode)) throw e
            if (host.isNotEmpty()) rejected += host
            runCatching { first.close() }
            val retry = newSource()
            delegate = retry
            return retry.open(dataSpec)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: 0

    override fun getUri(): Uri? = delegate?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        delegate?.responseHeaders.orEmpty()

    override fun close() {
        val current = delegate
        delegate = null
        current?.close()
    }

    private fun newSource(): HttpDataSource =
        base.createDataSource().also { source -> listeners.forEach(source::addTransferListener) }

    /** 只有 401/403 才算「Referer 被拒」；换了 Referer 其它状态码也不会变。 */
    private fun isRejection(responseCode: Int): Boolean =
        responseCode == 401 || responseCode == 403

    /** [DataSource.Factory]，顺带持有「哪些主机的 Referer 被拒过」。 */
    class Factory(
        private val base: HttpDataSource.Factory,
        private val referer: () -> String?,
    ) : DataSource.Factory {

        private val rejected: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun createDataSource(): DataSource =
            RefererFallbackDataSource(base, referer, rejected)
    }
}

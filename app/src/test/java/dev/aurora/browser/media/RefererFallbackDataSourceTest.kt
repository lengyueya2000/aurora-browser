package dev.aurora.browser.media

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「带了 Referer 被 CDN 拒掉」的自动回退。
 *
 * 真机回归：4kvm.net 的分片放在 `file.icve.com.cn`，那个 CDN 的 Referer ACL 放行空
 * Referer、拒绝第三方站点。站点自己在页面里用 `<meta name="referrer" content="no-referrer">`
 * 让浏览器不发 Referer，所以网页里播得好好的；我们的原生播放器按惯例带上 Referer，
 * 每个分片都被 `403 denied by Referer ACL` 顶回来，表现为「这个视频播不了」。
 *
 * 这里用假的 HTTP 源复现同一套规则（只看有没有带 Referer、只看是不是黑名单主机），
 * 把「什么时候该退、退了以后要不要一直退」这两件事钉死。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RefererFallbackDataSourceTest {

    private val referer = "https://4kvm.net/play/ch4k8uf4p"

    /** 一次开连接：记下打到哪个主机、这次带的 Referer（null = 没带）。 */
    private data class Attempt(val host: String, val referer: String?)

    /**
     * 假 HTTP 源。规则照着 `file.icve.com.cn` 来：黑名单主机上带了 Referer 就拒绝，
     * 不带就放行；别的主机带不带都放行。
     */
    private class FakeHttpSource(
        private val attempts: MutableList<Attempt>,
        private val rejectRefererOn: Set<String>,
        private val rejectCode: Int,
        private val rejectEvenWithoutReferer: Boolean,
    ) : HttpDataSource {

        private val properties = mutableMapOf<String, String>()
        private var uri: Uri? = null
        private var responseCode = 200

        var wasClosed = false
            private set

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            val host = dataSpec.uri.host.orEmpty()
            val value = properties["Referer"]
            attempts += Attempt(host, value)
            val rejected = host in rejectRefererOn && (value != null || rejectEvenWithoutReferer)
            if (rejected) {
                responseCode = rejectCode
                throw HttpDataSource.InvalidResponseCodeException(
                    /* responseCode = */ rejectCode,
                    /* responseMessage = */ "denied by Referer ACL",
                    /* cause = */ null,
                    /* headerFields = */ emptyMap(),
                    /* dataSpec = */ dataSpec,
                    /* responseBody = */ ByteArray(0),
                )
            }
            uri = dataSpec.uri
            return SEGMENT_BYTES
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1

        override fun getUri(): Uri? = uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            mapOf("X-Fake" to listOf("1"))

        override fun close() {
            wasClosed = true
        }

        override fun setRequestProperty(name: String, value: String) {
            properties[name] = value
        }

        override fun clearRequestProperty(name: String) {
            properties -= name
        }

        override fun clearAllRequestProperties() {
            properties.clear()
        }

        override fun getResponseCode(): Int = responseCode
    }

    private class FakeFactory(
        private val attempts: MutableList<Attempt>,
        private val rejectRefererOn: Set<String> = emptySet(),
        private val rejectCode: Int = 403,
        /** 连不带 Referer 也拒。用来验「没有 Referer 可退时不做第二次尝试」。 */
        private val rejectEvenWithoutReferer: Boolean = false,
    ) : HttpDataSource.Factory {

        val created = mutableListOf<FakeHttpSource>()

        override fun createDataSource(): HttpDataSource =
            FakeHttpSource(attempts, rejectRefererOn, rejectCode, rejectEvenWithoutReferer)
                .also { created += it }

        override fun setDefaultRequestProperties(
            defaultRequestProperties: Map<String, String>,
        ): HttpDataSource.Factory = this
    }

    private fun spec(url: String) = DataSpec.Builder().setUri(Uri.parse(url)).build()

    private val segment = "https://file.icve.com.cn/rc/535/191/85F2FC432D2E2B27319B0BA06A18AAFB.png"
    private val playlist = "https://oss.douyinbit.com/m3u8/a54f4c74d9a560d6e86f17c28ddd24cd.m3u8"

    @Test fun refererIsSentWhenTheCdnAcceptsIt() {
        val attempts = mutableListOf<Attempt>()
        val factory = RefererFallbackDataSource.Factory(FakeFactory(attempts)) { referer }

        val length = factory.createDataSource().open(spec(segment))

        assertThat(length).isEqualTo(SEGMENT_BYTES)
        assertThat(attempts).containsExactly(Attempt("file.icve.com.cn", referer))
    }

    /** 核心用例：403 之后在**同一次 open 内**换成不带 Referer 的源重开，用户不必自己重试。 */
    @Test fun rejectedRefererIsRetriedWithoutIt() {
        val attempts = mutableListOf<Attempt>()
        val base = FakeFactory(attempts, rejectRefererOn = setOf("file.icve.com.cn"))
        val factory = RefererFallbackDataSource.Factory(base) { referer }

        val length = factory.createDataSource().open(spec(segment))

        assertThat(length).isEqualTo(SEGMENT_BYTES)
        assertThat(attempts).containsExactly(
            Attempt("file.icve.com.cn", referer),
            Attempt("file.icve.com.cn", null),
        ).inOrder()
        // 被拒的那次连接要关掉，否则每个分片都漏一条连接。
        assertThat(base.created.first().wasClosed).isTrue()
    }

    /**
     * 拒绝要按主机记住，而且要在**多个 DataSource 实例之间**共享。
     *
     * HLS 一次播放要开几百次连接，每次连接都是新建一个 DataSource。不共享这份记录的话
     * 每个分片都要先撞一次 403，等于请求量翻倍、还平白多几百次失败。
     */
    @Test fun rejectionIsRememberedAcrossDataSourceInstances() {
        val attempts = mutableListOf<Attempt>()
        val base = FakeFactory(attempts, rejectRefererOn = setOf("file.icve.com.cn"))
        val factory = RefererFallbackDataSource.Factory(base) { referer }

        factory.createDataSource().open(spec(segment))
        factory.createDataSource().open(spec(segment))
        factory.createDataSource().open(spec(segment))

        assertThat(attempts).containsExactly(
            Attempt("file.icve.com.cn", referer),
            Attempt("file.icve.com.cn", null),
            Attempt("file.icve.com.cn", null),
            Attempt("file.icve.com.cn", null),
        ).inOrder()
    }

    /** 记忆是按主机的：别的主机没拒过 Referer，就还得继续带——有的 CDN 不带就不给数据。 */
    @Test fun otherHostsStillGetTheReferer() {
        val attempts = mutableListOf<Attempt>()
        val base = FakeFactory(attempts, rejectRefererOn = setOf("file.icve.com.cn"))
        val factory = RefererFallbackDataSource.Factory(base) { referer }

        factory.createDataSource().open(spec(segment))
        factory.createDataSource().open(spec(playlist))

        assertThat(attempts.last()).isEqualTo(Attempt("oss.douyinbit.com", referer))
    }

    /** 401 同样是「Referer 不对」的常见表现（有些 CDN 拿它表示热链）。 */
    @Test fun unauthorizedIsAlsoRetriedWithoutReferer() {
        val attempts = mutableListOf<Attempt>()
        val base = FakeFactory(attempts, setOf("file.icve.com.cn"), rejectCode = 401)

        val length = RefererFallbackDataSource.Factory(base) { referer }
            .createDataSource()
            .open(spec(segment))

        assertThat(length).isEqualTo(SEGMENT_BYTES)
        assertThat(attempts).hasSize(2)
    }

    /** 404 跟 Referer 无关，换了也一样 404——不做无谓重试，错误原样抛出。 */
    @Test fun unrelatedStatusCodesAreNotRetried() {
        val attempts = mutableListOf<Attempt>()
        val base = FakeFactory(attempts, setOf("file.icve.com.cn"), rejectCode = 404)

        val thrown = runCatching {
            RefererFallbackDataSource.Factory(base) { referer }
                .createDataSource()
                .open(spec(segment))
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(HttpDataSource.InvalidResponseCodeException::class.java)
        assertThat((thrown as HttpDataSource.InvalidResponseCodeException).responseCode).isEqualTo(404)
        assertThat(attempts).hasSize(1)
    }

    /** 没有页面地址可带时，请求里就不该出现 Referer；这时被 403 也没得退，只能如实报错。 */
    @Test fun withoutRefererThereIsNothingToFallBackFrom() {
        val attempts = mutableListOf<Attempt>()
        val base = FakeFactory(attempts, setOf("file.icve.com.cn"), rejectEvenWithoutReferer = true)

        val thrown = runCatching {
            RefererFallbackDataSource.Factory(base) { null }
                .createDataSource()
                .open(spec(segment))
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(HttpDataSource.InvalidResponseCodeException::class.java)
        assertThat(attempts).containsExactly(Attempt("file.icve.com.cn", null))
    }

    /** 读数据、取地址、取响应头都要落到真正打开的那个委托对象上。 */
    @Test fun readsAreDelegatedToTheOpenedSource() {
        val attempts = mutableListOf<Attempt>()
        val base = FakeFactory(attempts, setOf("file.icve.com.cn"))
        val source = RefererFallbackDataSource.Factory(base) { referer }.createDataSource()

        source.open(spec(segment))

        assertThat(source.uri).isEqualTo(Uri.parse(segment))
        assertThat(source.responseHeaders).containsKey("X-Fake")
        assertThat(source.read(ByteArray(8), 0, 8)).isEqualTo(-1)
        // 回退过一次，所以真正打开的是第二个委托对象。
        assertThat(base.created).hasSize(2)
    }

    /** 还没打开过就取地址、取响应头，要给得回来，不能抛。 */
    @Test fun unopenedSourceAnswersEmpty() {
        val source = RefererFallbackDataSource
            .Factory(FakeFactory(mutableListOf())) { referer }
            .createDataSource()

        assertThat(source.uri).isNull()
        assertThat(source.responseHeaders).isEmpty()
    }

    /** 监听器是在 open 之前挂上来的，必须补挂到后来才建出来的委托对象上。 */
    @Test fun transferListenerCanBeAddedBeforeOpen() {
        val source = RefererFallbackDataSource
            .Factory(FakeFactory(mutableListOf())) { referer }
            .createDataSource()
        val listener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            override fun onBytesTransferred(
                source: DataSource,
                dataSpec: DataSpec,
                isNetwork: Boolean,
                bytesTransferred: Int,
            ) = Unit

            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
        }

        source.addTransferListener(listener)
        source.open(spec(segment))
        source.close()
    }
}

/** 一个分片的字节数。真假值不影响断言，写真的只是方便对着真机日志看。 */
private const val SEGMENT_BYTES = 188_073L

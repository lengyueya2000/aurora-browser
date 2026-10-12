package dev.aurora.browser.media

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 用一次性的隐藏 WebView 嗅探某一集页面的真实流地址（追剧模式切集用）。
 *
 * ## 为什么是播放器自己开一个 WebView，而不是让浏览器那个标签去加载
 *
 * 播放器盖在浏览器上时，浏览器那个 Activity 已经 stopped，它的 WebView 窗口不可见、
 * 定时器被系统 throttle。而这些站点的流地址十有八九是**页面里的 JS 现算出来的**（4kvm
 * 用自己的 WASM 签时间戳，见 [RefererFallbackDataSource] 那段注释），JS 不跑就不会发那个
 * 请求，`shouldInterceptRequest` 什么也嗅不到——播放器会一直等一个永远不来的回调。
 *
 * 所以这里自己跑一遍真实页面：不去构造 URL，让站点把地址算出来，我们在网络层接住。
 * 这也是唯一能覆盖「地址由站点 JS 现算」的做法。
 *
 * ## 几条约束
 *
 * - **必须挂进视图树**：不进视图树的 WebView 在部分 ROM 上定时器会被压制。所以调用方把
 *   根容器传进来，这里以 1×1、`alpha = 0` 挂上去——`alpha` 不影响可见性标志，用户看不见，
 *   但 JS 照常跑。
 * - **同一时刻只有一个**，用完立刻 `destroy()`：一个 WebView 二三十 MB。
 * - **迟到回调要作废**：连续切集时，前一次的嗅探结果必须丢掉，否则会把用户带回上一集。
 *   做法与缩略图的 `captureTokens` 一致——每次嗅探递增一个代号。
 */
class EpisodeSniffer(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    private var web: WebView? = null
    private var token = 0
    private var delivered = false
    private var pageFinished = false
    private var onReady: ((String) -> Unit)? = null
    private var onFail: ((String) -> Unit)? = null

    /**
     * 加载某一集页面并等它把真实流地址发出来。
     *
     * @param host 挂载点（Activity 的根容器）；null 时不挂载，直接加载。
     * @param userAgent 当前「请求桌面版网站」的 UA；**必须与标签一致**，否则桌面版站点
     *   给的是另一套页面，选集与流地址都可能对不上。
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun sniff(
        pageUrl: String,
        host: ViewGroup?,
        userAgent: String?,
        onReady: (String) -> Unit,
        onFail: (String) -> Unit,
        timeoutMs: Long = TimeoutMs,
    ) {
        cancel()
        val id = ++token
        this.onReady = onReady
        this.onFail = onFail
        delivered = false
        pageFinished = false
        MediaSniffer.clear(TabId)

        val view = runCatching { create(userAgent, id, host) }.getOrNull()
        if (view == null) {
            fail(id, "没能创建嗅探窗口")
            return
        }
        web = view

        // 按时间表查结果：页面加载完就查，没加载完的拍次只在最后一拍兜底。
        ChecksMs.forEachIndexed { i, gap ->
            main.postDelayed({ check(id, force = i == ChecksMs.lastIndex) }, gap)
        }
        main.postDelayed({ timeout(id) }, timeoutMs)

        runCatching { view.loadUrl(pageUrl) }
            .onFailure { fail(id, "这一集的页面打不开") }
    }

    /** 放弃当前这一次嗅探（用户按返回、或要换一集）。 */
    fun cancel() {
        token++
        onReady = null
        onFail = null
        delivered = true
        release()
    }

    /** Activity 销毁时收尾。 */
    fun destroy() = cancel()

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(userAgent: String?, id: Int, host: ViewGroup?): WebView {
        val view = WebView(context)
        view.settings.javaScriptEnabled = true          // 站点靠 JS 现算地址，必须开
        view.settings.domStorageEnabled = true
        // 不要自动出声：这一遍只是去拿地址，站点若自动播一段广告会莫名其妙地响。
        view.settings.mediaPlaybackRequiresUserGesture = true
        if (!userAgent.isNullOrBlank()) view.settings.userAgentString = userAgent
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // 这个回调在后台线程；MediaSniffer 自己带锁，记完再回主线程查。
                MediaSniffer.record(TabId, request.url.toString())
                main.post { check(id) }
                return null
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageFinished = true
                check(id)
            }
        }
        if (host != null) {
            // alpha=0 而不是 GONE/INVISIBLE：可见性标志保持 VISIBLE，定时器不被压制，
            // 但用户一个像素也看不到。
            view.alpha = 0f
            runCatching { host.addView(view, ViewGroup.LayoutParams(1, 1)) }
        }
        return view
    }

    private fun check(id: Int, force: Boolean = false) {
        if (delivered || token != id) return
        // 页面还没加载完时先不交付：站点常在正式流之前先拉一个预览片，早拿会拿错。
        if (!force && !pageFinished) return
        val url = MediaSniffer.playable(TabId) ?: return
        succeed(id, url)
    }

    private fun succeed(id: Int, streamUrl: String) {
        if (delivered || token != id) return
        delivered = true
        val cb = onReady
        onReady = null
        onFail = null
        release()
        cb?.invoke(streamUrl)
    }

    private fun fail(id: Int, reason: String) {
        if (delivered || token != id) return
        delivered = true
        val cb = onFail
        onReady = null
        onFail = null
        release()
        cb?.invoke(reason)
    }

    private fun timeout(id: Int) = fail(id, "等这一集的地址等超时了")

    private fun release() {
        MediaSniffer.clear(TabId)
        val view = web ?: return
        web = null
        runCatching {
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.webViewClient = WebViewClient()
            view.destroy()
        }.onFailure { Log.w(TAG, "销毁嗅探窗口失败", it) }
    }

    companion object {
        private const val TAG = "AuroraEpisode"

        /**
         * 嗅探窗口在 [MediaSniffer] 里用的假标签 id。
         *
         * 真标签 id 由标签仓库生成，不会长这样；用完之后 [release] 会把它清掉。
         */
        internal const val TabId = "__episode__"

        /** 查结果的时间表（毫秒）。最后一拍兜底——有些页面 `onPageFinished` 迟迟不来。 */
        internal val ChecksMs = longArrayOf(0L, 800L, 2_000L, 4_000L, 7_000L)

        /** 总超时。慢站多给几秒，反正界面上有「正在获取第 N 集…」。 */
        const val TimeoutMs = 12_000L
    }
}

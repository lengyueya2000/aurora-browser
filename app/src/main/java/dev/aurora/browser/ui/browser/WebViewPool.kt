package dev.aurora.browser.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.aurora.browser.BuildConfig
import dev.aurora.browser.data.DownloadRequest
import dev.aurora.browser.media.MediaSniffer
import java.io.ByteArrayInputStream

/**
 * 每个标签页一个 `WebView`。
 *
 * 为什么不是一个 WebView 复用：所有标签共用一个实例时，`loadUrl` 会把新标签压进同一个
 * 前进/后退栈，切标签后按返回键会退到另一个标签的页面。另外无痕标签也需要独立实例，
 * 才能关掉 DOM 存储与缓存。
 *
 * 池的生命周期长于组合（切标签时旧实例留在池里），因此客户端**不能**捕获组合期的 lambda
 * ——那会一直调用首次组合的那份。改为把最新回调写进池上的字段，客户端每次只读这里。
 */
class WebViewPool(
    private val context: Context,
    private val factory: ((Context) -> WebView)? = null,
) {
    // 事件出口，由 BrowserScreen 每次组合刷新（见 SideEffect）。
    var onProgress: (tabId: String, progress: Int) -> Unit = { _, _ -> }
    var onPageStarted: (tabId: String) -> Unit = {}
    var onPageFinished: (tabId: String, title: String, url: String) -> Unit = { _, _, _ -> }
    var onTrackerBlocked: (tabId: String) -> Unit = {}
    var onSslError: (tabId: String) -> Unit = {}
    var onExternalUrl: (tabId: String, url: String) -> Unit = { _, _ -> }
    var onDownloadRequest: (tabId: String, request: DownloadRequest) -> Unit = { _, _ -> }
    var onLoadError: (tabId: String, description: String) -> Unit = { _, _ -> }
    // 视频全屏：字段名不叫 onShowCustomView/onHideCustomView，避免和 WebChromeClient
    // 的同名回调撞名——在匿名对象内部写 onShowCustomView(...) 会递归调用自己。
    var onShowFullscreenView: (view: View, callback: WebChromeClient.CustomViewCallback) -> Unit =
        { _, _ -> }
    var onHideFullscreenView: () -> Unit = {}
    // 页面里注入的「全屏 / 下载 / 投屏」按钮被点（见 MediaOverlay）。回调保证在主线程。
    // 第三个参数是附加信息，见 [MediaBridge]。
    var onMediaAction: (action: String, url: String, extra: String) -> Unit = { _, _, _ -> }
    var blockTrackers: () -> Boolean = { false }

    private val views = mutableMapOf<String, WebView>()

    /** 桥的方法跑在 WebView 的 JS 线程上，回调宿主前先切回主线程。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val mediaBridge = MediaBridge { action, url, extra ->
        main.post { onMediaAction(action, url, extra) }
    }

    /**
     * 这个标签页当前该拿去播放的地址（真实流，不是页面里的 `blob:`）。
     *
     * 页面给不出可用地址时（MSE 站点的 `blob:`）就靠它兜底，见 [MediaSniffer]。
     */
    fun sniffedPlayableUrl(tabId: String): String? = MediaSniffer.playable(tabId)

    /** 这个标签页嗅探到的直链媒体文件；没有直链（只有 HLS 分片）时为 null。 */
    fun sniffedDirectFile(tabId: String): String? = MediaSniffer.directFile(tabId)

    /** 取（或创建）该标签的 WebView。 */
    fun get(tabId: String, incognito: Boolean): WebView =
        views.getOrPut(tabId) { create(tabId, incognito) }

    /** 释放已不存在的标签的 WebView。 */
    fun retain(tabIds: Set<String>) {
        val stale = views.keys - tabIds
        stale.forEach { dispose(it) }
    }

    fun dispose(tabId: String) {
        MediaSniffer.clear(tabId)
        views.remove(tabId)?.let(::destroy)
    }

    fun disposeAll() {
        views.keys.forEach { MediaSniffer.clear(it) }
        views.values.forEach(::destroy)
        views.clear()
    }

    /** 仅供测试观察：当前池中活着的标签。 */
    fun retainedTabIds(): Set<String> = views.keys.toSet()

    /**
     * 在页面里执行 JS，并把返回值当 JSON 解析成 `[标题, 正文]`。
     *
     * `evaluateJavascript` 会把返回值 JSON 编码后再回调，因此脚本约定返回**数组**——
     * 返回字符串会被套上两层引号，得解析两次。回调在主线程；页面还没加载或脚本抛错时
     * 一律回空串，让调用方走「无法进入阅读模式」的分支。
     */
    fun evaluate(tabId: String, script: String, onResult: (title: String, text: String) -> Unit) {
        val web = views[tabId] ?: return onResult("", "")
        runCatching {
            web.evaluateJavascript(script) { raw ->
                val pair = runCatching {
                    (org.json.JSONTokener(raw).nextValue() as? org.json.JSONArray)
                        ?.takeIf { it.length() >= 2 }
                        ?.let { it.optString(0, "") to it.optString(1, "") }
                }.getOrNull()
                if (pair != null) onResult(pair.first, pair.second) else onResult("", "")
            }
        }.onFailure { onResult("", "") }
    }

    /**
     * 执行脚本，把 `evaluateJavascript` 的**原始**返回值（JSON 文本）回传。
     *
     * 与 [evaluate] 的区别：那个把返回值当 `[标题, 正文]` 解析，这个不做任何解析，
     * 留给调用方按自己的结构处理（视频源列表就是一个对象数组）。
     */
    fun evaluateRaw(tabId: String, script: String, onResult: (String?) -> Unit) {
        val web = views[tabId] ?: return onResult(null)
        runCatching { web.evaluateJavascript(script) { raw -> onResult(raw) } }
            .onFailure { onResult(null) }
    }

    /** 提取当前页面里可下载 / 可投屏的视频源。 */
    fun evaluateVideos(tabId: String, onResult: (List<VideoSource>) -> Unit) =
        evaluateRaw(tabId, VideoExtractor.SCRIPT) { onResult(VideoExtractor.parse(it)) }

    private fun destroy(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.destroy()
    }

    companion object {
        private const val TAG = "AuroraWebView"

        /** 网页尾部要预留的滚动空间，等于底部悬浮工具栏的完整高度（CSS px 与 dp 同值）。 */
        internal val PageBottomInsetCssPx: Int =
            dev.aurora.browser.ui.components.BottomToolbarHeight.value.toInt()

        /** 注入的留白元素 id。固定值便于幂等替换，也便于测试断言。 */
        internal const val BottomInsetElementId = "aurora-bottom-inset"

        /**
         * 生成「在文档尾部补一段滚动空间」的脚本。
         *
         * 为什么用注入而不是给 WebView 加 padding：`setPadding` 裁的是**视口**，内容会
         * 在工具栏上沿被硬生生切断，悬浮玻璃工具栏压在内容上的观感就没了。这里改的是
         * **文档**高度，滚动中内容照样从玻璃工具栏下面穿过，只有滚到底时最后一行会停在
         * 工具栏上沿——和原型 `.page{padding-bottom:110px}` 的做法一致。
         *
         * 三重保险：
         * - 元素 `pointer-events:none`，不会抢走页面的点击；
         * - 幂等，重复调用先删旧的再加新的（SPA 会反复触发 onPageFinished）；
         * - 页面本来就不滚动时不注入，免得短页面凭空多出一段可滚动空白。
         *   `load` 与 500ms 后再各跑一次，兜住图片/字体撑高文档的情况。
         */
        internal fun bottomInsetScript(cssPx: Int): String = """
            (function(){
              var H = $cssPx, ID = '$BottomInsetElementId';
              function run() {
                var old = document.getElementById(ID);
                if (old && old.parentNode) old.parentNode.removeChild(old);
                if (!document.body) return;
                if (document.documentElement.scrollHeight <= window.innerHeight + 1) return;
                var d = document.createElement('div');
                d.id = ID;
                d.setAttribute('aria-hidden', 'true');
                d.style.cssText = 'height:' + H + 'px;width:100%;pointer-events:none;';
                document.body.appendChild(d);
              }
              run();
              setTimeout(run, 500);
              window.addEventListener('load', run);
            })();
        """.trimIndent()

        /**
         * 是否该把这次加载失败当作整页失败。
         *
         * 抽成纯函数是因为 `WebResourceError` 是 final 类、构造函数包内可见，
         * 测试里造不出实例，没法直接驱动 `onReceivedError`。
         */
        internal fun isMainFrameError(request: WebResourceRequest): Boolean = request.isForMainFrame
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(tabId: String, incognito: Boolean): WebView =
        (factory ?: { ctx: Context -> WebView(ctx) })(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            applyHardenedSettings(this, incognito)
            // 页面上的「下载 / 投屏」按钮靠它回宿主。@JavascriptInterface 的方法只暴露
            // 这一个，且宿主会校验动作名与地址（见 MediaAction）。
            addJavascriptInterface(mediaBridge, MediaOverlay.BRIDGE_NAME)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    // 换页就把上一条视频嗅探到的地址清掉：留着的话，新页面上点「下载」
                    // 会下到上一个视频的流。
                    MediaSniffer.clear(tabId)
                    onPageStarted(tabId)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    onPageFinished(tabId, view.title.orEmpty(), url)
                    // 两段脚本合在一次 evaluateJavascript 里注入，省一次跨进程往返：
                    // - 悬浮工具栏盖在网页之上，页面末尾不留白的话，滚到底时最后几行会
                    //   永久停在工具栏底下、滚也滚不出来，所以给文档尾部补一段滚动空间；
                    // - 给页面里的每个 <video> 贴上「全屏 / 下载 / 投屏」浮层。
                    // 两段都是完整语句的 IIFE，拼起来等价于依次执行。
                    runCatching {
                        view.evaluateJavascript(
                            bottomInsetScript(PageBottomInsetCssPx) + "\n" + MediaOverlay.SCRIPT,
                        ) { raw ->
                            // 正常是 "ok"；脚本语法错或被站点拦下来时这里会是错误文本，
                            // debug 构建下留一条日志，省得再靠猜（见 README 的排查记录）。
                            if (BuildConfig.DEBUG && raw != "\"ok\"") {
                                Log.d(TAG, "media overlay injection -> $raw")
                            }
                        }
                    }
                }

                /**
                 * 只把 http/https 留在 WebView 内；其余 scheme（tel:、mailto:、intent:、
                 * market:、微信/支付宝的 scheme……）交给系统处理，否则页面里的电话/邮件
                 * 链接点了没反应。
                 */
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    val scheme = request.url.scheme?.lowercase().orEmpty()
                    if (scheme == "http" || scheme == "https") return false
                    onExternalUrl(tabId, request.url.toString())
                    return true
                }

                override fun onReceivedSslError(
                    view: WebView,
                    handler: SslErrorHandler,
                    error: android.net.http.SslError,
                ) {
                    // 默认拒绝，不给「继续访问」的直达路径（规格 5.3 / 7）。
                    handler.cancel()
                    onSslError(tabId)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: android.webkit.WebResourceError,
                ) {
                    // 只关心主文档失败；子资源失败（广告、图片）不该整页报错。
                    if (isMainFrameError(request)) {
                        onLoadError(tabId, error.description?.toString().orEmpty())
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    // 注意：此回调在后台线程，只做纯计算与线程安全的写入，不碰 Compose 状态。
                    // 每个请求都从这里过，视频站的真实流（HLS 播放列表、直链文件）就藏在这里——
                    // 页面里的 `<video>` 只有 MSE 的 `blob:`，拿不到地址。见 MediaSniffer。
                    MediaSniffer.record(tabId, request.url.toString())
                    if (!blockTrackers()) return null
                    if (!dev.aurora.browser.domain.TrackerBlocklist.isBlocked(request.url.toString())) {
                        return null
                    }
                    onTrackerBlocked(tabId)
                    return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)),
                    )
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    onProgress(tabId, newProgress)
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    onPageFinished(tabId, title.orEmpty(), view.url.orEmpty())
                }

                /**
                 * 网页视频全屏。WebView 把要全屏播放的 View 交出来，**由应用负责**把它
                 * 贴到窗口上并隐藏系统栏；不接管这个回调，`video.requestFullscreen()`
                 * 会静默失败——表现就是「点全屏没反应，视频还在网页小窗里」。
                 */
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    onShowFullscreenView(view, callback)
                }

                override fun onHideCustomView() {
                    onHideFullscreenView()
                }

                /**
                 * 权限请求：只放行 DRM 的 protected media id（全屏播放加密流要用），
                 * 相机 / 麦克风 / 定位 / 通知一律拒绝——与隐私页「站点权限一律拒绝」一致。
                 * 不覆盖这个回调时默认就是全部拒绝，显式写出来是为了给 DRM 开个口子。
                 */
                override fun onPermissionRequest(request: PermissionRequest) {
                    val granted = request.resources
                        .filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                    if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
                }
            }

            setDownloadListener(
                DownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
                    onDownloadRequest(
                        tabId,
                        DownloadRequest(url, userAgent, contentDisposition, mimeType, contentLength),
                    )
                },
            )
        }
}

/**
 * 与安全/隐私相关的 WebView 配置。
 *
 * - 明文 HTTP 必须放行：这是个浏览器，打不开 `http://` 站点等于不能用。地址栏的
 *   「不安全」标识负责提醒用户（`usesCleartextTraffic` 见 AndroidManifest）。
 * - 关闭文件访问与 content:// 读取：网页不该能读本机文件。
 * - 混排内容用 COMPATIBILITY 模式：严格模式会拦掉被动资源，大量站点直接白屏；
 *   `NEVER_ALLOW` 更适合内嵌 WebView 而非浏览器。
 * - 无痕标签额外关掉 DOM 存储并禁用缓存。
 */
@SuppressLint("SetJavaScriptEnabled")
private fun applyHardenedSettings(webView: WebView, incognito: Boolean) {
    val settings = webView.settings
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = !incognito
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.setGeolocationEnabled(false)
    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    settings.setSupportMultipleWindows(false)
    settings.javaScriptCanOpenWindowsAutomatically = false
    if (incognito) settings.cacheMode = WebSettings.LOAD_NO_CACHE

    // 第三方 Cookie 一律不发给跨站请求，减少跨站追踪面。
    runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false) }
}

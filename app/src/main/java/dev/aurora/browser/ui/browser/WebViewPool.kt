package dev.aurora.browser.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream

/** WebView 触发的下载请求，交给 UI 层入队（DownloadManager 需要 Context）。 */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
)

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
    var blockTrackers: () -> Boolean = { false }

    private val views = mutableMapOf<String, WebView>()

    /** 取（或创建）该标签的 WebView。 */
    fun get(tabId: String, incognito: Boolean): WebView =
        views.getOrPut(tabId) { create(tabId, incognito) }

    /** 释放已不存在的标签的 WebView。 */
    fun retain(tabIds: Set<String>) {
        val stale = views.keys - tabIds
        stale.forEach { dispose(it) }
    }

    fun dispose(tabId: String) { views.remove(tabId)?.let(::destroy) }

    fun disposeAll() {
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

    private fun destroy(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.destroy()
    }

    companion object {
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

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    onPageStarted(tabId)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    onPageFinished(tabId, view.title.orEmpty(), url)
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
                    // 注意：此回调在后台线程，只做纯计算与线程安全的计数，不碰 Compose 状态。
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

package dev.aurora.browser.ui.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.aurora.browser.BuildConfig
import dev.aurora.browser.data.DownloadRequest
import dev.aurora.browser.media.EpisodeList
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
    /**
     * 当前是否「请求桌面版网站」。
     *
     * 为什么是取值的 lambda 而不是一个 `Boolean` 参数：池的生命周期长于组合，而设置是
     * 组合期才读得到的（见 [blockTrackers] 的同一处理）。传值的话，池里已有的 WebView
     * 会永远停在创建那一刻的设置上。
     */
    private val desktopMode: () -> Boolean = { false },
    /** 取窗口像素的方式。真机走 [PixelCopy]；单测注入假的，好在没有真实窗口的环境里也能走通这条路。 */
    private val windowCapture: WindowCapture = WindowCapture { window, source, dest, onResult ->
        PixelCopy.request(
            window,
            source,
            dest,
            { result -> onResult(result == PixelCopy.SUCCESS) },
            android.os.Handler(android.os.Looper.getMainLooper()),
        )
    },
    /**
     * 取某个 WebView 在窗口里的可见范围（窗口坐标）；看不见时回 `null`。
     *
     * 判据是 [WebView.isAttachedToWindow]——同一时刻只有一个标签的 WebView 挂在窗口上，
     * 没挂上去的画出来是空白，见 [captureThumbnail]。
     *
     * 为什么是可注入的：Robolectric 里的 `WebView` 量不出尺寸，`layout()` 对它是空操作
     * （同一次调用加在普通 `View` 上就能拿到 720×1280），于是这条抠图路径整段都测不到。
     * 单测注入一个固定矩形，真机走默认实现。
     */
    private val visibleBounds: (WebView) -> Rect? = WebViewPool.Companion::attachedBounds,
) {
    // 事件出口，由 BrowserScreen 每次组合刷新（见 SideEffect）。
    var onProgress: (tabId: String, progress: Int) -> Unit = { _, _ -> }
    var onPageStarted: (tabId: String) -> Unit = {}
    var onPageFinished: (tabId: String, title: String, url: String) -> Unit = { _, _, _ -> }
    /**
     * 标题变化（`onReceivedTitle`）。
     *
     * 和 [onPageFinished] 分开：标题在加载过程中会反复变（站点先给占位标题再换成真标题，
     * 单页应用切路由也会变），把它当「页面加载完成」会顺带多写一次历史、多连拍一轮缩略图。
     */
    var onTitle: (tabId: String, title: String) -> Unit = { _, _ -> }
    /**
     * 渲染进程崩了（`onRenderProcessGone`）：这个标签的 WebView 已经作废。
     *
     * 回调已经切回主线程（见实现处），界面层可以直接改状态。
     */
    var onRenderGone: (tabId: String) -> Unit = {}
    // 下面两个会被 `shouldInterceptRequest`（WebView 的后台线程）读，而写入发生在主线程，
    // 所以要 @Volatile：不加的话后台线程可能长期读到组合期那份旧 lambda——开关关掉了还在拦。
    @Volatile var onTrackerBlocked: (tabId: String) -> Unit = {}
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
    // 第一个参数是**发起调用的那个标签**——不是「当前激活的标签」：后台标签页里的脚本也能
    // 调这个桥，用激活标签去解释就会张冠李戴。见 [MediaBridge]。
    // 最后一个参数是附加信息。
    var onMediaAction: (tabId: String, action: String, url: String, extra: String) -> Unit =
        { _, _, _, _ -> }
    @Volatile var blockTrackers: () -> Boolean = { false }

    private val views = mutableMapOf<String, WebView>()

    /**
     * 每个标签当前那一轮连拍的代号；标签被释放时抹掉，让还在飞的拍图自己作废。
     *
     * 见 [captureThumbnailAsync]：延时链和 `PixelCopy` 的回调都可能晚于 `dispose`。
     */
    private val captureTokens = mutableMapOf<String, Int>()
    private var nextCaptureToken = 0

    /**
     * 每个标签当前那一轮**选集提取**的代号；标签被释放时抹掉，让还在飞的提取自己作废。
     *
     * 与 [captureTokens] 同一个理由：`evaluateJavascript` 的回调可能晚于 `dispose`，
     * 也可能晚于用户手快发起的下一轮提取——晚到的那一轮不该再往界面上写结果。
     */
    private val episodeTokens = mutableMapOf<String, Int>()
    private var nextEpisodeToken = 0

    /**
     * 标签缩略图（标签网格用），按 tabId 存。
     *
     * 只放内存、不进磁盘：缩略图是给「现在这些标签」看的，重启后标签本来就没了，
     * 存下来只是白占空间，还会把浏览过的页面留在磁盘上。
     */
    private val thumbnails = mutableMapOf<String, Bitmap>()

    /**
     * 这台设备 WebView 自带的 UA，第一次建 WebView 时顺手记下来。
     *
     * 关掉桌面版时要换回它。不写死一个手机 UA：WebView 版本一变那个字符串就过期了。
     * 在**创建**时抓（而不是赋值之后）——新建的 WebView 此时还没被我们改过 UA，
     * 抓到的一定是系统默认值，与桌面版当时是否开着无关。
     */
    private var mobileUserAgent: String? = null

    /** 桥的方法跑在 WebView 的 JS 线程上，回调宿主前先切回主线程。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

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
        // 让还在飞的连拍作废：见 captureTokens。
        captureTokens.remove(tabId)
        // 选集提取同理：关掉的标签不该再往界面上回结果。
        episodeTokens.remove(tabId)
        // 不 recycle：位图可能还在标签网格里画着，手动回收会让它在下一帧崩掉。
        // 位图在现代 Android 上就在 Java 堆里，去掉引用就够了。
        thumbnails.remove(tabId)
        views.remove(tabId)?.let(::destroy)
    }

    fun disposeAll() {
        views.keys.forEach { MediaSniffer.clear(it) }
        captureTokens.clear()
        episodeTokens.clear()
        views.values.forEach(::destroy)
        views.clear()
        thumbnails.clear()
    }

    /** 仅供测试观察：当前池中活着的标签。 */
    fun retainedTabIds(): Set<String> = views.keys.toSet()

    /**
     * 给某个标签拍一张缩略图，给标签网格用。
     *
     * 只能拍**正在显示**的那个标签：WebView 的画面由渲染进程合成，没挂在窗口上的实例
     * 画出来是空白。所以判据是 [WebView.isAttachedToWindow]——同一时刻只有一个标签的
     * WebView 挂在窗口上，其余标签的图只能靠它上次显示时留下的那张。
     *
     * 失败或拍到一张纯色（没画上东西）时回 `null`，让网格退回占位图——比给用户看一块
     * 白板诚实。空白标签、还没布局过的 WebView 也都回 `null`。
     *
     * 这是**同步兜底**路径：走 `View.draw()`。硬件加速下 WebView 的内容在渲染进程里，
     * `draw()` 往往画不出东西，真机请用 [captureThumbnailAsync]。
     */
    fun captureThumbnail(tabId: String): Bitmap? {
        val web = views[tabId] ?: return null
        if (web.url == null) return null
        val bounds = visibleBounds(web) ?: return null
        val bitmap = runCatching {
            Bitmap.createBitmap(ThumbnailWidth, ThumbnailHeight, Bitmap.Config.RGB_565)
        }.getOrNull() ?: return null
        val drawn = runCatching {
            val canvas = Canvas(bitmap)
            // 画布按缩略图尺寸缩放过，等于把整个视口缩着画进去。
            canvas.scale(
                ThumbnailWidth.toFloat() / bounds.width(),
                ThumbnailHeight.toFloat() / bounds.height(),
            )
            web.draw(canvas)
        }.isSuccess
        if (!drawn || isBlankThumbnail(bitmap)) {
            bitmap.recycle()
            return null
        }
        thumbnails.put(tabId, bitmap)
        return bitmap
    }

    /**
     * 拍缩略图（真机走这条路）。
     *
     * 为什么不是 [captureThumbnail] 的 `View.draw()`：WebView 的页面由渲染进程合成，画面
     * 不在 View 的绘制列表里，硬件加速下 `draw()` 拿到的是一张空图——这正是缩略图在真机上
     * 一直是空白的原因。`PixelCopy` 抠的是窗口的合成结果，硬件加速也拿得到。
     *
     * [bottomInsetPx] 用来避开叠在网页上的悬浮工具栏：抠图抠的是窗口像素，工具栏会一起被
     * 抠进去。缩略图本来就把整页压扁到 5:3，少一条底边不影响可辨识度。
     *
     * **拍一次不够，按 [CaptureGapsMs] 拍几次**：「页面加载完成」之后多久画上屏没有保证。
     * 实测在 1080×1920 的模拟器上，第一次 `onPageFinished` 后 400ms 抠到的还是一整块底色
     * （页面还没画出来）；而只按「拍到第一张」就收工的话，又会拿到一张画了一半的图（上半个
     * 屏幕是页面、下半个还是底色）。所以每一拍都回调一次，后一次覆盖前一次——时间越晚画面
     * 越完整。一直没拍到才回调 `null`。
     *
     * [window] 为 null（或几次都拍不到）时退回 `View.draw()`，再不行回 `null`。
     */
    fun captureThumbnailAsync(
        tabId: String,
        window: Window?,
        bottomInsetPx: Int = 0,
        onCaptured: (Bitmap?) -> Unit = {},
    ) {
        // 记一个代号：`dispose` 会把它抹掉，于是这一轮（含已经在路上的 `PixelCopy` 回调）
        // 自己作废，不会再往一个已经关掉的标签上写缩略图。
        val token = ++nextCaptureToken
        captureTokens[tabId] = token
        attemptCapture(tabId, window, bottomInsetPx, attempt = 0, token = token, onCaptured = onCaptured)
    }

    private fun attemptCapture(
        tabId: String,
        window: Window?,
        bottomInsetPx: Int,
        attempt: Int,
        token: Int,
        onCaptured: (Bitmap?) -> Unit,
    ) {
        main.postDelayed({
            if (captureTokens[tabId] != token) return@postDelayed
            captureNow(tabId, window, bottomInsetPx, token) { bitmap ->
                if (captureTokens[tabId] != token) return@captureNow
                if (bitmap != null) {
                    onCaptured(bitmap)
                } else if (attempt == CaptureGapsMs.lastIndex) {
                    // 最后一次还是没拍到就认了，让网格画占位图，别一直重试下去。
                    onCaptured(null)
                }
                if (attempt < CaptureGapsMs.lastIndex) {
                    attemptCapture(tabId, window, bottomInsetPx, attempt + 1, token, onCaptured)
                }
            }
        }, CaptureGapsMs[attempt])
    }

    private fun captureNow(
        tabId: String,
        window: Window?,
        bottomInsetPx: Int,
        token: Int,
        onCaptured: (Bitmap?) -> Unit,
    ) {
        val web = views[tabId]
        if (window == null || web == null || web.url == null) {
            return onCaptured(captureThumbnail(tabId))
        }
        val bounds = visibleBounds(web) ?: return onCaptured(captureThumbnail(tabId))

        val source = Rect(
            bounds.left,
            bounds.top,
            bounds.right,
            (bounds.bottom - bottomInsetPx).coerceAtLeast(bounds.top + 1),
        )
        val raw = runCatching {
            Bitmap.createBitmap(source.width(), source.height(), Bitmap.Config.RGB_565)
        }.getOrNull() ?: return onCaptured(captureThumbnail(tabId))

        val failure = runCatching {
            windowCapture.capture(window, source, raw) { ok ->
                // 拍图期间标签被关掉了：位图丢掉，别往一个已经没了的标签上塞。
                if (captureTokens[tabId] != token) {
                    raw.recycle()
                    return@capture
                }
                // 纯色同样当没拍到：窗口还没画出页面时抠到的是一整块底色。
                val scaled = if (ok && !isBlankThumbnail(raw)) scaleToThumbnail(raw) else null
                raw.recycle()
                if (scaled == null) return@capture onCaptured(captureThumbnail(tabId))
                thumbnails.put(tabId, scaled)
                onCaptured(scaled)
            }
        }.exceptionOrNull()
        // 抠图请求本身抛异常（窗口没有 surface、取景框越界）时不能让主线程崩：退回 draw()。
        if (failure != null) {
            Log.w(TAG, "抠窗口画面失败，退回 draw()", failure)
            raw.recycle()
            onCaptured(captureThumbnail(tabId))
        }
    }

    /** 把整屏大小的抠图压成缩略图尺寸。尺寸恰好相同时 `createScaledBitmap` 会原样返回，得复制。 */
    private fun scaleToThumbnail(source: Bitmap): Bitmap? {
        if (source.width == ThumbnailWidth && source.height == ThumbnailHeight) {
            return runCatching { source.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
        }
        return runCatching {
            Bitmap.createScaledBitmap(source, ThumbnailWidth, ThumbnailHeight, true)
        }.getOrNull()
    }

    /** 上次拍到的缩略图；没拍过（或拍出来是空白）时为 null。 */
    fun thumbnail(tabId: String): Bitmap? = thumbnails[tabId]

    /**
     * 切换「请求桌面版网站」。
     *
     * 光改 UA 不够，还得**重新请求**：站点是拿 UA 决定发哪套页面的，已经在看的这一页
     * 还是按老 UA 发下来的那一套，不重载就只是「下次打开才是桌面版」——用户点了开关
     * 却看不出任何变化。
     *
     * 空白标签不重载（`url == null`）：没什么可刷新的，白白发一次请求。无痕标签也一样
     * 处理——UA 本来就不是用来隔离身份的。
     */
    fun applyDesktopMode(enabled: Boolean) {
        views.values.forEach { view ->
            if (applyUserAgent(view, enabled) && view.url != null) {
                runCatching { view.reload() }
            }
        }
    }

    /**
     * 把桌面版设置写进某个 WebView 的 UA。
     *
     * 返回 UA 是否真的变了——没变就不必重载，见 [applyDesktopMode]。
     */
    private fun applyUserAgent(webView: WebView, enabled: Boolean): Boolean {
        if (mobileUserAgent == null) mobileUserAgent = webView.settings.userAgentString
        val target = DesktopMode.userAgentFor(enabled, mobileUserAgent) ?: return false
        if (webView.settings.userAgentString == target) return false
        webView.settings.userAgentString = target
        return true
    }

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

    /**
     * 提取当前页面的选集列表（追剧模式，见 [EpisodeSession]）。
     *
     * **按需求值，带重试**：SPA 站点的选集常是异步渲染的，`onPageFinished` 那一刻可能还没有；
     * 先立即试一次，拿不到就按 [gaps] 再试。拿到非空结果就停，最后一拍还没有才回 `null`——
     * 调用方据此当成单集视频，一切照旧。
     *
     * @param gaps 每一拍的时间点。默认 [EpisodeGapsMs]（覆盖异步渲染）；开播前那次传
     *   `longArrayOf(0L)`——那一步不该为迟到的列表把开播拖住（见 BrowserScreen 的 PLAY 分支）。
     */
    fun extractEpisodes(
        tabId: String,
        pageUrl: String,
        pageTitle: String,
        gaps: LongArray = EpisodeGapsMs,
        onResult: (EpisodeList?) -> Unit,
    ) {
        val token = ++nextEpisodeToken
        episodeTokens[tabId] = token
        attemptExtract(tabId, pageUrl, pageTitle, 0, token, gaps, onResult)
    }

    private fun attemptExtract(
        tabId: String,
        pageUrl: String,
        pageTitle: String,
        attempt: Int,
        token: Int,
        gaps: LongArray,
        onResult: (EpisodeList?) -> Unit,
    ) {
        main.postDelayed({
            if (episodeTokens[tabId] != token) return@postDelayed
            evaluateRaw(tabId, EpisodeExtractor.SCRIPT) { raw ->
                if (episodeTokens[tabId] != token) return@evaluateRaw
                val list = EpisodeExtractor.parse(raw, pageUrl, pageTitle)
                if (list != null || attempt == gaps.lastIndex) {
                    onResult(list)
                } else {
                    attemptExtract(tabId, pageUrl, pageTitle, attempt + 1, token, gaps, onResult)
                }
            }
        }, gaps[attempt])
    }

    /**
     * 让某个标签导航到某个地址。
     *
     * 追剧模式切集后用它把标签挪到正在看的那一集，用户退出全屏时人在对的页面
     * （见 [EpisodeSession]）。它**不参与播放链路**：标签不在、加载失败都不影响播放。
     */
    fun navigate(tabId: String, url: String) {
        val web = views[tabId] ?: return
        runCatching { web.loadUrl(url) }
    }

    private fun destroy(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        // 先把 JS 接口摘掉再 destroy：`destroy()` 之后 WebView 内部还有一堆异步收尾，
        // 页面里没断的定时器仍可能在这中间再打一次桥。接口还在的话，那次调用会走到已经
        // 被释放的标签上——宿主按 tabId 找不到标签（拿到的 tab 是 null）还好，怕的是
        // 标签 id 已经被新标签复用，动作就落到了别人身上。
        runCatching { webView.removeJavascriptInterface(MediaOverlay.BRIDGE_NAME) }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = null
        webView.destroy()
    }

    companion object {
        private const val TAG = "AuroraWebView"

        /**
         * 缩略图的像素尺寸。5:3 贴近网格卡片的预览框比例（2 列网格里约 158×96dp），
         * 显示时再按 `ContentScale.Crop` 裁一点。取 320 宽是为了在 3x 屏上也够清楚，
         * 同时 RGB_565 一张只占 120KB——标签多了也不至于吃内存。
         */
        internal const val ThumbnailWidth = 320
        internal const val ThumbnailHeight = 192

        /**
         * 拍缩略图的时间表（毫秒）：页面加载完成后等第一格这么久再拍，拍不到就按下一格再试。
         *
         * 为什么不能只拍一次：`onPageFinished` 与「画面真的画上屏了」之间没有保证。实测在
         * 1080×1920 的模拟器上，第一次 `onPageFinished` 后 400ms 抠到的仍是一整块底色，
         * 得等到页面第二次加载完成才拍得到。与其猜一个够长的延时，不如拍不到就再试。
         */
        internal val CaptureGapsMs = longArrayOf(400L, 1500L, 3000L)

        /**
         * 选集提取的时间表（毫秒）。第一拍是立即——多数页面在 `onPageFinished` 时选集已经
         * 在 DOM 里了；后两拍留给异步渲染选集的 SPA。
         *
         * 与 [CaptureGapsMs] 不同的是它**只跑一次、拿到就停**：提取是个纯读操作，不像拍图
         * 那样「越晚越完整」，所以没必要为了一张更全的列表一直重试。
         */
        internal val EpisodeGapsMs = longArrayOf(0L, 800L, 2500L)

        /**
         * 默认的取景范围：WebView 挂在窗口上时是它自己的位置和尺寸，否则看不见。
         *
         * 抽出来是为了能单独测「没挂窗口就不拍」这一条——那是 [captureThumbnailAsync] 唯一
         * 不能出错的前提（`PixelCopy` 抠的是窗口，不管那个位置上是哪个标签）。
         */
        internal fun attachedBounds(web: WebView): Rect? {
            if (!web.isAttachedToWindow || web.width <= 0 || web.height <= 0) return null
            val location = IntArray(2)
            web.getLocationInWindow(location)
            return Rect(location[0], location[1], location[0] + web.width, location[1] + web.height)
        }

        /** 判断空白缩略图时每条边抽多少个点。 */
        private const val BlankSampleGrid = 12

        /**
         * 抽样判断是不是一张纯色图。
         *
         * `WebView.draw()` 在个别设备上会画出空白，而空白缩略图比没有缩略图更糟——用户看到的
         * 是一块白板。真实网页的缩略图不可能每个采样点都同色，所以抽样全同色就当没拍到。
         * 代价是纯色页面（极少）也会退回占位图。
         */
        internal fun isBlankThumbnail(bitmap: Bitmap): Boolean {
            val stepX = (bitmap.width / BlankSampleGrid).coerceAtLeast(1)
            val stepY = (bitmap.height / BlankSampleGrid).coerceAtLeast(1)
            val first = bitmap.getPixel(0, 0)
            var y = 0
            while (y < bitmap.height) {
                var x = 0
                while (x < bitmap.width) {
                    if (bitmap.getPixel(x, y) != first) return false
                    x += stepX
                }
                y += stepY
            }
            return true
        }

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
            applyUserAgent(this, desktopMode())
            // 页面上的「下载 / 投屏」按钮靠它回宿主。@JavascriptInterface 的方法只暴露
            // 这一个，且宿主会校验动作名与地址（见 MediaAction）。**每个 WebView 一个桥**，
            // 桥里绑着这个标签的 id，宿主才能分辨调用来自哪个标签。
            addJavascriptInterface(
                MediaBridge(tabId) { from, action, url, extra ->
                    main.post { onMediaAction(from, action, url, extra) }
                },
                MediaOverlay.BRIDGE_NAME,
            )

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    // 换页就把上一条视频嗅探到的地址清掉：留着的话，新页面上点「下载」
                    // 会下到上一个视频的流。
                    MediaSniffer.clear(tabId)
                    // 去广告脚本要抢在页面自己的脚本之前跑。站点的前贴片广告配置写在播放器
                    // 容器的 `data-*` 上，页面脚本在 DOMContentLoaded 时读走它、顺手把广告
                    // 插件装上——等 onPageFinished 再注入就晚了。这里注入是「尽量早」，
                    // 不是保证：万一落到了旧文档上，onPageFinished 那次和脚本自身的
                    // MutationObserver + 轮询还能兜住（见 AdBlockScript 的注释）。
                    if (blockTrackers()) {
                        runCatching { view.evaluateJavascript(AdBlockScript.SCRIPT, null) }
                    }
                    onPageStarted(tabId)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    onPageFinished(tabId, view.title.orEmpty(), url)
                    // 三段脚本合在一次 evaluateJavascript 里注入，省两次跨进程往返：
                    // - 去广告（与 onPageStarted 同一次注入，幂等，这里是兜底）；
                    // - 悬浮工具栏盖在网页之上，页面末尾不留白的话，滚到底时最后几行会
                    //   永久停在工具栏底下、滚也滚不出来，所以给文档尾部补一段滚动空间；
                    // - 给页面里的每个 <video> 贴上「全屏 / 下载 / 投屏」浮层。
                    // 三段都是完整语句的 IIFE，拼起来等价于依次执行。
                    val adBlock = if (blockTrackers()) AdBlockScript.SCRIPT + "\n" else ""
                    runCatching {
                        view.evaluateJavascript(
                            adBlock +
                                bottomInsetScript(PageBottomInsetCssPx) + "\n" + MediaOverlay.SCRIPT,
                        ) { raw ->
                            // 正常是 "ok"；脚本语法错或被站点拦下来时这里会是错误文本，
                            // debug 构建下留一条日志，省得再靠猜（见 README 的排查记录）。
                            if (BuildConfig.DEBUG && raw != "\"ok\"") {
                                Log.d(TAG, "page script injection -> $raw")
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
                    // 只放行主框架的跳转。子框架（广告 iframe）里的 `intent://`、`market://`
                    // 不该在用户没点任何东西的情况下拉起外部应用——那是「打开网页就被拉走」
                    // 的一条路子。拦掉它，页面本身的展示不受影响。
                    if (!request.isForMainFrame) return true
                    // 主框架这一半同样要有手势：页面自己执行 `location.href='weixin://…'`
                    // 是没有用户手势的（`hasGesture()` 为 false），放行等于打开一个页面就
                    // 被拉走。只有用户真点了链接（`tel:`/`mailto:`/`intent:` 这些）才交系统。
                    if (!request.hasGesture()) return true
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
                    // **主文档永远放行。** 名单是按域名匹配的，而不少站的播放页、跳转页自己
                    // 就挂在统计域名下（短链、聚合站尤其常见）。命中之后我们回一个空响应，
                    // 用户看到的就是一片纯白、地址栏还停在那个域名——「点了链接网页全白」。
                    // 追踪脚本永远不是主文档，拦主文档没有任何收益，只有白屏这一个后果。
                    if (request.isForMainFrame) return null
                    if (!dev.aurora.browser.domain.TrackerBlocklist.isBlocked(request.url.toString())) {
                        return null
                    }
                    onTrackerBlocked(tabId)
                    return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)),
                    )
                }

                /**
                 * 渲染进程崩了。
                 *
                 * 不接管的话，WebView 默认会把整个应用一起带走（进程直接死），而视频站
                 * 把渲染进程搞崩是常事。接管之后：这个 WebView 已经不能再用，先从池里
                 * 摘掉并 `destroy()`（官方要求销毁，留着也画不出东西），再通知界面重建标签。
                 *
                 * 返回 true = 我们处理了，别让应用跟着崩。
                 */
                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.w(TAG, "渲染进程挂了，didCrash=${detail.didCrash()}")
                    // 池里的 Map 与 `destroy()` 都只该在主线程动。这个回调按文档在 UI 线程，
                    // 但挪回主线程的代价只是晚一帧，不必赌文档。
                    main.post {
                        dispose(tabId)
                        onRenderGone(tabId)
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    onProgress(tabId, newProgress)
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    onTitle(tabId, title.orEmpty())
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

/**
 * 从窗口里抠一块像素出来。
 *
 * 抽成接口只为一件事：`PixelCopy` 需要真实窗口，单测里没有。注入一个假的就能把
 * 「算取景框 → 抠图 → 判空白 → 缩放 → 存缓存」这条路走通，而不用去凑一个真窗口。
 */
fun interface WindowCapture {
    fun capture(window: Window, source: Rect, dest: Bitmap, onResult: (Boolean) -> Unit)
}

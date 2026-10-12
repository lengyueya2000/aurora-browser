package dev.aurora.browser.ui.browser

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.Window
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.data.DownloadRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** `WebViewPool` 的行为：每标签一个实例、按标签回收、事件出口、加固配置与 scheme 分流。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewPoolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class CountingFactory {
        val created = mutableListOf<WebView>()
        val factory: (Context) -> WebView = { ctx -> WebView(ctx).also { created += it } }
    }

    private fun newPool(f: CountingFactory) = WebViewPool(context, f.factory)

    private fun newDesktopPool(f: CountingFactory, desktop: () -> Boolean) =
        WebViewPool(context, f.factory, desktop)

    private class FakeRequest(
        private val raw: String,
        private val mainFrame: Boolean = true,
        private val gesture: Boolean = true,
    ) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(raw)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = gesture
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    /** 平台里这个构造函数被标了 deprecated（只能由框架构造），测试里照用，压掉警告。 */
    @Suppress("DEPRECATION")
    private class FakeRenderGone(private val didCrash: Boolean) : RenderProcessGoneDetail() {
        override fun didCrash(): Boolean = didCrash
        override fun rendererPriorityAtExit(): Int = 0
    }

    /**
     * 核心修复的回归：所有标签共用一个 WebView 时，`loadUrl` 会把新标签压进同一个
     * 前进/后退栈，切标签后按返回键会退到另一个标签的页面。
     */
    @Test fun eachTabGetsItsOwnWebView() {
        val f = CountingFactory()
        val pool = newPool(f)
        val a = pool.get("a", false)
        val b = pool.get("b", false)
        assertThat(a).isNotSameInstanceAs(b)
        assertThat(f.created).hasSize(2)
        // 同一标签再次取，仍是同一个实例
        assertThat(pool.get("a", false)).isSameInstanceAs(a)
        assertThat(pool.retainedTabIds()).containsExactly("a", "b")
    }

    @Test fun retainDisposesOnlyClosedTabs() {
        val f = CountingFactory()
        val pool = newPool(f)
        val a = pool.get("a", false)
        val b = pool.get("b", false)
        pool.retain(setOf("b"))
        assertThat(pool.retainedTabIds()).containsExactly("b")
        assertThat(shadowOf(a).wasDestroyCalled()).isTrue()
        assertThat(shadowOf(b).wasDestroyCalled()).isFalse()
    }

    @Test fun disposeAllDestroysEveryWebView() {
        val f = CountingFactory()
        val pool = newPool(f)
        val a = pool.get("a", false)
        val b = pool.get("b", false)
        pool.disposeAll()
        assertThat(pool.retainedTabIds()).isEmpty()
        assertThat(shadowOf(a).wasDestroyCalled()).isTrue()
        assertThat(shadowOf(b).wasDestroyCalled()).isTrue()
    }

    /** WebView 比组合活得久，客户端每次读字段——晚绑定的回调必须生效。 */
    @Test fun eventsReadTheLatestLambdaAtCallTime() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        var first = -1
        var second = -1
        pool.onProgress = { _, p -> first = p }
        shadowOf(web).webChromeClient.onProgressChanged(web, 10)
        pool.onProgress = { _, p -> second = p }
        shadowOf(web).webChromeClient.onProgressChanged(web, 42)
        assertThat(first).isEqualTo(10)
        assertThat(second).isEqualTo(42)
    }

    @Test fun pageFinishedReportsTabIdAndCurrentUrl() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("t7", false)
        var seen: Triple<String, String, String>? = null
        pool.onPageFinished = { id, title, url -> seen = Triple(id, title, url) }
        web.loadUrl("https://a.com")
        shadowOf(web).webViewClient.onPageFinished(web, "https://a.com")
        assertThat(seen).isNotNull()
        assertThat(seen!!.first).isEqualTo("t7")
        assertThat(seen!!.third).isEqualTo("https://a.com")
        // 标题取自 WebView 自身（Robolectric 没设过 title 时会回落到当前地址）
        assertThat(seen!!.second).isEqualTo(web.title.orEmpty())
    }

    /** 页面里的 tel:/mailto:/intent: 链接（用户点的那一下）必须交给系统，否则点了没反应。 */
    @Test fun externalSchemesLeaveTheWebView() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        var external: String? = null
        pool.onExternalUrl = { _, url -> external = url }
        val client = shadowOf(web).webViewClient

        assertThat(client.shouldOverrideUrlLoading(web, FakeRequest("tel:10086", gesture = true)))
            .isTrue()
        assertThat(external).isEqualTo("tel:10086")

        external = null
        assertThat(
            client.shouldOverrideUrlLoading(web, FakeRequest("weixin://dl/business", gesture = true)),
        ).isTrue()
        assertThat(external).isEqualTo("weixin://dl/business")
    }

    /**
     * 主框架里**没有用户手势**的自定义 scheme 跳转不交给系统。
     *
     * 页面自己执行 `location.href='weixin://…'` 时 `hasGesture()` 是 false——放行的话，
     * 打开一个页面就会被拉走。只有用户真点了链接才交系统（见上一条用例）。
     */
    @Test fun mainFrameSchemeWithoutGestureIsBlocked() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        var external: String? = null
        pool.onExternalUrl = { _, url -> external = url }
        val client = shadowOf(web).webViewClient

        assertThat(
            client.shouldOverrideUrlLoading(
                web,
                FakeRequest("weixin://dl/business", gesture = false),
            ),
        ).isTrue()
        assertThat(external).isNull()
    }

    @Test fun httpAndHttpsStayInsideTheWebView() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        var external: String? = null
        pool.onExternalUrl = { _, url -> external = url }
        val client = shadowOf(web).webViewClient

        assertThat(client.shouldOverrideUrlLoading(web, FakeRequest("https://a.com"))).isFalse()
        assertThat(client.shouldOverrideUrlLoading(web, FakeRequest("http://a.com"))).isFalse()
        assertThat(external).isNull()
    }

    /**
     * 子框架里的自定义 scheme 不交给系统。
     *
     * 不拦的话，广告 iframe 里的 `intent://`、`market://` 会在用户没点任何东西的情况下
     * 拉起外部应用——一条「打开网页就被拉走」的路子。拦掉它不影响页面本身的展示。
     */
    @Test fun externalSchemesFromSubFramesAreBlocked() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        var external: String? = null
        pool.onExternalUrl = { _, url -> external = url }
        val client = shadowOf(web).webViewClient

        assertThat(
            client.shouldOverrideUrlLoading(
                web,
                FakeRequest("intent://x#Intent;package=com.x;end", mainFrame = false),
            ),
        ).isTrue()
        assertThat(external).isNull()
    }

    /**
     * 渲染进程崩了：把那个已经作废的实例从池里摘掉，并通知界面换一个。
     *
     * 不接管这个回调的话，WebView 默认会让整个应用跟着一起死；而崩掉的实例画不出任何
     * 东西，留着就是一个空白死壳子。
     */
    @Test fun aCrashedRendererRetiresTheTabAndNotifies() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        var gone: String? = null
        pool.onRenderGone = { gone = it }
        val client = shadowOf(web).webViewClient

        val handled = client.onRenderProcessGone(web, FakeRenderGone(didCrash = true))
        idleMain()

        assertThat(handled).isTrue()               // 自己处理了，别让进程跟着崩
        assertThat(pool.retainedTabIds()).isEmpty() // 死实例不能留在池里
        assertThat(gone).isEqualTo("a")
    }

    /**
     * 主文档加载失败才整页报错；子资源（图片/广告）失败不该把页面判死。
     *
     * 只测判定函数：`WebResourceError` 是 final 类且构造函数包内可见，
     * 造不出实例来驱动 `onReceivedError` 本身。
     */
    @Test fun onlyMainFrameErrorsAreReported() {
        assertThat(WebViewPool.isMainFrameError(FakeRequest("https://a.com", mainFrame = false)))
            .isFalse()
        assertThat(WebViewPool.isMainFrameError(FakeRequest("https://a.com", mainFrame = true)))
            .isTrue()
    }

    @Test fun trackerRequestsAreBlockedOnlyWhenEnabled() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        var blocked = 0
        pool.onTrackerBlocked = { blocked++ }
        val client = shadowOf(web).webViewClient

        // 追踪脚本都是子资源，所以这一组用例一律 mainFrame = false（主文档的情况见下一个测试）。
        pool.blockTrackers = { false }
        assertThat(
            client.shouldInterceptRequest(
                web, FakeRequest("https://www.google-analytics.com/collect", mainFrame = false),
            ),
        ).isNull()

        pool.blockTrackers = { true }
        assertThat(
            client.shouldInterceptRequest(
                web, FakeRequest("https://www.google-analytics.com/collect", mainFrame = false),
            ),
        ).isNotNull()
        assertThat(
            client.shouldInterceptRequest(web, FakeRequest("https://a.com/app.js", mainFrame = false)),
        ).isNull()
        assertThat(blocked).isEqualTo(1)
    }

    /**
     * 主文档**永远不拦**，哪怕它命中了追踪名单。
     *
     * 名单是按域名匹配的，而不少站的播放页/跳转页自己就挂在统计域名下。拦下来我们回一个空
     * 响应，用户看到的是「点了链接，整页纯白」——而且地址栏还停在那个域名上，完全看不出发生
     * 了什么。追踪脚本不会是主文档，拦主文档没有任何收益。
     */
    @Test fun mainFrameNavigationIsNeverBlockedAsTracker() {
        val pool = newPool(CountingFactory())
        val web = pool.get("a", false)
        var blocked = 0
        pool.onTrackerBlocked = { blocked++ }
        pool.blockTrackers = { true }
        val client = shadowOf(web).webViewClient

        assertThat(client.shouldInterceptRequest(web, FakeRequest("https://www.google-analytics.com/collect")))
            .isNull()
        assertThat(blocked).isEqualTo(0)
    }

    /**
     * MSE 站点的真实流只出现在网络层：页面里的 `<video>` 是 `blob:` 伪地址
     * （欧乐影院就是 plyr + hls.js）。所以每个请求都要喂给嗅探器，全屏/下载/投屏才有地址可用。
     */
    @Test fun mediaRequestsAreSniffedPerTab() {
        val pool = newPool(CountingFactory())
        val web = pool.get("sniff", false)
        val client = shadowOf(web).webViewClient

        client.shouldInterceptRequest(web, FakeRequest("https://c.com/v/master.m3u8"))
        client.shouldInterceptRequest(web, FakeRequest("https://c.com/v/seg-1-v1-a1.ts"))
        assertThat(pool.sniffedPlayableUrl("sniff")).isEqualTo("https://c.com/v/master.m3u8")

        // 直链站点没有播放列表，嗅探结果就是那个文件本身。
        client.shouldInterceptRequest(web, FakeRequest("https://c.com/v/b.mp4"))
        assertThat(pool.sniffedDirectFile("sniff")).isEqualTo("https://c.com/v/b.mp4")

        // 非媒体请求（JS/CSS/图片）不该污染结果。
        client.shouldInterceptRequest(web, FakeRequest("https://c.com/app.js"))
        assertThat(pool.sniffedPlayableUrl("sniff")).isEqualTo("https://c.com/v/master.m3u8")

        pool.dispose("sniff")
        assertThat(pool.sniffedPlayableUrl("sniff")).isNull()
    }

    /** 换页要清掉上一条视频的嗅探结果，否则新页面点「下载」会下到上一个视频。 */
    @Test fun pageStartClearsSniffedMedia() {
        val pool = newPool(CountingFactory())
        val web = pool.get("sniff2", false)
        val client = shadowOf(web).webViewClient
        client.shouldInterceptRequest(web, FakeRequest("https://c.com/v/master.m3u8"))
        assertThat(pool.sniffedPlayableUrl("sniff2")).isNotNull()

        client.onPageStarted(web, "https://a.com/next", null)
        assertThat(pool.sniffedPlayableUrl("sniff2")).isNull()
    }

    @Test fun downloadsAreHandedToTheUiLayer() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        var request: DownloadRequest? = null
        pool.onDownloadRequest = { _, r -> request = r }
        shadowOf(web).downloadListener.onDownloadStart(
            "https://a.com/f.zip", "UA/1.0", "attachment; filename=f.zip", "application/zip", 1234L,
        )
        assertThat(request?.url).isEqualTo("https://a.com/f.zip")
        assertThat(request?.contentDisposition).isEqualTo("attachment; filename=f.zip")
        assertThat(request?.contentLength).isEqualTo(1234L)
    }

    /** 文件访问与多窗口一律关闭：网页不该能读本机文件、也不该自己开新窗口。 */
    @Test fun settingsAreHardened() {
        val f = CountingFactory()
        val web = newPool(f).get("a", false)
        val s = web.settings
        assertThat(s.javaScriptEnabled).isTrue()          // 浏览器必须能跑 JS
        assertThat(s.allowFileAccess).isFalse()
        assertThat(s.allowContentAccess).isFalse()
        assertThat(s.supportMultipleWindows()).isFalse()
        assertThat(s.javaScriptCanOpenWindowsAutomatically).isFalse()
        assertThat(s.mixedContentMode).isEqualTo(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE)
        assertThat(s.domStorageEnabled).isTrue()
    }

    /** 无痕标签关掉 DOM 存储与缓存。 */
    @Test fun incognitoTurnsOffDomStorageAndCache() {
        val f = CountingFactory()
        val web = newPool(f).get("a", true)
        assertThat(web.settings.domStorageEnabled).isFalse()
        assertThat(web.settings.cacheMode).isEqualTo(WebSettings.LOAD_NO_CACHE)
    }

    @Test fun evaluateDoesNothingForUnknownTab() {
        val f = CountingFactory()
        val pool = newPool(f)
        var called = false
        pool.evaluate("missing", "1+1") { title, text ->
            called = true
            assertThat(title).isEmpty()
            assertThat(text).isEmpty()
        }
        assertThat(called).isTrue()
    }

    @Test fun evaluateRunsTheScriptInTheTabsWebView() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        pool.evaluate("a", "return 1") { _, _ -> }
        assertThat(shadowOf(web).lastEvaluatedJavascript).isEqualTo("return 1")
    }

    /**
     * 真机回归：底部悬浮工具栏浮在网页之上，页面末尾不留白的话，滚到底时最后几行
     * 会永久停在工具栏底下滚不出来（在雷电模拟器的长页面上复现过）。
     */
    @Test fun pageFinishedInjectsBottomSpaceForTheFloatingToolbar() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        shadowOf(web).webViewClient.onPageFinished(web, "https://a.com")
        val script = shadowOf(web).lastEvaluatedJavascript
        assertThat(script).contains(WebViewPool.BottomInsetElementId)
        assertThat(script).contains(WebViewPool.PageBottomInsetCssPx.toString())
    }

    /**
     * 留白元素不能抢走页面的点击，否则底部那一条上的链接/按钮会失效；
     * 也不该在本来就不滚动的短页面上凭空多出一段空白。
     */
    @Test fun bottomInsetScriptIsNonBlockingIdempotentAndGuarded() {
        val script = WebViewPool.bottomInsetScript(106)
        assertThat(script).contains("pointer-events:none")
        // 先删旧的再加新的：SPA 会反复触发 onPageFinished。
        assertThat(script).contains("removeChild")
        // 页面本来就不滚动时直接返回，不留白。
        assertThat(script).contains("scrollHeight")
        assertThat(script).contains("window.addEventListener('load', run)")
    }

    private class FakePermissionRequest(private val resources: Array<String>) : PermissionRequest() {
        var granted: Array<String>? = null
        var denied = false
        override fun getOrigin(): Uri = Uri.parse("https://a.com")
        override fun getResources(): Array<String> = resources
        override fun grant(resources: Array<String>) { granted = resources }
        override fun deny() { denied = true }
    }

    /**
     * 网页视频全屏：WebView 通过 `WebChromeClient` 把要全屏播放的 View 交出来，
     * 池必须原样转给 UI 层。真机上不接管这个回调，点全屏就没反应（视频留在小窗里）。
     */
    @Test fun customViewFullscreenIsHandedToTheUiLayer() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        val view = View(context)
        val callback = object : WebChromeClient.CustomViewCallback {
            override fun onCustomViewHidden() {}
        }
        var shown: View? = null
        var hidden = 0
        pool.onShowFullscreenView = { v, _ -> shown = v }
        pool.onHideFullscreenView = { hidden++ }

        shadowOf(web).webChromeClient.onShowCustomView(view, callback)
        assertThat(shown).isSameInstanceAs(view)

        shadowOf(web).webChromeClient.onHideCustomView()
        assertThat(hidden).isEqualTo(1)
    }

    /**
     * 站点权限只放行 DRM 的 protected media id（全屏播放加密流要用），
     * 相机 / 麦克风 / 定位继续拒绝——这是隐私页对用户的承诺。
     */
    @Test fun permissionRequestsOnlyGrantProtectedMedia() {
        val f = CountingFactory()
        val web = newPool(f).get("a", false)
        val chrome = shadowOf(web).webChromeClient

        val camera = FakePermissionRequest(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
        chrome.onPermissionRequest(camera)
        assertThat(camera.denied).isTrue()
        assertThat(camera.granted).isNull()

        val drm = FakePermissionRequest(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
        chrome.onPermissionRequest(drm)
        assertThat(drm.granted).asList()
            .containsExactly(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
        assertThat(drm.denied).isFalse()

        // 混合请求：只授予 DRM，其余仍然拒绝。
        val mixed = FakePermissionRequest(
            arrayOf(
                PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID,
                PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            ),
        )
        chrome.onPermissionRequest(mixed)
        assertThat(mixed.granted).asList()
            .containsExactly(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)
    }

    /** 下载视频走的是「进页面找 <video>/<source>」，靠 evaluateJavascript 注入提取脚本。 */
    @Test fun evaluateVideosInjectsTheExtractorScript() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        pool.evaluateVideos("a") { }
        assertThat(shadowOf(web).lastEvaluatedJavascript).isEqualTo(VideoExtractor.SCRIPT)
    }

    /** 页面加载完要把「全屏 / 下载 / 投屏」浮层注进去（和尾部留白脚本同一次注入）。 */
    @Test fun pageFinishedInjectsTheMediaOverlay() {
        val pool = newPool(CountingFactory())
        val web = pool.get("a", false)
        shadowOf(web).webViewClient.onPageFinished(web, "https://a.com")
        assertThat(shadowOf(web).lastEvaluatedJavascript).contains(MediaOverlay.SCRIPT)
    }

    /**
     * 去广告脚本要在 `onPageStarted` 就注入，不能等 `onPageFinished`。
     *
     * 站点的前贴片广告配置写在播放器容器的 `data-*` 上，页面脚本在 DOMContentLoaded 时
     * 就读走它并把广告插件装上——那时候再注入已经晚了。
     */
    @Test fun pageStartInjectsAdBlockBeforePageScripts() {
        val pool = newPool(CountingFactory())
        pool.blockTrackers = { true }
        val web = pool.get("adblock", false)
        shadowOf(web).webViewClient.onPageStarted(web, "https://a.com", null)
        assertThat(shadowOf(web).lastEvaluatedJavascript)
            .contains(AdBlockScript.PREROLL_ATTRIBUTE_PREFIX)
    }

    /** 关掉拦截开关时两处都不注入——用户在隐私页关掉的就是这个开关。 */
    @Test fun adBlockIsNotInjectedWhenBlockingIsOff() {
        val pool = newPool(CountingFactory())
        pool.blockTrackers = { false }
        val web = pool.get("adblock-off", false)
        val client = shadowOf(web).webViewClient
        client.onPageStarted(web, "https://a.com", null)
        assertThat(shadowOf(web).lastEvaluatedJavascript ?: "").doesNotContain("__auroraAdBlock")
        client.onPageFinished(web, "https://a.com")
        assertThat(shadowOf(web).lastEvaluatedJavascript ?: "").doesNotContain("__auroraAdBlock")
    }

    /** `onPageFinished` 那次是兜底：`onPageStarted` 的注入可能落到旧文档上。 */
    @Test fun pageFinishedReinjectsAdBlockAsABackstop() {
        val pool = newPool(CountingFactory())
        pool.blockTrackers = { true }
        val web = pool.get("adblock-late", false)
        shadowOf(web).webViewClient.onPageFinished(web, "https://a.com")
        val script = shadowOf(web).lastEvaluatedJavascript
        assertThat(script).contains("__auroraAdBlock")
        // 同一次注入里另外两段还得在，别把浮层和留白挤掉。
        assertThat(script).contains(MediaOverlay.SCRIPT)
        assertThat(script).contains(WebViewPool.BottomInsetElementId)
    }

    /**
     * 页面上的按钮点下去要能到宿主，而且是**在主线程**——桥的方法跑在 WebView 的 JS
     * 线程上，直接回调会让宿主在非主线程碰 Compose 状态和系统服务。
     */
    @Test fun mediaBridgeHopsBackToTheMainThread() {
        val pool = newPool(CountingFactory())
        val web = pool.get("a", false)
        var seen: List<String>? = null
        pool.onMediaAction = { tabId, action, url, type -> seen = listOf(tabId, action, url, type) }
        val bridge = shadowOf(web).getJavascriptInterface(MediaOverlay.BRIDGE_NAME) as MediaBridge
        bridge.onMediaAction("download", "https://a.com/v.mp4", "video/mp4")
        assertThat(seen).isNull() // 还没轮到主线程
        shadowOf(Looper.getMainLooper()).idle()
        // 桥绑在这个标签上，宿主拿到的第一个参数必须是 "a"——不是「当前激活的标签」。
        assertThat(seen).containsExactly("a", "download", "https://a.com/v.mp4", "video/mp4")
    }

    /** 标签不存在时立刻回空列表，别把回调挂死。 */
    @Test fun evaluateVideosOnUnknownTabReturnsEmpty() {
        val pool = newPool(CountingFactory())
        var sources: List<VideoSource>? = null
        pool.evaluateVideos("missing") { sources = it }
        assertThat(sources).isEmpty()
    }

    /** 原始返回值接口在标签不存在时回 null，调用方据此走降级分支。 */
    @Test fun evaluateRawOnUnknownTabReturnsNull() {
        val pool = newPool(CountingFactory())
        var raw: String? = "sentinel"
        pool.evaluateRaw("missing", "1+1") { raw = it }
        assertThat(raw).isNull()
    }

    // ---------- 请求桌面版网站 ----------

    /**
     * 开关一拨，**已经建好**的标签也要换 UA，并且要重新加载。
     *
     * 只改 UA 不重载的话，用户点了开关看到的是同一个手机版页面——站点早在发页面时
     * 就按老 UA 决定了给哪一套。
     */
    @Test fun applyDesktopModeSwitchesUserAgentAndReloads() {
        val f = CountingFactory()
        val pool = newDesktopPool(f) { false }
        val web = pool.get("a", false)
        val deviceDefault = web.settings.userAgentString
        web.loadUrl("https://a.com")
        assertThat(deviceDefault).isNotEmpty()

        pool.applyDesktopMode(true)
        assertThat(web.settings.userAgentString).isEqualTo(DesktopMode.DESKTOP_USER_AGENT)
        assertThat(shadowOf(web).reloadInvocations).isEqualTo(1)

        pool.applyDesktopMode(false)
        assertThat(web.settings.userAgentString).isEqualTo(deviceDefault)
        assertThat(shadowOf(web).reloadInvocations).isEqualTo(2)
    }

    /** 空白标签没什么可刷新的，别平白发一次请求。 */
    @Test fun applyDesktopModeDoesNotReloadAnEmptyTab() {
        val pool = newDesktopPool(CountingFactory()) { false }
        val web = pool.get("a", false)
        pool.applyDesktopMode(true)
        assertThat(web.settings.userAgentString).isEqualTo(DesktopMode.DESKTOP_USER_AGENT)
        assertThat(shadowOf(web).reloadInvocations).isEqualTo(0)
    }

    /** UA 没变就不重载：每次回到前台都会走一遍这里，白重载等于页面莫名其妙自己刷新。 */
    @Test fun applyDesktopModeIsANoOpWhenTheUserAgentAlreadyMatches() {
        val pool = newDesktopPool(CountingFactory()) { false }
        val web = pool.get("a", false)
        web.loadUrl("https://a.com")
        pool.applyDesktopMode(true)
        pool.applyDesktopMode(true)
        assertThat(shadowOf(web).reloadInvocations).isEqualTo(1)
    }

    /** 池是复用的，新标签得读到**当前**设置，而不是池创建那一刻的值。 */
    @Test fun tabsCreatedWhileDesktopModeIsOnStartDesktop() {
        var desktop = false
        val pool = newDesktopPool(CountingFactory()) { desktop }
        assertThat(pool.get("a", false).settings.userAgentString)
            .isNotEqualTo(DesktopMode.DESKTOP_USER_AGENT)

        desktop = true
        assertThat(pool.get("b", false).settings.userAgentString)
            .isEqualTo(DesktopMode.DESKTOP_USER_AGENT)
    }

    // ---------- 标签缩略图 ----------

    @Test fun noThumbnailBeforeAnyCapture() {
        val pool = newPool(CountingFactory())
        pool.get("a", false)
        assertThat(pool.thumbnail("a")).isNull()
    }

    /** 标签不存在时回 null，别让调用方去处理异常。 */
    @Test fun capturingAnUnknownTabReturnsNull() {
        val pool = newPool(CountingFactory())
        assertThat(pool.captureThumbnail("missing")).isNull()
    }

    /** 空白标签页没有内容可拍，也就没有缩略图。 */
    @Test fun capturingATabWithoutAPageReturnsNull() {
        val pool = newPool(CountingFactory())
        val web = pool.get("a", false)
        assertThat(web.url).isNull()
        assertThat(pool.captureThumbnail("a")).isNull()
        assertThat(pool.thumbnail("a")).isNull()
    }

    /**
     * 没在显示着的标签同步也拍不到。
     *
     * 判据是「挂没挂在窗口上」（见 [WebViewPool.Companion.attachedBounds]）。Robolectric 里
     * 挂上去也画不出真实网页，所以「拍出来是空白」那条分支由 [asyncCaptureRejectsAUniformWindow]
     * 和 [blankDetectionSamplesTheWholeImage] 覆盖。
     */
    @Test fun capturingAnUndisplayedTabReturnsNull() {
        val pool = newPool(CountingFactory())
        val web = pool.get("a", false)
        web.loadUrl("https://a.com")
        assertThat(web.isAttachedToWindow).isFalse()
        assertThat(pool.captureThumbnail("a")).isNull()
        assertThat(pool.thumbnail("a")).isNull()
    }

    /** 关闭标签时缩略图一起丢掉，不然内存里会留着已经关掉的页面。 */
    @Test fun disposingATabForgetsItsThumbnail() {
        val pool = newPool(CountingFactory())
        pool.get("a", false)
        pool.dispose("a")
        assertThat(pool.thumbnail("a")).isNull()
    }

    /** 纯色图判为空白，有一点变化就算拍到了。 */
    @Test fun blankDetectionSamplesTheWholeImage() {
        val uniform = Bitmap.createBitmap(320, 192, Bitmap.Config.RGB_565)
        assertThat(WebViewPool.isBlankThumbnail(uniform)).isTrue()

        // 抽样只看网格上的点，所以这里造一块成片的色差（真实页面必然有），
        // 而不是改一个像素——那可能正好落在采样点之间。
        val withContent = Bitmap.createBitmap(320, 192, Bitmap.Config.RGB_565)
        withContent.eraseColor(android.graphics.Color.WHITE)
        Canvas(withContent).drawRect(
            0f, 96f, 320f, 192f,
            android.graphics.Paint().apply { color = android.graphics.Color.RED },
        )
        assertThat(WebViewPool.isBlankThumbnail(withContent)).isFalse()
    }

    // ---------- 缩略图：真机走的 PixelCopy 那条路 ----------

    /**
     * 记录取景框的假取景器。真机由 `PixelCopy` 实现，测试里没有真窗口，用它顶替。
     *
     * [ok] 模拟 `PixelCopy` 的结果码，[blankTimes] 模拟「抠到了但窗口还没画出页面」——
     * 前几次抠到纯色，之后才有内容。
     */
    private class FakeCapture(
        private val ok: Boolean = true,
        private val blankTimes: Int = 0,
    ) : WindowCapture {
        val sources = mutableListOf<Rect>()
        override fun capture(
            window: Window,
            source: Rect,
            dest: Bitmap,
            onResult: (Boolean) -> Unit,
        ) {
            sources += Rect(source)
            if (ok) {
                dest.eraseColor(android.graphics.Color.WHITE)
                if (sources.size > blankTimes) {
                    // 上下两半不同色：真实页面必然有这种色差，纯色会被当成「没拍到」。
                    Canvas(dest).drawRect(
                        0f, 0f, dest.width.toFloat(), dest.height / 2f,
                        android.graphics.Paint().apply { color = android.graphics.Color.RED },
                    )
                }
            }
            onResult(ok)
        }
    }

    /**
     * 假装 WebView 占了窗口里的一块（含偏移，真机上它就在地址栏下面）。
     *
     * Robolectric 的 `WebView` 量不出尺寸——`layout()` 对它是空操作，所以真实取尺寸的那段
     * 在这儿永远回 null，抠图逻辑也就一步都走不到。注入一个固定矩形才测得到后面的事。
     */
    private val fakeBounds: (WebView) -> Rect? = { Rect(10, 160, 730, 1440) }

    private fun newCapturingPool(
        activity: Activity,
        capture: WindowCapture,
        bounds: ((WebView) -> Rect?)? = fakeBounds,
    ) = WebViewPool(
        activity,
        { ctx -> WebView(ctx) },
        { false },
        capture,
        bounds ?: WebViewPool.Companion::attachedBounds,
    )

    /**
     * 建一个挂在窗口上、已经加载过页面的标签。
     *
     * 真机上只有正显示着的那一个标签拍得到图——没挂窗口的 WebView 画不出东西。测试里
     * 也得照这个前提来，否则测的就不是真机会遇到的情况。
     */
    private fun attachedTab(
        capture: WindowCapture,
        tabId: String = "a",
        url: String = "https://a.com",
    ): Pair<WebViewPool, WebView> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val pool = newCapturingPool(activity, capture)
        val web = pool.get(tabId, false)
        activity.setContentView(web)
        web.loadUrl(url)
        return pool to web
    }

    private fun window(web: WebView): Window = (web.context as Activity).window

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** 把时钟往前推，让延后拍的这一拍真正跑起来。 */
    private fun advance(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /** 时间表上第 n 拍之前的等待。 */
    private fun CaptureGap(index: Int) = WebViewPool.CaptureGapsMs[index]

    /** 把时间表上的每一拍都推完（每一拍都失败时才会走到最后一次）。 */
    private fun exhaustCaptureAttempts() =
        WebViewPool.CaptureGapsMs.forEach { advance(it + 50) }

    @Test fun asyncCaptureStoresAScaledThumbnail() {
        val capture = FakeCapture()
        val (pool, web) = attachedTab(capture)
        var captured: Bitmap? = null

        pool.captureThumbnailAsync("a", window(web), bottomInsetPx = 0) { captured = it }
        advance(CaptureGap(0))

        assertThat(capture.sources).hasSize(1)
        assertThat(captured).isNotNull()
        assertThat(captured!!.width).isEqualTo(WebViewPool.ThumbnailWidth)
        assertThat(captured!!.height).isEqualTo(WebViewPool.ThumbnailHeight)
        assertThat(pool.thumbnail("a")).isSameInstanceAs(captured)
    }

    /** 悬浮工具栏会叠在网页上，抠图时必须把它那一条裁掉，否则缩略图里挂着一颗胶囊。 */
    @Test fun asyncCaptureTrimsTheToolbarBand() {
        val capture = FakeCapture()
        val (pool, web) = attachedTab(capture)

        pool.captureThumbnailAsync("a", window(web), bottomInsetPx = 200) {}
        advance(CaptureGap(0))

        // 取景框跟着 WebView 的位置走（真机上它不在窗口原点），只把底边往上收。
        val source = capture.sources.single()
        assertThat(source).isEqualTo(Rect(10, 160, 730, 1440 - 200))
    }

    /** PixelCopy 自己失败时同样退回 `draw()`，几次都没拍到就回 null。 */
    @Test fun asyncCaptureFallsBackWhenTheCopyFails() {
        val (pool, web) = attachedTab(FakeCapture(ok = false))
        var called = false

        pool.captureThumbnailAsync("a", window(web)) { called = true }
        exhaustCaptureAttempts()

        assertThat(called).isTrue()
        assertThat(pool.thumbnail("a")).isNull()
    }

    /** 没有窗口（预览、单测）时不该崩，直接走同步兜底。 */
    @Test fun asyncCaptureWithoutAWindowFallsBack() {
        val (pool, _) = attachedTab(FakeCapture())
        var captured: Bitmap? = null

        pool.captureThumbnailAsync("a", window = null) { captured = it }
        exhaustCaptureAttempts()

        assertThat(captured).isNull()
    }

    /** 页面加载完到拍图之间要留出上屏时间，所以第一拍是延后的。 */
    @Test fun asyncCaptureWaitsBeforeTheFirstShot() {
        val (pool, web) = attachedTab(FakeCapture())
        var captured = false

        pool.captureThumbnailAsync("a", window(web)) { captured = true }
        idleMain()
        assertThat(captured).isFalse()

        advance(CaptureGap(0))
        assertThat(captured).isTrue()
    }

    /**
     * 第一次拍到纯色（页面还没画上屏）要接着试，不能就此放弃。
     *
     * 实测就是这么回事：第一次 `onPageFinished` 后 400ms 抠到的仍是一整块底色，同一个页面
     * 第二次加载完成时才拍得到。只拍一次的话，缩略图永远停在占位图上。
     */
    @Test fun asyncCaptureRetriesAfterABlankShot() {
        val capture = FakeCapture(blankTimes = 1)
        val (pool, web) = attachedTab(capture)
        var captured: Bitmap? = null

        pool.captureThumbnailAsync("a", window(web)) { captured = it }

        advance(CaptureGap(0))
        assertThat(capture.sources).hasSize(1)
        assertThat(captured).isNull()          // 这一拍是纯色，没存

        advance(CaptureGap(1))
        assertThat(capture.sources).hasSize(2)
        assertThat(captured).isNotNull()
        assertThat(pool.thumbnail("a")).isSameInstanceAs(captured)
    }

    /** 几次都拍不到就认了，别无限重试下去。 */
    @Test fun asyncCaptureGivesUpAfterTheLastAttempt() {
        val capture = FakeCapture(blankTimes = Int.MAX_VALUE)
        val (pool, web) = attachedTab(capture)
        var called = false

        pool.captureThumbnailAsync("a", window(web)) { called = true }
        exhaustCaptureAttempts()

        assertThat(capture.sources).hasSize(WebViewPool.CaptureGapsMs.size)
        assertThat(called).isTrue()
        assertThat(pool.thumbnail("a")).isNull()
    }

    /**
     * 每一拍都回调，最后留下的必须是最后那张。
     *
     * 因为画面是逐段画出来的：早拍到的那张可能只有上半个屏幕是页面、下半个还是底色。
     */
    @Test fun asyncCaptureKeepsTheLatestShot() {
        val capture = FakeCapture()
        val (pool, web) = attachedTab(capture)
        val seen = mutableListOf<Bitmap>()

        pool.captureThumbnailAsync("a", window(web)) { it?.let(seen::add) }
        exhaustCaptureAttempts()

        assertThat(capture.sources).hasSize(WebViewPool.CaptureGapsMs.size)
        assertThat(seen).hasSize(WebViewPool.CaptureGapsMs.size)
        assertThat(pool.thumbnail("a")).isSameInstanceAs(seen.last())
    }

    /**
     * 没挂在窗口上的标签不拍。
     *
     * 这一条是必须的：`PixelCopy` 抠的是窗口，不管那个位置上是哪个标签的 WebView。
     * 少了这个判断，后台标签加载完成时会把**当前显示着的**那一页存成它的缩略图。
     */
    @Test fun asyncCaptureSkipsDetachedTabs() {
        val capture = FakeCapture()
        // 有 Activity（拿得到 window），但没 setContentView——WebView 没挂上窗口。
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val pool = newCapturingPool(activity, capture, bounds = null)
        val web = pool.get("a", false)
        web.loadUrl("https://a.com")
        assertThat(web.isAttachedToWindow).isFalse()

        pool.captureThumbnailAsync("a", window(web)) {}
        exhaustCaptureAttempts()

        assertThat(capture.sources).isEmpty()
        assertThat(pool.thumbnail("a")).isNull()
    }

    /** 取景范围的两个前提：挂在窗口上、且量得出尺寸。 */
    @Test fun attachedBoundsNeedsADisplayedAndSizedView() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val web = WebView(activity)
        assertThat(WebViewPool.attachedBounds(web)).isNull()          // 没挂到窗口上
        activity.setContentView(web)
        assertThat(WebViewPool.attachedBounds(web)).isNull()          // 挂了，但 Robolectric 量不出尺寸
    }

    /** 空白标签页没有内容可拍。 */
    @Test fun asyncCaptureSkipsBlankTabs() {
        val capture = FakeCapture()
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val pool = newCapturingPool(activity, capture)
        val web = pool.get("a", false)
        activity.setContentView(web)

        pool.captureThumbnailAsync("a", window(web)) {}
        exhaustCaptureAttempts()

        assertThat(capture.sources).isEmpty()
    }

    /**
     * 拍图期间标签被关掉：整条延时链作废，不再去拍。
     *
     * `attemptCapture` 是延时链，`dispose` 之后回调照样会跑；不设防的话它会接着往一个
     * 已经关掉的标签上写缩略图。
     */
    @Test fun closingATabCancelsThePendingCaptureChain() {
        val capture = FakeCapture()
        val (pool, web) = attachedTab(capture)
        var captured: Bitmap? = null

        pool.captureThumbnailAsync("a", window(web)) { captured = it }
        pool.dispose("a")
        exhaustCaptureAttempts()

        assertThat(capture.sources).isEmpty()
        assertThat(captured).isNull()
        assertThat(pool.thumbnail("a")).isNull()
    }

    /**
     * `PixelCopy` 的回调可能晚于 `dispose`：结果回来时也要认出来并丢掉。
     *
     * 抠图是异步的（真机由窗口合成器回填），关标签与回调之间必然有窗口期——那一拍回来
     * 时会无条件 `thumbnails.put`，给一个已经没了的标签重新塞回位图。
     */
    @Test fun aShotArrivingAfterCloseIsDropped() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var deliver: ((Boolean) -> Unit)? = null
        val pool = WebViewPool(
            activity,
            { ctx -> WebView(ctx) },
            { false },
            WindowCapture { _, _, dest, onResult ->
                Canvas(dest).drawRect(
                    0f, 0f, dest.width.toFloat(), dest.height / 2f,
                    android.graphics.Paint().apply { color = android.graphics.Color.RED },
                )
                deliver = onResult // 先不回填，模拟「结果还在路上」
            },
            fakeBounds,
        )
        val web = pool.get("a", false)
        activity.setContentView(web)
        web.loadUrl("https://a.com")

        pool.captureThumbnailAsync("a", window(web)) {}
        advance(CaptureGap(0))
        pool.dispose("a")
        deliver!!.invoke(true)

        assertThat(pool.thumbnail("a")).isNull()
    }
}

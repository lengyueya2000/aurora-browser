package dev.aurora.browser.ui.browser

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.view.View
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
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

    private class FakeRequest(private val raw: String, private val mainFrame: Boolean = true) :
        WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(raw)
        override fun isForMainFrame(): Boolean = mainFrame
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
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

    /** 页面里的 tel:/mailto:/intent: 链接必须交给系统，否则点了没反应。 */
    @Test fun externalSchemesLeaveTheWebView() {
        val f = CountingFactory()
        val pool = newPool(f)
        val web = pool.get("a", false)
        var external: String? = null
        pool.onExternalUrl = { _, url -> external = url }
        val client = shadowOf(web).webViewClient

        assertThat(client.shouldOverrideUrlLoading(web, FakeRequest("tel:10086"))).isTrue()
        assertThat(external).isEqualTo("tel:10086")

        external = null
        assertThat(client.shouldOverrideUrlLoading(web, FakeRequest("weixin://dl/business"))).isTrue()
        assertThat(external).isEqualTo("weixin://dl/business")
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

        pool.blockTrackers = { false }
        assertThat(client.shouldInterceptRequest(web, FakeRequest("https://www.google-analytics.com/collect")))
            .isNull()

        pool.blockTrackers = { true }
        assertThat(client.shouldInterceptRequest(web, FakeRequest("https://www.google-analytics.com/collect")))
            .isNotNull()
        assertThat(client.shouldInterceptRequest(web, FakeRequest("https://a.com/app.js"))).isNull()
        assertThat(blocked).isEqualTo(1)
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
     * 页面上的按钮点下去要能到宿主，而且是**在主线程**——桥的方法跑在 WebView 的 JS
     * 线程上，直接回调会让宿主在非主线程碰 Compose 状态和系统服务。
     */
    @Test fun mediaBridgeHopsBackToTheMainThread() {
        val pool = newPool(CountingFactory())
        val web = pool.get("a", false)
        var seen: Triple<String, String, String>? = null
        pool.onMediaAction = { action, url, type -> seen = Triple(action, url, type) }
        val bridge = shadowOf(web).getJavascriptInterface(MediaOverlay.BRIDGE_NAME) as MediaBridge
        bridge.onMediaAction("download", "https://a.com/v.mp4", "video/mp4")
        assertThat(seen).isNull() // 还没轮到主线程
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(seen).isEqualTo(Triple("download", "https://a.com/v.mp4", "video/mp4"))
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
}

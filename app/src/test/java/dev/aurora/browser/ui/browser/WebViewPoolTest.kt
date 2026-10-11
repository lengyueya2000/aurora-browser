package dev.aurora.browser.ui.browser

import android.content.Context
import android.net.Uri
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
}

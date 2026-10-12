package dev.aurora.browser.media

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 切集用的隐藏嗅探窗口。
 *
 * 这个类最容易错的地方全是**时序**：早交付会拿到站点正式流之前的预览片，迟到交付会把用户
 * 带回上一集，不交付就是转圈转到超时。所以用例几乎都在推时钟。
 *
 * 它是「地址由站点 JS 现算」唯一能覆盖的做法（见 [EpisodeSniffer] 的类注释），而这件事在
 * 单测里没法真的跑一遍页面，所以这里用假请求喂 `shouldInterceptRequest`，验的是
 * 「什么时候交付、什么时候作废、什么时候销毁」这套骨架。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeSnifferTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val host = FrameLayout(context)

    private val page = "https://a.com/player/vod/1-82861-2.html"
    private val master = "https://cdn.a.com/hls/master.m3u8"

    @Before fun clearSnifferState() {
        // MediaSniffer 是进程级的单例，上一个用例留下的地址会让下一个用例假通过。
        MediaSniffer.clear(EpisodeSniffer.TabId)
    }

    private class FakeRequest(private val raw: String) : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(raw)
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }

    /** 嗅探窗口是挂进宿主容器的，从容器里取回来（它是 1×1、alpha=0，但确实在树上）。 */
    private fun window(): WebView = host.getChildAt(0) as WebView

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** 把时钟往前推：查结果的每一拍与总超时都是 `postDelayed` 排的。 */
    private fun advance(ms: Long) =
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /** 假装这个窗口去请求了一个地址。 */
    private fun request(web: WebView, url: String) {
        web.webViewClient.shouldInterceptRequest(web, FakeRequest(url))
        idleMain()
    }

    private fun finishLoading(web: WebView) {
        web.webViewClient.onPageFinished(web, page)
    }

    // ── 交付 ───────────────────────────────────────────────────

    @Test fun thePageIsLoadedAndTheWindowIsHiddenButAddedToTheHost() {
        val sniffer = EpisodeSniffer(context)
        sniffer.sniff(page, host, null, {}, {})
        val web = window()
        assertThat(shadowOf(web).lastLoadedUrl).isEqualTo(page)
        // 必须挂进视图树：不进树的 WebView 在部分 ROM 上定时器会被压制，JS 不跑就嗅不到
        // 地址。单测里验的是「确实进了宿主」（真机上的挂窗口由宿主 Activity 负责）。
        assertThat(web.parent).isSameInstanceAs(host)
        // alpha=0 而不是 GONE/INVISIBLE：可见性标志保持 VISIBLE，定时器才不被压制。
        assertThat(web.alpha).isEqualTo(0f)
    }

    @Test fun theStreamIsDeliveredOnceThePageFinishes() {
        val sniffer = EpisodeSniffer(context)
        var ready: String? = null
        var failed: String? = null
        sniffer.sniff(page, host, null, { ready = it }, { failed = it })
        val web = window()

        request(web, master)
        // 页面还没加载完就先拿到地址时**不能交付**：站点常在正式流之前先拉一个预览片。
        assertThat(ready).isNull()

        finishLoading(web)
        assertThat(ready).isEqualTo(master)
        assertThat(failed).isNull()
    }

    @Test fun aRequestThatIsNotMediaIsIgnored() {
        val sniffer = EpisodeSniffer(context)
        var ready: String? = null
        sniffer.sniff(page, host, null, { ready = it }, {})
        val web = window()
        request(web, "https://a.com/static/cover.jpg")
        finishLoading(web)
        assertThat(ready).isNull()
        // 紧接着来了真流，仍然要交付。
        request(web, master)
        assertThat(ready).isEqualTo(master)
    }

    /** 有些页面 `onPageFinished` 迟迟不来，所以时间表上最后一拍是兜底的。 */
    @Test fun theLastScheduledCheckDeliversEvenWithoutPageFinished() {
        val sniffer = EpisodeSniffer(context)
        var ready: String? = null
        sniffer.sniff(page, host, null, { ready = it }, {})
        val web = window()
        request(web, master)

        // 倒数第二拍之前都不交付。
        advance(EpisodeSniffer.ChecksMs[EpisodeSniffer.ChecksMs.lastIndex - 1] + 50)
        assertThat(ready).isNull()

        advance(EpisodeSniffer.ChecksMs.last() + 50)
        assertThat(ready).isEqualTo(master)
    }

    // ── 失败与作废 ─────────────────────────────────────────────

    @Test fun itFailsWhenNothingWasSniffedInTime() {
        val sniffer = EpisodeSniffer(context)
        var ready: String? = null
        var failed: String? = null
        sniffer.sniff(page, host, null, { ready = it }, { failed = it })
        finishLoading(window())

        advance(EpisodeSniffer.TimeoutMs + 100)
        assertThat(ready).isNull()
        assertThat(failed).isNotNull()
    }

    /** 用户手快连点两集：第一次的结果必须丢掉，否则会把他带回上一集。 */
    @Test fun aSupersededSniffNeverDelivers() {
        val sniffer = EpisodeSniffer(context)
        var first: String? = null
        sniffer.sniff(page, host, null, { first = it }, {})
        val firstWindow = window()
        request(firstWindow, "https://cdn.a.com/hls/ep2.m3u8")

        var second: String? = null
        sniffer.sniff("https://a.com/player/vod/1-82861-3.html", host, null, { second = it }, {})
        val secondWindow = window()
        request(secondWindow, "https://cdn.a.com/hls/ep3.m3u8")
        finishLoading(secondWindow)

        assertThat(second).isEqualTo("https://cdn.a.com/hls/ep3.m3u8")
        assertThat(first).isNull()

        // 第一次那些排好的拍子与超时还在飞，跑完之后也不能冒出来。
        advance(EpisodeSniffer.TimeoutMs + 100)
        assertThat(first).isNull()
    }

    @Test fun cancelStopsDeliveryAndDestroysTheWindow() {
        val sniffer = EpisodeSniffer(context)
        var ready: String? = null
        var failed: String? = null
        sniffer.sniff(page, host, null, { ready = it }, { failed = it })
        val web = window()
        request(web, master)

        sniffer.cancel()
        // 一个 WebView 二三十 MB，用完必须立刻还回去。
        assertThat(shadowOf(web).wasDestroyCalled()).isTrue()
        assertThat(host.childCount).isEqualTo(0)

        advance(EpisodeSniffer.TimeoutMs + 100)
        assertThat(ready).isNull()
        assertThat(failed).isNull()
    }

    @Test fun destroyIsCancel() {
        val sniffer = EpisodeSniffer(context)
        sniffer.sniff(page, host, null, {}, {})
        val web = window()
        sniffer.destroy()
        assertThat(shadowOf(web).wasDestroyCalled()).isTrue()
        assertThat(host.childCount).isEqualTo(0)
    }

    /** 交付之后窗口就该没了——它在 `succeed` 里被销毁，不需要调用方再收一次尾。 */
    @Test fun theWindowIsReleasedAfterDelivery() {
        val sniffer = EpisodeSniffer(context)
        var ready: String? = null
        sniffer.sniff(page, host, null, { ready = it }, {})
        val web = window()
        request(web, master)
        finishLoading(web)

        assertThat(ready).isEqualTo(master)
        assertThat(shadowOf(web).wasDestroyCalled()).isTrue()
        assertThat(host.childCount).isEqualTo(0)
    }

    // ── UA ─────────────────────────────────────────────────────

    /** 桌面版站点给的是另一套 DOM，UA 不一致会嗅到对不上的地址。 */
    @Test fun theUserAgentIsAppliedWhenGiven() {
        val sniffer = EpisodeSniffer(context)
        sniffer.sniff(page, host, "AuroraDesktop/1", {}, {})
        assertThat(window().settings.userAgentString).isEqualTo("AuroraDesktop/1")
    }

    /** 手机版时不该动 UA：写死一个手机 UA 会随 WebView 版本一起过期（见 DesktopMode）。 */
    @Test fun theUserAgentIsLeftAloneWhenNotGiven() {
        val sniffer = EpisodeSniffer(context)
        sniffer.sniff(page, host, null, {}, {})
        assertThat(window().settings.userAgentString).isNotEmpty()
        assertThat(window().settings.userAgentString).isNotEqualTo("AuroraDesktop/1")
    }

    // ── 没有宿主 ───────────────────────────────────────────────

    /** 宿主容器传 null 时不能崩：照样加载、照样嗅探。 */
    @Test fun aNullHostStillSniffs() {
        val sniffer = EpisodeSniffer(context)
        var ready: String? = null
        sniffer.sniff(page, null, null, { ready = it }, {})
        assertThat(host.childCount).isEqualTo(0)
        assertThat(ready).isNull()
        advance(EpisodeSniffer.TimeoutMs + 100)
        // 没有窗口可拿，也就没有请求可记——走到超时是唯一合理的结果。
        assertThat(ready).isNull()
    }
}

package dev.aurora.browser.ui.browser

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebViewContainerTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeController : BrowserController {
        var loaded: String? = null
        var reloads = 0
        override fun loadUrl(url: String) { loaded = url }
        override fun reload() { reloads++ }
        override fun stopLoading() {}
        override fun goBack() {}
        override fun goForward() {}
        override fun canGoBack() = false
        override fun canGoForward() = false
    }

    /**
     * 捕获池实际创建的 WebView。Robolectric 4.13 没有 `getLastCreatedWebView()`，
     * 因此走 `WebViewPool` 的 `factory` 注入（该参数本就是为测试而留）。
     */
    private class WebViewFactory {
        var created: WebView? = null
        val factory: (Context) -> WebView = { ctx -> WebView(ctx).also { created = it } }
    }

    /**
     * 计数 WebView：记录 `loadUrl` 调用次数，并单独跟踪「当前地址」。
     * 真实 WebView 的 `getUrl()` 反映的是当前页面——重定向后它与最后一次 `loadUrl` 的请求地址
     * 不同。这里用 `currentUrl` 复刻这一语义：`navigateWithoutLoad` 表示 WebView 自身跳转
     * （重定向 / 站内链接），不经过 `loadUrl`。
     * （Robolectric 的 `ShadowWebView.getUrl()` 直接返回最后一次 `loadUrl` 的地址，无法表达这种
     *  自跳转，因此由子类接管 `getUrl()`。）
     */
    private class CountingWebView(ctx: Context) : WebView(ctx) {
        var loadCount = 0
        private var currentUrl: String? = null
        override fun loadUrl(url: String) { loadCount++; currentUrl = url; super.loadUrl(url) }
        override fun getUrl(): String? = currentUrl
        /** 模拟 WebView 自身导航到某地址：不经过 `loadUrl`，只改变当前地址。 */
        fun navigateWithoutLoad(url: String) { currentUrl = url }
    }

    @Test fun loadsRequestedUrlOnFirstComposition() {
        val f = WebViewFactory()
        val pool = WebViewPool(context, f.factory)
        compose.setContent {
            AuroraTheme {
                WebViewContainer(
                    controller = FakeController(), pool = pool, tabId = "t1", url = "https://a.com",
                )
            }
        }
        compose.onRoot().assertExists()
        val web = f.created
        assertThat(web).isNotNull()
        assertThat(shadowOf(web!!).lastLoadedUrl).isEqualTo("https://a.com")
    }

    @Test fun blankUrlDoesNotLoadAnything() {
        val f = WebViewFactory()
        val pool = WebViewPool(context, f.factory)
        compose.setContent {
            AuroraTheme {
                WebViewContainer(controller = FakeController(), pool = pool, tabId = "t1", url = null)
            }
        }
        compose.onRoot().assertExists()
        val web = f.created
        assertThat(web).isNotNull()
        assertThat(shadowOf(web!!).lastLoadedUrl).isNull()
    }

    /**
     * FIX 2 的回归测试：`onPageFinished` 会把真实地址回写成 `url` 参数，若加载 effect 不加
     * 「WebView 是否已在该地址」的守卫，重定向/站内跳转会重复 `loadUrl`（多一次加载 + 多一条历史）。
     */
    @Test fun doesNotReloadWhenUrlIsUnchanged() {
        var created: CountingWebView? = null
        val pool = WebViewPool(context, factory = { ctx -> CountingWebView(ctx).also { created = it } })
        val urlState = mutableStateOf("https://a.com")
        // 无关状态：读取它可在 url 不变时强制重组，验证「纯重组不触发加载」。
        val tick = mutableStateOf(0)
        compose.setContent {
            AuroraTheme {
                tick.value
                WebViewContainer(
                    controller = FakeController(), pool = pool, tabId = "t1", url = urlState.value,
                )
            }
        }
        compose.waitForIdle()
        val web = created!!
        // 首次组合只加载一次
        assertThat(web.loadCount).isEqualTo(1)
        assertThat(web.url).isEqualTo("https://a.com")

        // url 不变、仅重组：不应再次加载
        tick.value = 1
        compose.waitForIdle()
        assertThat(web.loadCount).isEqualTo(1)

        // 模拟重定向：WebView 自身已停在 final 地址（不经过 loadUrl），随后 onPageFinished
        // 把该地址回写为新的 url（等价 vm.onPageFinished）。effect 因 url 变化重跑，
        // 但 WebView 已在该地址上——守卫必须拦下。
        web.navigateWithoutLoad("https://a.com/final")
        urlState.value = "https://a.com/final"
        compose.waitForIdle()
        assertThat(web.loadCount).isEqualTo(1)

        // 切到真正不同的地址时仍应加载
        urlState.value = "https://b.com"
        compose.waitForIdle()
        assertThat(web.loadCount).isEqualTo(2)
        assertThat(web.url).isEqualTo("https://b.com")
    }

    /** 同一标签在多次重组之间必须复用池里的同一个实例。 */
    @Test fun reusesTheSameWebViewInstanceForTheSameTab() {
        val f = WebViewFactory()
        val pool = WebViewPool(context, f.factory)
        val tick = mutableStateOf(0)
        compose.setContent {
            AuroraTheme {
                tick.value
                WebViewContainer(controller = FakeController(), pool = pool, tabId = "t1", url = null)
            }
        }
        compose.waitForIdle()
        val first = f.created
        tick.value = 1
        compose.waitForIdle()
        assertThat(f.created).isSameInstanceAs(first)
        assertThat(pool.retainedTabIds()).containsExactly("t1")
    }

    /**
     * 回归：WebView 归 `WebViewPool` 所有。离开组合（例如打开地址栏覆盖层把 Page 分支换掉）
     * 只能解绑控制器，不能销毁实例——否则切回来要重新加载，滚动位置与前进/后退历史全丢。
     */
    @Test fun leavingCompositionKeepsTheWebViewAliveInThePool() {
        val f = WebViewFactory()
        val pool = WebViewPool(context, f.factory)
        val visible = mutableStateOf(true)
        compose.setContent {
            AuroraTheme {
                if (visible.value) {
                    WebViewContainer(
                        controller = FakeController(), pool = pool, tabId = "t1", url = "https://a.com",
                    )
                }
            }
        }
        compose.waitForIdle()
        val web = f.created!!
        visible.value = false
        compose.waitForIdle()
        assertThat(shadowOf(web).wasDestroyCalled()).isFalse()
        assertThat(pool.retainedTabIds()).containsExactly("t1")
        // 池仍可交出同一个实例
        assertThat(pool.get("t1", false)).isSameInstanceAs(web)
    }
}

package dev.aurora.browser.ui.browser

import android.content.Context
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
     * 捕获容器实际创建的 WebView。Robolectric 4.14.1 没有 `getLastCreatedWebView()`，
     * 因此走 `factory` 注入（该参数本就是为测试而留），再用 `shadowOf(web).lastLoadedUrl`
     * 观察它收到的 URL。
     */
    private class WebViewFactory {
        var created: WebView? = null
        val factory: (Context) -> WebView = { ctx -> WebView(ctx).also { created = it } }
    }

    @Test fun loadsRequestedUrlOnFirstComposition() {
        val controller = FakeController()
        val f = WebViewFactory()
        compose.setContent {
            AuroraTheme {
                WebViewContainer(controller = controller, url = "https://a.com", factory = f.factory)
            }
        }
        compose.onRoot().assertExists()
        // 断言 WebView 真的被创建并收到 URL
        val web = f.created
        assertThat(web).isNotNull()
        assertThat(shadowOf(web!!).lastLoadedUrl).isEqualTo("https://a.com")
    }

    @Test fun blankUrlDoesNotLoadAnything() {
        val controller = FakeController()
        val f = WebViewFactory()
        compose.setContent {
            AuroraTheme { WebViewContainer(controller = controller, url = null, factory = f.factory) }
        }
        compose.onRoot().assertExists()
        val web = f.created
        assertThat(web).isNotNull()
        assertThat(shadowOf(web!!).lastLoadedUrl).isNull()
    }

    // 重组后传入的新回调必须生效：客户端不能一直调用首次组合时捕获的那个 lambda。
    @Test fun callbacksFollowTheLatestLambdaAfterRecomposition() {
        var first = -1
        var second = -1
        val useSecond = mutableStateOf(false)
        val f = WebViewFactory()
        compose.setContent {
            AuroraTheme {
                val onProgress: (Int) -> Unit =
                    if (useSecond.value) { { second = it } } else { { first = it } }
                WebViewContainer(
                    controller = FakeController(), url = null,
                    onProgress = onProgress, factory = f.factory,
                )
            }
        }
        useSecond.value = true
        compose.waitForIdle()
        val web = f.created!!
        shadowOf(web).webChromeClient.onProgressChanged(web, 42)
        assertThat(second).isEqualTo(42)
        assertThat(first).isEqualTo(-1)
    }
}

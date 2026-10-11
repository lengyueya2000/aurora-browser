package dev.aurora.browser.ui.browser

import android.content.Context
import android.view.View
import android.webkit.WebChromeClient
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 网页视频全屏的状态机。
 *
 * 真机上「点全屏没反应」的根因是没人接管 `WebChromeClient.onShowCustomView`：WebView
 * 把要全屏的 View 交出来之后，挂载、隐藏系统栏、返回键处理全得应用自己做。这些逻辑
 * 没法在单测里断言真实窗口，所以抽成 [FullscreenHost] 用假宿主来测。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoFullscreenControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private class FakeHost : FullscreenHost {
        val attached = mutableListOf<View>()
        val detached = mutableListOf<View>()
        val immersive = mutableListOf<Boolean>()
        override fun attach(view: View) { attached += view }
        override fun detach(view: View) { detached += view }
        override fun setImmersive(on: Boolean) { immersive += on }
    }

    private class FakeCallback : WebChromeClient.CustomViewCallback {
        var hiddenCount = 0
        override fun onCustomViewHidden() { hiddenCount++ }
    }

    private fun newView() = View(context)

    @Test fun showAttachesTheViewAndEntersImmersiveMode() {
        val host = FakeHost()
        val controller = VideoFullscreenController(host)
        val view = newView()
        val callback = FakeCallback()

        controller.show(view, callback)

        assertThat(host.attached).containsExactly(view)
        assertThat(host.immersive).containsExactly(true)
        assertThat(controller.isFullscreen.value).isTrue()
        assertThat(callback.hiddenCount).isEqualTo(0)
    }

    @Test fun hideDetachesRestoresSystemBarsAndNotifiesTheWebView() {
        val host = FakeHost()
        val controller = VideoFullscreenController(host)
        val view = newView()
        val callback = FakeCallback()

        controller.show(view, callback)
        controller.hide()

        assertThat(host.detached).containsExactly(view)
        assertThat(host.immersive).containsExactly(true, false).inOrder()
        assertThat(controller.isFullscreen.value).isFalse()
        // 必须回调 WebView：漏掉它，下次全屏拿不到画面。
        assertThat(callback.hiddenCount).isEqualTo(1)
    }

    /**
     * 全屏中按返回必须是「退出全屏」，而且这次返回要被消费掉。
     * 不消费的话，用户在全屏里按返回会直接后退网页甚至退出应用。
     */
    @Test fun backPressExitsFullscreenAndIsConsumed() {
        val host = FakeHost()
        val controller = VideoFullscreenController(host)
        controller.show(newView(), FakeCallback())

        assertThat(controller.onBackPressed()).isTrue()
        assertThat(controller.isFullscreen.value).isFalse()
    }

    @Test fun backPressIsNotConsumedWhenNotFullscreen() {
        val controller = VideoFullscreenController(FakeHost())
        assertThat(controller.onBackPressed()).isFalse()
    }

    @Test fun hideWithoutFullscreenDoesNothing() {
        val host = FakeHost()
        val controller = VideoFullscreenController(host)
        controller.hide()
        assertThat(host.immersive).isEmpty()
        assertThat(host.detached).isEmpty()
    }

    /**
     * 站点切清晰度/切集时会再发一次 show：旧的 View 必须先从窗口上摘掉，
     * 否则它会永久留在窗口最上层，既漏内存又盖住新画面。
     */
    @Test fun showingAgainReclaimsThePreviousView() {
        val host = FakeHost()
        val controller = VideoFullscreenController(host)
        val first = newView()
        val firstCallback = FakeCallback()
        val second = newView()

        controller.show(first, firstCallback)
        controller.show(second, FakeCallback())

        assertThat(host.detached).containsExactly(first)
        assertThat(host.attached).containsExactly(first, second).inOrder()
        assertThat(firstCallback.hiddenCount).isEqualTo(1)
        assertThat(controller.isFullscreen.value).isTrue()
    }

    @Test fun hideAfterShowIsIdempotent() {
        val host = FakeHost()
        val controller = VideoFullscreenController(host)
        val callback = FakeCallback()
        controller.show(newView(), callback)

        controller.hide()
        controller.hide()

        assertThat(callback.hiddenCount).isEqualTo(1)
        assertThat(host.immersive).containsExactly(true, false).inOrder()
    }
}

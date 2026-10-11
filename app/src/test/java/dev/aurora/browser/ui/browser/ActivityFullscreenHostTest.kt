package dev.aurora.browser.ui.browser

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 真实全屏宿主（挂 View + 切方向 + 沉浸式）的行为。
 *
 * 状态机那层已经用假宿主测过了，这里补的是它真正碰窗口的那部分：横屏视频在竖屏里
 * 全屏只能占中间一条，所以进全屏要把界面掰到横屏、退出再原样还回去——还错方向会
 * 让用户退出全屏后整个浏览器横着，比不全屏更难受。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActivityFullscreenHostTest {

    private fun newActivity(): Activity =
        Robolectric.buildActivity(Activity::class.java).setup().get()

    @Test fun attachPutsTheViewOnTheContentRoot() {
        val activity = newActivity()
        val host = ActivityFullscreenHost(activity)
        val view = View(activity)

        host.attach(view)

        assertThat(view.parent).isNotNull()
        assertThat(view.parent).isInstanceOf(ViewGroup::class.java)
    }

    @Test fun fullscreenSwitchesToLandscapeAndExitRestoresTheOldOrientation() {
        val activity = newActivity()
        val host = ActivityFullscreenHost(activity)
        val view = View(activity)
        val before = activity.requestedOrientation

        host.attach(view)
        assertThat(activity.requestedOrientation)
            .isEqualTo(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

        host.detach(view)
        assertThat(activity.requestedOrientation).isEqualTo(before)
        assertThat(view.parent).isNull()
    }

    /** 站点切清晰度时会再来一次 show：方向只能被记一次，不能把横屏当成「原来的方向」存下来。 */
    @Test fun attachingTwiceStillRestoresTheOriginalOrientation() {
        val activity = newActivity()
        val host = ActivityFullscreenHost(activity)
        val first = View(activity)
        val second = View(activity)
        val before = activity.requestedOrientation

        host.attach(first)
        host.detach(first)
        host.attach(second)
        host.detach(second)

        assertThat(activity.requestedOrientation).isEqualTo(before)
    }

    @Test fun detachWithoutAttachIsHarmless() {
        val activity = newActivity()
        val host = ActivityFullscreenHost(activity)
        val before = activity.requestedOrientation

        host.detach(View(activity))

        assertThat(activity.requestedOrientation).isEqualTo(before)
    }
}

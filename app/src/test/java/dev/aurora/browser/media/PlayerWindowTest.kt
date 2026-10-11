package dev.aurora.browser.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 小窗比例。Android 只接受 1/2.39–2.39，越界会抛异常——真机上表现就是「点了小窗没反应」，
 * 所以夹紧这一步必须在这里定死。
 */
class PlayerWindowTest {

    @Test fun normalVideoKeepsItsOwnRatio() {
        assertThat(PlayerWindow.pipAspectRatio(1920, 1080)).isEqualTo(16 to 9)
        assertThat(PlayerWindow.pipAspectRatio(1280, 720)).isEqualTo(16 to 9)
        // 竖屏短剧：9:16 仍在允许区间内，原样保留。
        assertThat(PlayerWindow.pipAspectRatio(1080, 1920)).isEqualTo(9 to 16)
    }

    @Test fun ultraWideIsClampedToTheMaximum() {
        // 21:9 的宽银幕（2.33）还合法，32:9（3.56）就越界了。
        assertThat(PlayerWindow.pipAspectRatio(2560, 1080)).isEqualTo(64 to 27)
        assertThat(PlayerWindow.pipAspectRatio(3840, 1080)).isEqualTo(239 to 100)
    }

    @Test fun veryTallVideoIsClampedToTheMinimum() {
        // 9:21 的竖屏已经接近下限，1:3 就越界了。
        val clamped = PlayerWindow.pipAspectRatio(1080, 3240)
        assertThat(clamped).isEqualTo(100 to 239)
        val ratio = clamped.first.toFloat() / clamped.second
        assertThat(ratio).isAtLeast(PlayerWindow.MIN_RATIO)
    }

    /** 还没收到 onVideoSizeChanged 时按 16:9 给，别让系统摆一个方窗口。 */
    @Test fun unknownSizeFallsBackToWidescreen() {
        assertThat(PlayerWindow.pipAspectRatio(0, 0)).isEqualTo(16 to 9)
        assertThat(PlayerWindow.pipAspectRatio(-100, 200)).isEqualTo(16 to 9)
    }

    /** 非标准但合法的尺寸要化简，别把 1918x1080 这种原样丢给系统。 */
    @Test fun ratioIsReduced() {
        assertThat(PlayerWindow.pipAspectRatio(3840, 2160)).isEqualTo(16 to 9)
        assertThat(PlayerWindow.pipAspectRatio(1440, 1080)).isEqualTo(4 to 3)
    }
}

package dev.aurora.browser.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 桌面版 UA 的选择。纯函数，不用 Robolectric。
 *
 * 关键在**关掉**时换回什么：必须是调用方记下的「这台设备 WebView 的默认 UA」，
 * 而不是我们写死的某个手机 UA——写死的那个会随着 WebView 版本一起过期。
 */
class DesktopModeTest {

    @Test fun desktopModeUsesADesktopUserAgent() {
        val ua = DesktopMode.userAgentFor(enabled = true, mobileUserAgent = "Mozilla/5.0 (Linux; Android 14)")
        assertThat(ua).isEqualTo(DesktopMode.DESKTOP_USER_AGENT)
    }

    /** 站点认的是「桌面」这个信号：不能带 Android，也不能带 Mobile。 */
    @Test fun desktopUserAgentDoesNotLookMobile() {
        assertThat(DesktopMode.DESKTOP_USER_AGENT).contains("Windows NT")
        assertThat(DesktopMode.DESKTOP_USER_AGENT).doesNotContain("Android")
        assertThat(DesktopMode.DESKTOP_USER_AGENT).doesNotContain("Mobile")
    }

    @Test fun turningItOffGoesBackToTheDeviceDefault() {
        val device = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36"
        assertThat(DesktopMode.userAgentFor(enabled = false, mobileUserAgent = device)).isEqualTo(device)
    }

    /** 拿不到默认 UA 时宁可不动，也不要给一个错的。 */
    @Test fun withoutAKnownDefaultNothingIsChanged() {
        assertThat(DesktopMode.userAgentFor(enabled = false, mobileUserAgent = null)).isNull()
        assertThat(DesktopMode.userAgentFor(enabled = false, mobileUserAgent = "   ")).isNull()
    }

    /** 开着桌面版时不需要默认 UA，所以缺了也照样给桌面 UA。 */
    @Test fun desktopModeWorksWithoutAKnownDefault() {
        assertThat(DesktopMode.userAgentFor(enabled = true, mobileUserAgent = null))
            .isEqualTo(DesktopMode.DESKTOP_USER_AGENT)
    }
}

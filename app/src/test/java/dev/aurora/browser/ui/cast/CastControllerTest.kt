package dev.aurora.browser.ui.cast

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 投屏入口。
 *
 * 投屏能力因设备而异（有的机型没有系统投屏页、有的没有能播视频的接收端应用），
 * 所以每个系统调用都必须返回「成没成」而不是抛异常——把浏览器打崩是最糟的降级。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CastControllerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** 让某个 intent 在包管理器里「有应用能接」。 */
    private fun register(intent: Intent) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "com.example.cast"
                name = "com.example.cast.ReceiverActivity"
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, info)
    }

    private fun videoIntent(url: String, mime: String) =
        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), mime)

    @Test fun systemCastSettingsReportsFailureWhenNothingHandlesIt() {
        assertThat(CastController(app).openSystemCastSettings()).isFalse()
    }

    @Test fun systemCastSettingsOpensTheSystemCastPage() {
        register(Intent("android.settings.CAST_SETTINGS"))
        assertThat(CastController(app).openSystemCastSettings()).isTrue()
        assertThat(shadowOf(app).nextStartedActivity.action)
            .isEqualTo("android.settings.CAST_SETTINGS")
    }

    /** 部分 OEM 没有 CAST_SETTINGS，沿用旧的 WIFI_DISPLAY_SETTINGS，得能兜住。 */
    @Test fun systemCastSettingsFallsBackToTheOemAction() {
        register(Intent("com.android.settings.WIFI_DISPLAY_SETTINGS"))
        assertThat(CastController(app).openSystemCastSettings()).isTrue()
        assertThat(shadowOf(app).nextStartedActivity.action)
            .isEqualTo("com.android.settings.WIFI_DISPLAY_SETTINGS")
    }

    /**
     * 投屏播放视频走的是系统选择器：把视频地址 + MIME 交给能播它的应用
     * （各类投屏播放器都注册了 video 通配 MIME）。
     */
    @Test fun playOnDeviceHandsTheVideoToTheSystemChooser() {
        register(videoIntent("https://a.com/v.mp4", "video/mp4"))

        assertThat(CastController(app).playOnDevice("https://a.com/v.mp4", "video/mp4")).isTrue()

        val chooser = shadowOf(app).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertThat(target?.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(target?.data.toString()).isEqualTo("https://a.com/v.mp4")
        assertThat(target?.type).isEqualTo("video/mp4")
    }

    /** 没有应用能接时必须安静地返回 false，而不是弹一个空的选择器。 */
    @Test fun playOnDeviceReportsFailureWhenNothingCanPlayIt() {
        assertThat(CastController(app).playOnDevice("https://a.com/v.mp4", "video/mp4")).isFalse()
    }

    /** 没给 MIME 时兜底成 video 通配类型，否则接收端应用按 MIME 注册匹配不到。 */
    @Test fun playOnDeviceFallsBackToTheVideoWildcardMime() {
        register(videoIntent("https://a.com/stream", "video/*"))

        assertThat(CastController(app).playOnDevice("https://a.com/stream", null)).isTrue()

        val target = shadowOf(app).nextStartedActivity
            .getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertThat(target?.type).isEqualTo("video/*")
    }
}

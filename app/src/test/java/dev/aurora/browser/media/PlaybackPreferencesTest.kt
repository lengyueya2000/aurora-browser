package dev.aurora.browser.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 记忆播放进度与倍速。核心是「什么时候**不**记」——记错了比不记更烦：
 * 看到第 3 秒就退出，下次「接着播」等于从头播；看完最后 10 秒退出，下次直接跳到结尾。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PlaybackPreferences.create(context)

    private val url = "https://c.com/player/vod/1-82861-1.html"

    @Test fun unknownVideoStartsFromZero() {
        assertThat(prefs.resumeMs(url)).isEqualTo(0L)
    }

    @Test fun positionIsRememberedAndReadBack() {
        prefs.remember(url, positionMs = 754_000L, durationMs = 3_600_000L)
        assertThat(prefs.resumeMs(url)).isEqualTo(754_000L)
    }

    /** 不同视频各记各的，键按 URL 区分。 */
    @Test fun positionsAreKeptPerVideo() {
        prefs.remember(url, 100_000L, 3_600_000L)
        prefs.remember("https://c.com/other", 200_000L, 3_600_000L)
        assertThat(prefs.resumeMs(url)).isEqualTo(100_000L)
        assertThat(prefs.resumeMs("https://c.com/other")).isEqualTo(200_000L)
    }

    @Test fun tooShallowPositionsAreNotRemembered() {
        prefs.remember(url, positionMs = 2_000L, durationMs = 3_600_000L)
        assertThat(prefs.resumeMs(url)).isEqualTo(0L)
    }

    /** 已经看到结尾的视频下次从头播，而不是跳到最后 10 秒。 */
    @Test fun finishedVideosAreNotResumed() {
        prefs.remember(url, positionMs = 3_590_000L, durationMs = 3_600_000L)
        assertThat(prefs.resumeMs(url)).isEqualTo(0L)
    }

    /** 时长还没读出来时不能按「看完了」处理，否则长视频看到一半就丢了进度。 */
    @Test fun unknownDurationStillRemembers() {
        prefs.remember(url, positionMs = 600_000L, durationMs = 0L)
        assertThat(prefs.resumeMs(url)).isEqualTo(600_000L)
    }

    @Test fun forgetClearsThePosition() {
        prefs.remember(url, 600_000L, 3_600_000L)
        prefs.forget(url)
        assertThat(prefs.resumeMs(url)).isEqualTo(0L)
    }

    @Test fun speedDefaultsToOneAndRoundTrips() {
        assertThat(prefs.speed()).isEqualTo(1f)
        prefs.rememberSpeed(1.5f)
        assertThat(prefs.speed()).isEqualTo(1.5f)
    }

    /** 只接受界面上给出的档位，别的值写进去等于给下次启动埋一个怪倍速。 */
    @Test fun unsupportedSpeedIsIgnored() {
        prefs.rememberSpeed(1.5f)
        prefs.rememberSpeed(1.7f)
        assertThat(prefs.speed()).isEqualTo(1.5f)
    }

    /** 偏好文件被写坏（或来自更老的版本）时回落到 1x，而不是把怪值喂给播放器。 */
    @Test fun corruptedSpeedFallsBackToOne() {
        context.getSharedPreferences("aurora_playback", Context.MODE_PRIVATE)
            .edit()
            .putFloat("speed", 12f)
            .commit()
        assertThat(PlaybackPreferences.create(context).speed()).isEqualTo(1f)
    }
}

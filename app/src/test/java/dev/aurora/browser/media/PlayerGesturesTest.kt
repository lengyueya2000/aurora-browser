package dev.aurora.browser.media

import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.media.PlayerGestures.Axis
import dev.aurora.browser.media.PlayerGestures.Side
import org.junit.Test

/**
 * 手势与格式的纯计算。这里没有 Android 环境，全是加减乘除——
 * 正因为如此，真机上「上滑是变亮还是变暗」这类问题才应该在这一层定死。
 */
class PlayerGesturesTest {

    // ---------- 左右半屏 ----------

    @Test fun leftHalfIsLeftAndRightHalfIsRight() {
        assertThat(PlayerGestures.sideOf(0f, 1080)).isEqualTo(Side.LEFT)
        assertThat(PlayerGestures.sideOf(539f, 1080)).isEqualTo(Side.LEFT)
        assertThat(PlayerGestures.sideOf(540f, 1080)).isEqualTo(Side.RIGHT)
        assertThat(PlayerGestures.sideOf(1079f, 1080)).isEqualTo(Side.RIGHT)
    }

    /** 宽度未知时按左半屏处理，至少亮度手势仍然可用。 */
    @Test fun unknownWidthFallsBackToLeft() {
        assertThat(PlayerGestures.sideOf(100f, 0)).isEqualTo(Side.LEFT)
    }

    // ---------- 主轴判定 ----------

    @Test fun tinyMovementDoesNotDecideAnAxis() {
        assertThat(PlayerGestures.axisOf(3f, 4f)).isEqualTo(Axis.UNDECIDED)
        assertThat(PlayerGestures.axisOf(-11f, 5f)).isEqualTo(Axis.UNDECIDED)
    }

    @Test fun dominantDirectionWins() {
        assertThat(PlayerGestures.axisOf(60f, 10f)).isEqualTo(Axis.HORIZONTAL)
        assertThat(PlayerGestures.axisOf(-60f, 10f)).isEqualTo(Axis.HORIZONTAL)
        assertThat(PlayerGestures.axisOf(10f, 60f)).isEqualTo(Axis.VERTICAL)
        assertThat(PlayerGestures.axisOf(10f, -60f)).isEqualTo(Axis.VERTICAL)
        // 斜着滑动时横向优先：看进度比调音量更常被用到。
        assertThat(PlayerGestures.axisOf(40f, 40f)).isEqualTo(Axis.HORIZONTAL)
    }

    // ---------- 拖动进度 ----------

    @Test fun draggingHalfTheWidthMovesOneMinute() {
        val preview = PlayerGestures.seekPreview(
            startMs = 300_000L,
            dx = 540f,
            width = 1080,
            durationMs = 3_600_000L,
        )
        assertThat(preview.targetMs).isEqualTo(360_000L)
        assertThat(preview.deltaMs).isEqualTo(60_000L)
    }

    @Test fun seekIsClampedToTheVideoBounds() {
        val beforeStart = PlayerGestures.seekPreview(10_000L, -1080f, 1080, 600_000L)
        assertThat(beforeStart.targetMs).isEqualTo(0L)
        // 夹紧后显示的差值也要跟着修正，否则会出现「显示 +30 秒但画面没动」。
        assertThat(beforeStart.deltaMs).isEqualTo(-10_000L)

        val pastEnd = PlayerGestures.seekPreview(590_000L, 1080f, 1080, 600_000L)
        assertThat(pastEnd.targetMs).isEqualTo(600_000L)
        assertThat(pastEnd.deltaMs).isEqualTo(10_000L)
    }

    /** 时长还没读出来（直播或尚未 ready）时不该把进度夹到 0。 */
    @Test fun unknownDurationDoesNotClamp() {
        val preview = PlayerGestures.seekPreview(5_000L, 1080f, 1080, 0L)
        assertThat(preview.targetMs).isEqualTo(125_000L)
    }

    // ---------- 亮度与音量 ----------

    @Test fun slidingUpBrightensAndSlidingDownDims() {
        val brighter = PlayerGestures.brightness(0.5f, dy = -200f, height = 1000)
        assertThat(brighter).isWithin(0.001f).of(0.7f)

        val dimmer = PlayerGestures.brightness(0.5f, dy = 200f, height = 1000)
        assertThat(dimmer).isWithin(0.001f).of(0.3f)
    }

    @Test fun brightnessIsClampedToZeroAndOne() {
        assertThat(PlayerGestures.brightness(0.9f, -900f, 1000)).isEqualTo(1f)
        assertThat(PlayerGestures.brightness(0.1f, 900f, 1000)).isEqualTo(0f)
        // 起点本身就超范围（比如从系统亮度算出来的怪值）也要先夹紧。
        assertThat(PlayerGestures.brightness(1.4f, 0f, 1000)).isEqualTo(1f)
    }

    @Test fun slidingUpRaisesVolume() {
        // 上滑 500px 是整段高度的一半，音量应该走一半的档位（15 档 → +8）。
        assertThat(PlayerGestures.volume(5, dy = -500f, height = 1000, maxVolume = 15))
            .isEqualTo(13)
        assertThat(PlayerGestures.volume(5, dy = 500f, height = 1000, maxVolume = 15))
            .isEqualTo(0)
    }

    @Test fun volumeIsClampedAndSurvivesZeroMax() {
        assertThat(PlayerGestures.volume(0, -1000f, 1000, 15)).isEqualTo(15)
        assertThat(PlayerGestures.volume(15, 1000f, 1000, 15)).isEqualTo(0)
        // 拿不到音量上限（某些机型会返回 0）时不能除零，也不能报错。
        assertThat(PlayerGestures.volume(3, -500f, 1000, 0)).isEqualTo(0)
    }

    // ---------- 双击 ----------

    @Test fun doubleTapSeeksTenSecondsEitherWay() {
        val left = PlayerGestures.doubleTapSeekMs(60_000L, x = 100f, width = 1080, durationMs = 600_000L)
        assertThat(left).isEqualTo(50_000L)

        val right = PlayerGestures.doubleTapSeekMs(60_000L, x = 900f, width = 1080, durationMs = 600_000L)
        assertThat(right).isEqualTo(70_000L)
    }

    @Test fun doubleTapIsClampedAtBothEnds() {
        assertThat(PlayerGestures.doubleTapSeekMs(3_000L, 100f, 1080, 600_000L)).isEqualTo(0L)
        assertThat(PlayerGestures.doubleTapSeekMs(598_000L, 900f, 1080, 600_000L)).isEqualTo(600_000L)
    }

    // ---------- 按一下跳几秒（±10 秒按钮） ----------

    @Test fun seekByMovesRelativeToTheCurrentPosition() {
        assertThat(PlayerGestures.seekTarget(60_000L, 10_000L, 600_000L)).isEqualTo(70_000L)
        assertThat(PlayerGestures.seekTarget(60_000L, -10_000L, 600_000L)).isEqualTo(50_000L)
        // 两端夹紧
        assertThat(PlayerGestures.seekTarget(3_000L, -10_000L, 600_000L)).isEqualTo(0L)
        assertThat(PlayerGestures.seekTarget(598_000L, 10_000L, 600_000L)).isEqualTo(600_000L)
    }

    /**
     * 时长未知（直播 / 还没 prepare 完）时上界不能当 0。
     *
     * 之前写成 `duration.coerceAtLeast(0)`，`coerceIn(0, 0)` 会把每一次快进/快退都变成
     * 「跳到第 0 秒」——用户按快退，画面直接回到片头。
     */
    @Test fun seekByDoesNotCollapseToZeroWhenTheDurationIsUnknown() {
        assertThat(PlayerGestures.seekTarget(60_000L, 10_000L, 0L)).isEqualTo(70_000L)
        assertThat(PlayerGestures.seekTarget(60_000L, -10_000L, 0L)).isEqualTo(50_000L)
        // 直播里往回退也不能退到负数
        assertThat(PlayerGestures.seekTarget(5_000L, -10_000L, 0L)).isEqualTo(0L)
    }

    // ---------- 文本 ----------

    @Test fun timeIsFormattedWithHoursOnlyWhenNeeded() {
        assertThat(PlayerGestures.formatTime(0L)).isEqualTo("0:00")
        assertThat(PlayerGestures.formatTime(9_000L)).isEqualTo("0:09")
        assertThat(PlayerGestures.formatTime(754_000L)).isEqualTo("12:34")
        assertThat(PlayerGestures.formatTime(3_725_000L)).isEqualTo("1:02:05")
        assertThat(PlayerGestures.formatTime(-5_000L)).isEqualTo("0:00")
        // 59.6 秒要进位成 1:00，而不是显示 0:59 却已经播到下一分钟。
        assertThat(PlayerGestures.formatTime(59_600L)).isEqualTo("1:00")
    }

    @Test fun deltaIsSigned() {
        assertThat(PlayerGestures.deltaLabel(80_000L)).isEqualTo("+01:20")
        assertThat(PlayerGestures.deltaLabel(-35_000L)).isEqualTo("-00:35")
        assertThat(PlayerGestures.deltaLabel(0L)).isEqualTo("+00:00")
    }

    @Test fun speedLabelsKeepUsefulPrecision() {
        assertThat(PlayerGestures.speedLabel(0.5f)).isEqualTo("0.5x")
        assertThat(PlayerGestures.speedLabel(1f)).isEqualTo("1.0x")
        assertThat(PlayerGestures.speedLabel(1.25f)).isEqualTo("1.25x")
        assertThat(PlayerGestures.speedLabel(2f)).isEqualTo("2.0x")
        assertThat(PlayerGestures.SPEEDS).contains(1f)
    }
}

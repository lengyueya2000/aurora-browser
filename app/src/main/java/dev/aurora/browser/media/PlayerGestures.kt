package dev.aurora.browser.media

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 播放器手势与显示格式的纯计算部分。
 *
 * 指针事件在 `PlayerActivity` 里读，但「往哪个方向滑代表调什么」这类判断留在这里：
 * 全是加减乘除，不需要 Android 环境，也就没有理由只能靠手点真机来验证。
 *
 * 方向约定与国内视频 App 一致：**上滑变大**（亮度/音量），横向拖动看进度，
 * 双击左右半屏快退/快进 10 秒。
 */
internal object PlayerGestures {

    /** 左半屏调亮度、右半屏调音量。 */
    const val SPLIT_RATIO = 0.5f

    /** 横向拖满整屏宽对应的时间跨度。再长手指就不够用了。 */
    const val SEEK_FULL_WIDTH_MS = 120_000L

    /** 双击左/右半屏的步长。 */
    const val DOUBLE_TAP_SEEK_MS = 10_000L

    /** 判定主轴前至少移动这么多像素，免得手一抖就把点击变成拖动。 */
    private const val AXIS_SLOP = 12f

    /** 可选的播放倍速。 */
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

    /** 系统亮度未设置时的回落值（`screenBrightness` 为 -1 表示「跟随系统」）。 */
    const val DEFAULT_BRIGHTNESS = 0.5f

    enum class Side { LEFT, RIGHT }

    /** 一次拖动是横向（看进度）还是纵向（调亮度/音量），动得不够时还判不出来。 */
    enum class Axis { UNDECIDED, HORIZONTAL, VERTICAL }

    fun sideOf(x: Float, width: Int): Side =
        if (width <= 0 || x < width * SPLIT_RATIO) Side.LEFT else Side.RIGHT

    /**
     * 画面中间那颗按钮该画转圈还是画播放/暂停三角。
     *
     * 只看 `isPlaying` 分不出「在缓冲」和「用户暂停了」——两种情况下它都是 false。
     * 真机反馈：「全屏之后直接自动播放视频，如果缓冲慢，显示转圈，而不是显示播放按钮，
     * 这样会造成误解」——缓冲时画着播放三角，用户以为没自动播，又去点一下。
     *
     * 所以判据是「在缓冲」且「用户是想播的」（`playWhenReady`）。暂停状态下偶尔出现的
     * 缓冲仍然画三角：那时点一下正是「继续播放」，是用户期待的结果。
     */
    fun showsSpinner(buffering: Boolean, playWhenReady: Boolean): Boolean = buffering && playWhenReady

    fun axisOf(dx: Float, dy: Float): Axis {
        if (abs(dx) < AXIS_SLOP && abs(dy) < AXIS_SLOP) return Axis.UNDECIDED
        return if (abs(dx) >= abs(dy)) Axis.HORIZONTAL else Axis.VERTICAL
    }

    /** 横向拖动要跳到的位置，以及相对起点的时间差（界面用它显示「+01:20」）。 */
    data class SeekPreview(val targetMs: Long, val deltaMs: Long)

    fun seekPreview(startMs: Long, dx: Float, width: Int, durationMs: Long): SeekPreview {
        if (width <= 0) return SeekPreview(startMs.coerceAtLeast(0L), 0L)
        val delta = (dx / width * SEEK_FULL_WIDTH_MS).toLong()
        val target = seekTarget(startMs, delta, durationMs)
        // 起点本来就在末尾时，夹紧后的位移和名义位移不一致，显示的差值要跟着修正，
        // 否则会出现「显示 +30 秒但画面没动」。
        return SeekPreview(target, target - startMs)
    }

    /** 上滑变亮。`dy` 是相对按下点的位移（向上为负）。 */
    fun brightness(start: Float, dy: Float, height: Int): Float {
        val from = start.coerceIn(0f, 1f)
        if (height <= 0) return from
        return (from - dy / height).coerceIn(0f, 1f)
    }

    /** 上滑变大。音量是整数档位，直接按比例算再夹紧。 */
    fun volume(start: Int, dy: Float, height: Int, maxVolume: Int): Int {
        if (maxVolume <= 0) return 0
        val from = start.coerceIn(0, maxVolume)
        if (height <= 0) return from
        return (from - dy / height * maxVolume).roundToInt().coerceIn(0, maxVolume)
    }

    /**
     * 相对当前位置走 [deltaMs] 之后的落点。所有「按一下跳几秒」的入口都走这里。
     *
     * 时长未知时（直播、还没 prepare 完）**不能**把上界当成 0：`coerceIn(0, 0)` 会把
     * 每一次快进/快退都变成「跳到第 0 秒」。这时上界放到 `Long.MAX_VALUE`，只夹下界。
     */
    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val upper = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return (positionMs + deltaMs).coerceIn(0L, upper)
    }

    /** 双击左半屏退 10 秒、右半屏进 10 秒。 */
    fun doubleTapSeekMs(startMs: Long, x: Float, width: Int, durationMs: Long): Long {
        val step = if (sideOf(x, width) == Side.LEFT) -DOUBLE_TAP_SEEK_MS else DOUBLE_TAP_SEEK_MS
        return seekTarget(startMs, step, durationMs)
    }

    /** 进度与时长：一小时以内用 `12:34`，超过用 `1:02:03`。 */
    fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) + 500L) / 1000L
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** 倍速标签：`1.0x` / `1.25x` / `2.0x`。 */
    fun speedLabel(speed: Float): String {
        val text = "%.2f".format(speed).trimEnd('0')
        return (if (text.endsWith('.')) text + "0" else text) + "x"
    }

    /** 拖动进度时显示的相对位移：`+01:20` / `-00:35`。分钟补零，宽度不跳动。 */
    fun deltaLabel(deltaMs: Long): String {
        val sign = if (deltaMs < 0) "-" else "+"
        val text = formatTime(abs(deltaMs))
        val parts = text.split(':')
        return sign + if (parts.size == 2) "%02d:%s".format(parts[0].toInt(), parts[1]) else text
    }
}

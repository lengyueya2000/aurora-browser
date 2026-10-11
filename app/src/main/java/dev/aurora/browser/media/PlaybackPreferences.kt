package dev.aurora.browser.media

import android.content.Context
import android.content.SharedPreferences

/**
 * 播放偏好：**记忆播放进度**与上次用的倍速。
 *
 * 起因是「播放效果不太好」里的两条——每次全屏都从第 0 秒开始、每次都要重新调倍速。
 * 进度按 URL 存，键用 URL 的哈希（URL 本身太长且含特殊字符，不适合当键名）。
 *
 * 什么时候不记：看得太浅（不足 [MIN_RESUME_MS]）或已经看到结尾（离结束不足
 * [TAIL_MS]）都不记——否则用户重开时会「接着播」到第 3 秒或直接跳到结尾，比从头播还烦。
 */
class PlaybackPreferences(private val prefs: SharedPreferences) {

    fun resumeMs(url: String): Long = prefs.getLong(positionKey(url), 0L)

    fun remember(url: String, positionMs: Long, durationMs: Long) {
        if (positionMs < MIN_RESUME_MS) return
        if (durationMs > 0L && positionMs > durationMs - TAIL_MS) return
        prefs.edit().putLong(positionKey(url), positionMs).apply()
    }

    /** 播完或用户手动重看时清掉，免得下次又跳回去。 */
    fun forget(url: String) {
        prefs.edit().remove(positionKey(url)).apply()
    }

    fun speed(): Float {
        val stored = prefs.getFloat(KEY_SPEED, 1f)
        return if (stored in PlayerGestures.SPEEDS) stored else 1f
    }

    fun rememberSpeed(speed: Float) {
        if (speed !in PlayerGestures.SPEEDS) return
        prefs.edit().putFloat(KEY_SPEED, speed).apply()
    }

    private fun positionKey(url: String): String = "$KEY_POSITION_PREFIX${url.hashCode()}"

    companion object {
        private const val PREFS_NAME = "aurora_playback"
        private const val KEY_POSITION_PREFIX = "pos_"
        private const val KEY_SPEED = "speed"

        /** 看不够这么久就不值得记，从头播反而更符合预期。 */
        const val MIN_RESUME_MS = 5_000L

        /** 离结尾不足这么久算「看完了」，下次从头开始。 */
        const val TAIL_MS = 30_000L

        fun create(context: Context): PlaybackPreferences =
            PlaybackPreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

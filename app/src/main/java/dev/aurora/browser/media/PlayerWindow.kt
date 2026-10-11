package dev.aurora.browser.media

/**
 * 小窗（画中画）的窗口比例。
 *
 * Android 只接受 1/2.39–2.39 之间的比例，超出会直接抛
 * `IllegalArgumentException`。竖屏短剧（9:16 已经接近下限）再极端一点就会越界，
 * 所以这里先夹紧再交给系统。纯计算，可单测。
 */
object PlayerWindow {

    /** Android 的 `PictureInPictureParams` 硬性区间。 */
    const val MIN_RATIO = 1f / 2.39f
    const val MAX_RATIO = 2.39f

    /**
     * 返回化简后的宽高比。
     *
     * 读不到视频尺寸（还没 `onVideoSizeChanged`）时按 16:9 给，这是影视内容最常见的情况，
     * 总比让系统按默认的方窗口摆一个好。
     */
    fun pipAspectRatio(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val ratio = width.toFloat() / height
        return when {
            ratio > MAX_RATIO -> 239 to 100
            ratio < MIN_RATIO -> 100 to 239
            else -> {
                val divisor = gcd(width, height)
                (width / divisor) to (height / divisor)
            }
        }
    }

    private fun gcd(a: Int, b: Int): Int {
        var x = a
        var y = b
        while (y != 0) {
            val t = y
            y = x % y
            x = t
        }
        return if (x == 0) 1 else x
    }
}

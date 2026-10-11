package dev.aurora.browser.media

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 沉浸式（隐藏状态栏 / 导航栏）与刘海策略。
 *
 * 抽出来是因为有两条路要用它：站点自己的全屏（`WebChromeClient.onShowCustomView`）和
 * 我们自己的原生播放器（[PlayerActivity]）。两处各写一遍迟早会漂移——一处改了另一处
 * 没改，表现就是「从这个入口进全屏状态栏还在」。
 */
internal object ImmersiveMode {

    /** 进入沉浸式：藏系统栏、允许铺到刘海区、看视频别息屏。 */
    fun enter(activity: Activity, savedCutoutMode: Int?): Int? {
        val window = activity.window ?: return savedCutoutMode
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val decor = window.decorView
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        // 现代 API 是 insets controller，但国内几家 ROM（vivo / OPPO / 小米）对它的响应
        // 并不一致，只走一条路时真机上会出现「视频铺满了，状态栏还挂在那儿」。
        @Suppress("DEPRECATION")
        run {
            decor.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        return setCutoutMode(window, WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES)
            ?: savedCutoutMode
    }

    /** 退出沉浸式：恢复系统栏、恢复刘海策略、恢复息屏。 */
    fun exit(activity: Activity, savedCutoutMode: Int?) {
        val window = activity.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val decor = window.decorView
        controller.show(WindowInsetsCompat.Type.systemBars())
        @Suppress("DEPRECATION")
        run { decor.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedCutoutMode != null) setCutoutMode(window, savedCutoutMode)
    }

    /** 改刘海策略并返回改动前的值；API 28 以下没有这个属性，返回 null。 */
    private fun setCutoutMode(window: android.view.Window, mode: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val attrs = window.attributes
            val previous = attrs.layoutInDisplayCutoutMode
            attrs.layoutInDisplayCutoutMode = mode
            window.attributes = attrs
            previous
        }.getOrNull()
    }
}

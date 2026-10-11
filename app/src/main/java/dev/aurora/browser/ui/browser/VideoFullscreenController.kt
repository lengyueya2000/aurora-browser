package dev.aurora.browser.ui.browser

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 网页视频全屏的宿主：把 `WebChromeClient` 交出来的自定义 View 贴到窗口最上层，
 * 并切到沉浸式（隐藏状态栏与导航栏）。
 *
 * 抽成接口是为了让状态机能在纯 JVM / Robolectric 上测：真正的 View 挂载与系统栏
 * 切换没法在单测里断言，但「返回键归谁」「重复 show 会不会漏掉旧 View」这些逻辑必须测。
 */
interface FullscreenHost {
    /** 把自定义 View 挂到窗口内容之上。 */
    fun attach(view: View)

    /** 从窗口移除自定义 View（不销毁，WebView 还要收回去）。 */
    fun detach(view: View)

    /** 切换沉浸式：隐藏 / 恢复系统栏。 */
    fun setImmersive(on: Boolean)
}

/**
 * 网页视频全屏状态机（规格 5.4 的视频播放）。
 *
 * 为什么必须有这一层：WebView 里 HTML5 视频点全屏时，系统会把一个自定义 View 通过
 * `WebChromeClient.onShowCustomView` 交出来，**应用自己负责**把它放到窗口上并隐藏系统栏。
 * 不接管这个回调，`requestFullscreen()` 会静默失败——视频只会在网页里的小窗里转圈，
 * 这就是「无法真正全屏」的根因。
 */
class VideoFullscreenController(private val host: FullscreenHost) {

    private val _isFullscreen = mutableStateOf(false)

    /** 组合期读它来让返回键逻辑跟随全屏状态（plain var 不会触发重组）。 */
    val isFullscreen: State<Boolean> get() = _isFullscreen

    private var current: View? = null
    private var callback: WebChromeClient.CustomViewCallback? = null

    /** `WebChromeClient.onShowCustomView` 转发到这里。 */
    fun show(view: View, onHidden: WebChromeClient.CustomViewCallback) {
        // 已在全屏时又收到一次 show：先把旧的收干净再挂新的，否则旧 View 会永久
        // 留在窗口上（站点切换清晰度/切集时常见），既漏内存又盖住新画面。
        if (_isFullscreen.value) hideInternal(notifyWebView = true)
        // 挂载失败（窗口状态异常、View 已被别处收走）就当作这次全屏没发生：
        // 既不留下一个吃掉返回键的假全屏状态，也不让 WebView 卡在「等回调」里。
        if (runCatching { host.attach(view) }.isFailure) {
            runCatching { onHidden.onCustomViewHidden() }
            return
        }
        current = view
        callback = onHidden
        runCatching { host.setImmersive(true) }
        _isFullscreen.value = true
    }

    /** `WebChromeClient.onHideCustomView` 转发到这里；也用于界面销毁时兜底。 */
    fun hide() {
        if (!_isFullscreen.value && current == null) return
        hideInternal(notifyWebView = true)
    }

    /**
     * 返回键：全屏中则退出全屏并**消费**这次返回（返回 true）。
     *
     * 不消费的话，用户在全屏里按返回会直接后退网页甚至退出应用，而画面还停在
     * 全屏的黑底上——这是全屏视频最常见的 bug。
     */
    fun onBackPressed(): Boolean {
        if (!_isFullscreen.value) return false
        hide()
        return true
    }

    private fun hideInternal(notifyWebView: Boolean) {
        current?.let { runCatching { host.detach(it) } }
        current = null
        runCatching { host.setImmersive(false) }
        _isFullscreen.value = false
        val cb = callback
        callback = null
        // WebView 靠这个回调把自定义 View 收回自己的容器；漏调会导致下次全屏拿不到画面。
        if (notifyWebView) runCatching { cb?.onCustomViewHidden() }
    }
}

/** 真实实现：把 View 挂到 `android.R.id.content`，并在窗口上切沉浸式。 */
class ActivityFullscreenHost(private val activity: Activity) : FullscreenHost {

    /** 进入全屏前的刘海策略，退出时原样恢复。 */
    private var savedCutoutMode: Int? = null

    /** 进入全屏前的屏幕方向，退出时原样恢复。 */
    private var savedOrientation: Int? = null

    override fun attach(view: View) {
        // `android.R.id.content` 是窗口里所有内容的根，正常一定能找到；找不到就退到
        // decorView，宁可铺得不够满，也不要让全屏静默失败。
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: (activity.window?.decorView as? ViewGroup)
            ?: return
        // 自定义 View 可能是从 WebView 内部搬过来的，先摘离原父节点，否则 addView 抛异常。
        (view.parent as? ViewGroup)?.removeView(view)
        view.setBackgroundColor(Color.BLACK)
        runCatching {
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            view.bringToFront()
            view.requestFocus()
        }
        // 横屏视频在竖屏里全屏只能占中间一条，上下两条黑边白扔一半屏幕。系统浏览器
        // 全屏播放时都会把界面掰到横屏，这里照做；退出全屏时恢复原来的方向。
        // 布局参数不恢复也无妨——`configChanges` 里已经声明了 orientation，旋转不会
        // 重建 Activity，挂上去的全屏 View 因此不会被销毁。
        savedOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // 横屏全屏视频要能铺到刘海区，否则左右会被切掉一条黑边。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activity.window?.let { window ->
                val attrs = window.attributes
                savedCutoutMode = attrs.layoutInDisplayCutoutMode
                attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                window.attributes = attrs
            }
        }
    }

    override fun detach(view: View) {
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        savedOrientation?.let { saved ->
            runCatching { activity.requestedOrientation = saved }
            savedOrientation = null
        }
    }

    /**
     * 隐藏 / 恢复系统栏。
     *
     * 现代 API 是 insets controller，但同时把旧的 `systemUiVisibility` 标志位也点上：
     * 国内几家 ROM（vivo / OPPO / 小米）对 controller 的响应并不一致，只走一条路时
     * 真机上会出现「视频铺满了，状态栏还挂在那儿」。
     */
    override fun setImmersive(on: Boolean) {
        val window = activity.window ?: return
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val decor = window.decorView
        if (on) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            @Suppress("DEPRECATION")
            run {
                decor.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            // 看视频时不该被息屏打断。
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            @Suppress("DEPRECATION")
            run { decor.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            savedCutoutMode?.let { mode ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    window.let { w ->
                        val attrs = w.attributes
                        attrs.layoutInDisplayCutoutMode = mode
                        w.attributes = attrs
                    }
                }
            }
            savedCutoutMode = null
        }
    }
}

/** 没有 Activity 时（预览 / 单测）的降级实现：只保留状态机，不碰窗口。 */
object NoopFullscreenHost : FullscreenHost {
    override fun attach(view: View) = Unit
    override fun detach(view: View) = Unit
    override fun setImmersive(on: Boolean) = Unit
}

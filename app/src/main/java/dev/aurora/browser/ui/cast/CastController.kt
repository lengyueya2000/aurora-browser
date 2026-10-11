package dev.aurora.browser.ui.cast

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * 系统「无线投屏 / 屏幕镜像」设置页的候选 action，按顺序尝试。
 *
 * 没有统一标准：AOSP 用 `android.settings.CAST_SETTINGS`，部分 OEM 沿用了旧的
 * `WIFI_DISPLAY_SETTINGS`。第一个能起来的就算成功。
 */
internal val CastSettingsActions = listOf(
    "android.settings.CAST_SETTINGS",
    "com.android.settings.WIFI_DISPLAY_SETTINGS",
)

/**
 * 投屏（规格 5.4 的媒体能力）。
 *
 * Android 上的「投屏」是两件不同的事，这里都接上：
 *
 * 1. **屏幕镜像**：由系统实现，第三方应用无法自己开始镜像，只能把用户送到系统的投屏
 *    界面（[openSystemCastSettings]）。
 * 2. **投屏播放视频**：把页面里的视频地址交给能播它的应用（[playOnDevice]）。Chromecast /
 *    DLNA 类的投屏播放器都注册了 video 通配 MIME，系统的选择器会把它们列出来。
 *
 * 为什么不用 Google Cast SDK：它要求注册 Cast app id 并依赖 Google Play 服务，
 * 在没有 GMS 的设备上直接不可用，还会把闭源依赖引进来。`androidx.mediarouter` 的接收端
 * 选择器是 `AppCompatDialog` 的子类，会要求整个应用换成 AppCompat 主题——对一个纯 Compose
 * 应用来说代价太大。所以这里退到系统能力 + 应用选择器，覆盖真实使用场景且零新增依赖。
 *
 * 所有系统调用都包在 `runCatching` 里并返回布尔值：投屏能力因设备而异，拿不到就返回
 * false 让界面给出可读提示，而不是抛异常把浏览器打崩。
 */
class CastController(private val context: Context) {

    /**
     * 打开系统的投屏 / 屏幕镜像界面。所有候选 action 都起不来时返回 false。
     *
     * 先用 `resolveActivity` 判一次可达性再 start：`startActivity` 在多数机型上抛
     * `ActivityNotFoundException`，但在部分 ROM 上会静默失败，用户看到的就是「点了没反应」。
     * 可达性判定依赖 manifest 里的 `<queries>`——Android 11+ 不声明就查不到目标包。
     */
    fun openSystemCastSettings(): Boolean = CastSettingsActions.any { action ->
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!canResolve(intent)) return@any false
        runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /**
     * 把视频地址交给能播放它的应用。返回 false 表示系统里没有能接这个地址的应用。
     *
     * MIME 取不到时兜底成 video 通配类型：接收端应用普遍按 MIME 注册，不给类型匹配不到。
     */
    fun playOnDevice(url: String, mime: String?): Boolean = runCatching {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), mime?.takeIf { it.isNotBlank() } ?: "video/*")
        }
        // 先确认有应用能接，否则会弹出一个空的选择器（比报错更让人困惑）。
        if (!canResolve(view)) return false
        context.startActivity(
            Intent.createChooser(view, "投屏播放").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    private fun canResolve(intent: Intent): Boolean =
        runCatching { context.packageManager.resolveActivity(intent, 0) != null }
            .getOrDefault(false)
}

package dev.aurora.browser.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

/**
 * 离线缓存那条进度通知的权限。
 *
 * Android 13（API 33）起 `POST_NOTIFICATIONS` 是**运行时**权限：只在清单里声明是不够的，
 * 不请求就发通知，系统会安静地把通知丢掉——不抛异常、不报错。表现出来就是「下载在跑，
 * 但下拉通知栏什么都没有」，而前台服务的通知是用户唯一能看见「它还在下」的地方。
 *
 * 请求时机放在**用户点下「下载」的那一刻**，不在启动时问：启动就弹权限框，用户还不知道
 * 这个权限是干什么的，多半直接拒掉；而点下载时问，「为了让你看到下载进度」是一句立刻能
 * 理解的话，同意率也高得多。
 *
 * 拒绝的后果被刻意做得很轻：下载照常进行，只是没有通知（清单注释里已经写明这一点）。
 * 所以这里**不重复问**——用户拒过一次就不再打扰，也不再在别处提醒。
 */
object NotificationPermission {

    /** 需不需要先请求。低于 Android 13 的机型没有这个权限，一律当作已授予。 */
    fun needsRequest(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 该请求就请求，否则什么都不做。
     *
     * 用 Compose 的 `rememberLauncherForActivityResult` 拿到 launcher 之后调这个：
     * 把「要不要请求」的判断收在一处，免得两个调用点（浏览页的下载按钮、播放器的下载按钮）
     * 各写一遍、漏掉一处。
     */
    fun requestIfNeeded(context: Context, launcher: ActivityResultLauncher<String>) {
        if (needsRequest(context)) {
            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

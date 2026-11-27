package dev.aurora.browser.data

import android.content.Context
import androidx.core.content.edit

/**
 * 更新检测的本地状态。
 *
 * 只记两件事：
 * - `lastCheckAt`：上次**启动静默检查**的时间。24 小时内不再检查，避免每次冷启动
 *   都请求网络。手动检查不写这个字段——用户主动查是一次性动作，不该影响自动节奏。
 * - `ignoredVersion`：用户点过「忽略此版本」的版本号。永久忽略到下一个版本出现，
 *   否则每 24 小时又弹一次同一个版本，很烦。
 *
 * 用独立的 prefs 文件，不跟书签/历史混在一起——那些数据结构复杂、有上限逻辑，
 * 更新状态就两个标量，放一起只会互相干扰。
 */
class UpdatePreferences(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lastCheckAt(): Long = runCatching { prefs.getLong(KEY_LAST_CHECK, 0L) }.getOrDefault(0L)

    fun ignoredVersion(): String? = runCatching { prefs.getString(KEY_IGNORED, null) }.getOrNull()

    /** 距上次检查满 24 小时才查。时间倒流（改系统时间）当作该查。 */
    fun shouldAutoCheck(now: Long = System.currentTimeMillis()): Boolean =
        now - lastCheckAt() >= INTERVAL_MS || now < lastCheckAt()

    fun markChecked(now: Long = System.currentTimeMillis()) {
        runCatching { prefs.edit { putLong(KEY_LAST_CHECK, now) } }
    }

    fun ignore(version: String) {
        runCatching { prefs.edit { putString(KEY_IGNORED, version) } }
    }

    private companion object {
        const val PREFS = "aurora_update"
        const val KEY_LAST_CHECK = "last_check_at"
        const val KEY_IGNORED = "ignored_version"
        const val INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}

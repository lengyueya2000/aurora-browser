package dev.aurora.browser.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aurora.browser.data.UpdatePreferences
import dev.aurora.browser.data.UpdateRepository
import dev.aurora.browser.domain.UpdateChecker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 更新检测的状态机。
 *
 * 同时服务两个入口：
 * - 启动时 [autoCheck]：受 24 小时节流约束，有新版本就弹窗。
 * - 设置页 [manualCheck]：不受节流约束，结果只显示在设置页那一行。
 *
 * 弹窗状态 [dialog] 由这里持有，UI 只负责显示——避免把网络逻辑散到 Composable 里。
 */
class UpdateViewModel(
    private val context: Context,
    private val currentVersion: String,
    private val prefs: UpdatePreferences = UpdatePreferences(context),
    private val repository: UpdateRepository = UpdateRepository,
) : ViewModel() {

    /** 设置页那行的状态文案。 */
    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object UpToDate : Status
        data object Failed : Status
    }

    /** 检测到的新版本，供弹窗显示；null 表示不弹。 */
    data class Available(val info: UpdateChecker.ReleaseInfo)

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /**
     * 给设置页显示的状态文案。
     *
     * **必须是 StateFlow 而不是 `statusText()` 函数**：Compose 只在组合期读到 Snapshot
     * State 才会订阅。原来的写法在 BrowserScreen 里调 `updateVm.statusText()`，读的是
     * 一次性的普通属性——状态从「点击检查」变成「正在检查…」时不会重组，界面永远
     * 停在旧文案上（模拟机上实测就是这个症状）。
     */
    val statusText: StateFlow<String> = _status.map { status ->
        when (status) {
            Status.Idle -> "点击检查"
            Status.Checking -> "正在检查…"
            Status.UpToDate -> "已是最新版本"
            Status.Failed -> "检查失败，请稍后重试"
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "点击检查")

    private val _dialog = MutableStateFlow<Available?>(null)
    val dialog: StateFlow<Available?> = _dialog.asStateFlow()

    /** 启动时静默检查。24 小时内已查过就跳过；失败一律静默。 */
    fun autoCheck() {
        if (!prefs.shouldAutoCheck()) return
        prefs.markChecked()
        viewModelScope.launch {
            // 网络请求是 suspend，先取到字符串，再交给纯函数判断。
            val json = repository.fetchReleases()
            val result = UpdateChecker.check(currentVersion, prefs.ignoredVersion()) { json }
            if (result is UpdateChecker.CheckResult.NewVersion) {
                _dialog.value = Available(result.info)
            }
        }
    }

    /** 设置页手动检查：写状态，不写 lastCheckAt（不影响自动检查节奏），也不弹窗。 */
    fun manualCheck() {
        _status.value = Status.Checking
        viewModelScope.launch {
            val json = repository.fetchReleases()
            val result = UpdateChecker.check(currentVersion, null) { json }
            // 用户主动查就是想看到结果：有新版直接弹窗，那一行同时显示状态。
            _status.value = when (result) {
                is UpdateChecker.CheckResult.NewVersion -> {
                    _dialog.value = Available(result.info)
                    Status.UpToDate
                }
                UpdateChecker.CheckResult.UpToDate -> Status.UpToDate
                UpdateChecker.CheckResult.Failed -> Status.Failed
            }
        }
    }

    /** 用户点「忽略此版本」：记下来，下次启动不再弹同一版本。 */
    fun ignore() {
        _dialog.value?.info?.version?.let { prefs.ignore(it) }
        _dialog.value = null
    }

    fun dismiss() {
        _dialog.value = null
    }

    /** 用系统浏览器打开 Release 页。 */
    fun openReleasePage() {
        val url = _dialog.value?.info?.pageUrl ?: return
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        _dialog.value = null
    }

}

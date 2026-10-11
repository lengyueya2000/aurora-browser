package dev.aurora.browser

import android.app.Application
import android.webkit.WebView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.aurora.browser.data.BrowserStore
import dev.aurora.browser.data.PrefsBrowserStore
import dev.aurora.browser.domain.TabRepository
import dev.aurora.browser.media.AuroraDownloadService
import dev.aurora.browser.ui.browser.BrowserViewModel

/**
 * 应用级依赖容器。
 *
 * M2 时只有一个 ViewModel、一个仓库，`viewModel()` 的无参构造就够用；现在依赖涨到
 * 「标签仓库 + 持久化存储」，再靠默认参数各自 new 一个，会让「两个界面看到的是不是同一份数据」
 * 变成疑问（设置页改了搜索引擎，浏览页要立刻生效）。因此引入一个最小容器。
 *
 * 标签仓库放在应用级而非 ViewModel 级：进程内切换 Activity 时标签不该丢。
 */
class AuroraApp : Application() {
    val tabs: TabRepository by lazy { TabRepository() }
    val store: BrowserStore by lazy { PrefsBrowserStore(this) }

    override fun onCreate() {
        // debug 构建打开 WebView 远程调试（chrome://inspect），页面里的问题才有得查；
        // release 关着，免得用户在真机上被接上调试口。
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        super.onCreate()
        // 尽早装上：真机上复现不了的闪退，下次启动时能把堆栈原样交出来。
        CrashLog.install(this)
        createOfflineChannel()
    }

    /**
     * 离线缓存的前台服务通知渠道。
     *
     * 渠道 id 必须和 [dev.aurora.browser.media.AuroraDownloadService.CHANNEL_ID] 一致；
     * 渠道要在发通知之前建好，否则 Android 8+ 会直接把通知丢掉，下载进度就看不见了。
     */
    private fun createOfflineChannel() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        runCatching {
            val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(AuroraDownloadService.CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                android.app.NotificationChannel(
                    AuroraDownloadService.CHANNEL_ID,
                    getString(R.string.offline_channel_name),
                    android.app.NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.offline_channel_description) },
            )
        }
    }
}

/** 把容器喂给 `BrowserViewModel`。 */
class BrowserViewModelFactory(
    private val tabs: TabRepository,
    private val store: BrowserStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        BrowserViewModel(tabs = tabs, store = store) as T
}

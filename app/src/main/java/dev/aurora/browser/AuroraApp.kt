package dev.aurora.browser

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.aurora.browser.data.BrowserStore
import dev.aurora.browser.data.PrefsBrowserStore
import dev.aurora.browser.domain.TabRepository
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

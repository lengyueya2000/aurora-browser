package dev.aurora.browser.domain

import dev.aurora.browser.domain.model.BrowserTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * 内存标签仓库。M2 不持久化——进程重启即清空（规格：持久化属 M3）。
 * 用不可变 List 快照驱动 UI，避免 Compose 静默失效。
 * 不变量：tabs 非空；activeId 始终指向 tabs 中的一项。
 */
class TabRepository {
    private val _tabs = MutableStateFlow(listOf(BrowserTab(id = newId())))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _activeId = MutableStateFlow(_tabs.value.first().id)
    val activeId: StateFlow<String> = _activeId.asStateFlow()

    val active: BrowserTab
        get() = _tabs.value.firstOrNull { it.id == _activeId.value } ?: _tabs.value.first()

    private fun newId(): String = UUID.randomUUID().toString()

    fun newTab(url: String? = null, incognito: Boolean = false): BrowserTab {
        val tab = BrowserTab(id = newId(), url = url, isIncognito = incognito)
        _tabs.value = _tabs.value + tab
        _activeId.value = tab.id
        return tab
    }

    fun close(id: String) {
        val current = _tabs.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val remaining = current.filterNot { it.id == id }
        if (remaining.isEmpty()) {
            val blank = BrowserTab(id = newId())
            _tabs.value = listOf(blank)
            _activeId.value = blank.id
            return
        }
        _tabs.value = remaining
        if (_activeId.value == id) {
            // 关闭当前标签后落到右邻，越界则落到新的最后一个
            _activeId.value = remaining[minOf(index, remaining.lastIndex)].id
        }
    }

    fun select(id: String) {
        if (_tabs.value.any { it.id == id }) _activeId.value = id
    }

    /**
     * 把标签退回首页（新标签页状态）。
     *
     * 这里换一个新的 [BrowserTab] 而不是 `copy(url = null)`：[update] 的 `url ?: tab.url` 表示
     * 「没传就不改」，`null` 在那里是「保持原样」，用它清不掉地址。标题也得一起复位，否则首页
     * 会顶着上一个网页的标题。无痕属性与分组保留——退首页不该把标签变成另一种标签。
     */
    fun goHome(id: String) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id != id) tab
            else BrowserTab(id = tab.id, isIncognito = tab.isIncognito, groupId = tab.groupId)
        }
    }

    fun update(id: String, url: String? = null, title: String? = null) {
        _tabs.value = _tabs.value.map { tab ->
            if (tab.id != id) tab
            else tab.copy(url = url ?: tab.url, title = title ?: tab.title)
        }
    }

    fun move(from: Int, to: Int) {
        val list = _tabs.value.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _tabs.value = list.toList()
    }
}

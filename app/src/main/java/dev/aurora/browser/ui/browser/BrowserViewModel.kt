package dev.aurora.browser.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aurora.browser.domain.BackOutcome
import dev.aurora.browser.domain.BackPressDispatcher
import dev.aurora.browser.domain.TabRepository
import dev.aurora.browser.domain.model.BrowserTab
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val SUGGESTION_POOL = listOf(
    "Compose 延迟布局 LazyLayout",
    "Compose 共享元素转场",
    "Compose 性能检查清单",
    "developer.android.com",
    "juejin.cn",
    "m3.material.io",
)

/**
 * 浏览界面的状态机。只持有界面状态与领域仓库，不持有 WebView——
 * 网页后退由调用方根据 [onBack] 返回的 [BackOutcome] 执行。
 */
class BrowserViewModel(
    private val tabs: TabRepository = TabRepository(),
    private val back: BackPressDispatcher = BackPressDispatcher(),
) : ViewModel() {

    private val _surface = MutableStateFlow<BrowserSurface>(BrowserSurface.Page)
    val surface: StateFlow<BrowserSurface> = _surface.asStateFlow()

    private val _menuOpen = MutableStateFlow(false)
    val menuOpen: StateFlow<Boolean> = _menuOpen.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val suggestions: StateFlow<List<String>> = _query
        .map { q ->
            if (q.isBlank()) emptyList()
            else SUGGESTION_POOL.filter { it.contains(q, ignoreCase = true) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _exitHintVisible = MutableStateFlow(false)
    val exitHintVisible: StateFlow<Boolean> = _exitHintVisible.asStateFlow()

    val tabsState: StateFlow<List<BrowserTab>> = tabs.tabs
    // 不能写 first { }：TabRepository.close 先写 _tabs 再写 _activeId，两个 StateFlow
    // 之间有一瞬 activeId 仍指向已从 tabs 移除的项（关闭最后一个标签时新旧 id 更是全变）。
    // 此时 first { } 抛 NoSuchElementException，异常在 stateIn 协程内被静默吞掉，
    // combine 协程随之终止、activeTab 永久停在旧值——实测关闭当前标签后 activeTab
    // 仍指向那个已关闭的标签（生产环境该未捕获异常会直接崩应用）。
    // firstOrNull 兜底到首个标签，下一拍 activeId 更新即被纠正。
    val activeTab: StateFlow<BrowserTab> = combine(tabs.tabs, tabs.activeId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.first()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, tabs.active)

    fun openOmnibox() { _surface.value = BrowserSurface.Omnibox }
    fun closeOmnibox() { _surface.value = BrowserSurface.Page; _query.value = "" }
    fun setQuery(q: String) { _query.value = q }
    fun openTabSwitcher() { _surface.value = BrowserSurface.TabSwitcher }
    fun closeTabSwitcher() { _surface.value = BrowserSurface.Page }
    fun openMenu() { _menuOpen.value = true }
    fun closeMenu() { _menuOpen.value = false }

    fun newTab(url: String? = null): BrowserTab {
        val tab = tabs.newTab(url)
        _surface.value = BrowserSurface.Page
        _menuOpen.value = false
        return tab
    }

    fun closeTab(id: String) = tabs.close(id)
    fun selectTab(id: String) { tabs.select(id); _surface.value = BrowserSurface.Page }
    fun updateActiveTab(url: String? = null, title: String? = null) =
        tabs.update(tabs.activeId.value, url = url, title = title)

    fun navigate(url: String) {
        tabs.update(tabs.activeId.value, url = url)
        _surface.value = BrowserSurface.Page
        _menuOpen.value = false
        _query.value = ""
    }

    /** 由 Activity 的 BackHandler 调用。 */
    fun onBack(canGoBack: Boolean): BackOutcome {
        val outcome = back.onBack(
            panelOpen = _menuOpen.value || _surface.value == BrowserSurface.TabSwitcher,
            omniboxFocused = _surface.value == BrowserSurface.Omnibox,
            canGoBack = canGoBack,
        )
        when (outcome) {
            BackOutcome.CLOSE_PANEL -> {
                if (_menuOpen.value) _menuOpen.value = false
                else _surface.value = BrowserSurface.Page
            }
            BackOutcome.EXIT_OMNIBOX -> closeOmnibox()
            BackOutcome.ARM_EXIT -> { _exitHintVisible.value = true; armExitHintReset() }
            else -> Unit
        }
        return outcome
    }

    private fun armExitHintReset() {
        viewModelScope.launch {
            delay(2000)
            _exitHintVisible.value = false
        }
    }
}

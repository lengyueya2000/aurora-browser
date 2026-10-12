package dev.aurora.browser.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aurora.browser.data.BrowserStore
import dev.aurora.browser.data.InMemoryBrowserStore
import dev.aurora.browser.domain.BackOutcome
import dev.aurora.browser.domain.BackPressDispatcher
import dev.aurora.browser.domain.SearchEngine
import dev.aurora.browser.domain.TabRepository
import dev.aurora.browser.domain.UrlResolver
import dev.aurora.browser.domain.model.Bookmark
import dev.aurora.browser.domain.model.BrowserTab
import dev.aurora.browser.domain.model.HistoryEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 建议行的类型，决定行首图标与副标题。 */
enum class SuggestionKind(val hint: String) {
    SEARCH("搜索"),
    URL("网址"),
    BOOKMARK("书签"),
    HISTORY("历史"),
}

/** 一条地址栏建议。`url` 是已经解析好的、可直接加载的地址。 */
data class OmniboxSuggestion(val label: String, val url: String, val kind: SuggestionKind)

/**
 * 浏览界面的状态机。只持有界面状态与领域仓库，不持有 WebView——
 * 网页后退由调用方根据 [onBack] 返回的 [BackOutcome] 执行。
 */
class BrowserViewModel(
    private val tabs: TabRepository = TabRepository(),
    private val back: BackPressDispatcher = BackPressDispatcher(),
    private val store: BrowserStore = InMemoryBrowserStore(),
) : ViewModel() {

    // ---------- 面板 / 覆盖层状态 ----------

    private val _surface = MutableStateFlow<BrowserSurface>(BrowserSurface.Page)
    val surface: StateFlow<BrowserSurface> = _surface.asStateFlow()

    private val _menuOpen = MutableStateFlow(false)
    val menuOpen: StateFlow<Boolean> = _menuOpen.asStateFlow()

    private val _exitHintVisible = MutableStateFlow(false)
    val exitHintVisible: StateFlow<Boolean> = _exitHintVisible.asStateFlow()

    /** 一次性提示（剪贴板为空、找不到扫码应用……）。展示后由 UI 调用 [consumeMessage]。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    fun consumeMessage() { _message.value = null }
    fun showMessage(text: String) { _message.value = text }

    // ---------- 持久化数据的直通 ----------

    val bookmarks: StateFlow<List<Bookmark>> = store.bookmarks
    val history: StateFlow<List<HistoryEntry>> = store.history
    val searchEngine: StateFlow<SearchEngine> = store.searchEngine
    val blockTrackers: StateFlow<Boolean> = store.blockTrackers
    val desktopMode: StateFlow<Boolean> = store.desktopMode

    fun addBookmark(title: String, url: String) = store.addBookmark(title, url)
    fun removeBookmark(url: String) = store.removeBookmark(url)
    fun clearHistory() = store.clearHistory()
    fun setSearchEngine(engine: SearchEngine) = store.setSearchEngine(engine)
    fun setBlockTrackers(enabled: Boolean) = store.setBlockTrackers(enabled)
    fun setDesktopMode(enabled: Boolean) = store.setDesktopMode(enabled)

    /** 当前活动页是否已收藏。 */
    fun isBookmarked(url: String?): Boolean =
        url != null && bookmarks.value.any { it.url == url }

    fun toggleBookmark() {
        val tab = tabs.active
        val url = tab.url ?: return
        if (isBookmarked(url)) removeBookmark(url) else addBookmark(tab.title, url)
    }

    // ---------- 地址栏输入 ----------

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val suggestions: StateFlow<List<OmniboxSuggestion>> =
        combine(_query, store.searchEngine, store.bookmarks, store.history) { q, engine, marks, hist ->
            buildSuggestions(q, engine, marks, hist)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun openOmnibox() { _surface.value = BrowserSurface.Omnibox }
    fun closeOmnibox() { _surface.value = BrowserSurface.Page; _query.value = "" }
    fun setQuery(q: String) { _query.value = q }

    // ---------- 页面状态 ----------

    private val _trackerCount = MutableStateFlow(0)
    val trackerCount: StateFlow<Int> = _trackerCount.asStateFlow()

    /** 当前页是否因证书错误被拦下（规格 5.3 的证书警告态）。 */
    private val _sslWarning = MutableStateFlow(false)
    val sslWarning: StateFlow<Boolean> = _sslWarning.asStateFlow()

    // ---------- 标签 ----------

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

    fun openTabSwitcher() { _surface.value = BrowserSurface.TabSwitcher }
    fun closeTabSwitcher() { _surface.value = BrowserSurface.Page }
    fun openMenu() { _menuOpen.value = true }
    fun closeMenu() { _menuOpen.value = false }

    fun openSurface(target: BrowserSurface) { _menuOpen.value = false; _surface.value = target }
    fun closeSecondarySurface() { _surface.value = BrowserSurface.Page }

    fun newTab(url: String? = null): BrowserTab {
        val tab = tabs.newTab(url?.let { UrlResolver.resolve(it, store.searchEngine.value) })
        _surface.value = BrowserSurface.Page
        _menuOpen.value = false
        return tab
    }

    /** 新建无痕标签页（规格 5.10）。 */
    fun newIncognitoTab(url: String? = null): BrowserTab {
        val tab = tabs.newTab(url?.let { UrlResolver.resolve(it, store.searchEngine.value) }, incognito = true)
        _surface.value = BrowserSurface.Page
        _menuOpen.value = false
        return tab
    }

    fun closeTab(id: String) = tabs.close(id)

    fun selectTab(id: String) { tabs.select(id); _surface.value = BrowserSurface.Page }

    fun updateActiveTab(url: String? = null, title: String? = null) =
        tabs.update(tabs.activeId.value, url = url, title = title)

    /**
     * 地址栏提交：输入既可能是地址也可能是搜索词，统一交给 [UrlResolver] 判定。
     * 无痕标签页不写入历史。
     */
    fun navigate(input: String) {
        val url = UrlResolver.resolve(input, store.searchEngine.value)
        if (url.isBlank()) return
        val tab = tabs.active
        tabs.update(tab.id, url = url)
        if (!tab.isIncognito) store.recordVisit(tab.title, url)
        _surface.value = BrowserSurface.Page
        _menuOpen.value = false
        _query.value = ""
    }

    /**
     * 页面加载完成：回写该标签的真实标题/地址，并把这次访问记进历史（无痕页除外）。
     *
     * 按 `tabId` 而不是「当前活动标签」回写：每个标签一个 WebView，后台标签也会触发
     * `onPageFinished`，用 active 会让后台页的标题/地址覆盖当前页。
     */
    fun onPageFinished(tabId: String, title: String, url: String) {
        val tab = tabs.tabs.value.firstOrNull { it.id == tabId } ?: return
        tabs.update(tabId, url = url, title = title.ifBlank { url })
        if (!tab.isIncognito) store.recordVisit(title.ifBlank { url }, url)
    }

    fun onPageStarted() {
        _trackerCount.value = 0
        _sslWarning.value = false
    }

    /**
     * 标题变化（`onReceivedTitle`）：只回写标题。
     *
     * 不写历史：加载过程中的标题变化不是一次访问，写进去会在历史里留下一串同地址、
     * 不同标题的中间态（真正的历史由 [onPageFinished] 记）。
     */
    fun onTitle(tabId: String, title: String) {
        if (title.isBlank()) return
        tabs.update(tabId, title = title)
    }

    // shouldInterceptRequest 在 WebView 的后台线程上回调，可能并发进来：
    // `_trackerCount.value += 1` 是读-改-写，两次并发会丢一次计数。
    fun onTrackerBlocked() { _trackerCount.update { it + 1 } }

    fun onSslError() { _sslWarning.value = true }

    // ---------- 返回键 ----------

    /** 由 Activity 的 BackHandler 调用。 */
    fun onBack(atHome: Boolean, canGoBack: Boolean): BackOutcome {
        // 除地址栏外的所有二级界面（标签网格、设置、书签、下载、隐私、阅读）都算「面板」，
        // 返回键先关它们；地址栏聚焦单独走 EXIT_OMNIBOX，顺序不能并进面板里。
        val secondary = _surface.value != BrowserSurface.Page &&
            _surface.value != BrowserSurface.Omnibox
        val outcome = back.onBack(
            panelOpen = _menuOpen.value || secondary,
            omniboxFocused = _surface.value == BrowserSurface.Omnibox,
            atHome = atHome,
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

    /**
     * 把当前标签退回首页。返回标签 id，让调用方顺带释放那个标签的 WebView。
     *
     * 页面是要丢的，不是藏起来：留着它既会在后台继续跑脚本、继续出声，又会让下一次导航
     * 带上一段「已经回不去的」历史——那样从新页面按返回会退回上一个网页，而不是首页。
     */
    fun goHome(): String {
        val id = tabs.activeId.value
        tabs.goHome(id)
        _surface.value = BrowserSurface.Page
        _menuOpen.value = false
        return id
    }

    private fun armExitHintReset() {
        viewModelScope.launch {
            delay(2000)
            _exitHintVisible.value = false
        }
    }

    private fun buildSuggestions(
        query: String,
        engine: SearchEngine,
        marks: List<Bookmark>,
        hist: List<HistoryEntry>,
    ): List<OmniboxSuggestion> {
        val q = query.trim()
        if (q.isEmpty()) {
            // 无输入：先给收藏，再给最近访问（规格 5.2「无输入显示建议」）。
            return marks.take(4).map { OmniboxSuggestion(it.title, it.url, SuggestionKind.BOOKMARK) } +
                hist.distinctBy { it.url }.take(4)
                    .map { OmniboxSuggestion(it.title, it.url, SuggestionKind.HISTORY) }
        }
        val head = if (UrlResolver.isUrlLike(q)) {
            listOf(OmniboxSuggestion(q, UrlResolver.resolve(q, engine), SuggestionKind.URL))
        } else {
            listOf(OmniboxSuggestion("搜索「$q」", engine.searchUrl(q), SuggestionKind.SEARCH))
        }
        val markHits = marks.filter { it.title.contains(q, true) || it.url.contains(q, true) }
            .take(3).map { OmniboxSuggestion(it.title, it.url, SuggestionKind.BOOKMARK) }
        val histHits = hist.filter { it.title.contains(q, true) || it.url.contains(q, true) }
            .distinctBy { it.url }.take(3)
            .map { OmniboxSuggestion(it.title, it.url, SuggestionKind.HISTORY) }
        return (head + markHits + histHits).distinctBy { it.url }
    }
}

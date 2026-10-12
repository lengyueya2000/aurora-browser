package dev.aurora.browser.ui.browser

import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.data.InMemoryBrowserStore
import dev.aurora.browser.domain.BackOutcome
import dev.aurora.browser.domain.SearchEngine
import dev.aurora.browser.domain.TabRepository
import dev.aurora.browser.domain.model.Bookmark
import dev.aurora.browser.domain.model.HistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {
    // viewModelScope 走 Dispatchers.Main；纯 JVM 单测必须先注入测试派发器，否则构造即抛异常。
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = BrowserViewModel(tabs = TabRepository(), back = dev.aurora.browser.domain.BackPressDispatcher())

    @Test fun startsOnPageSurface() {
        assertThat(vm().surface.value).isEqualTo(BrowserSurface.Page)
    }

    @Test fun navigatingClosesOmniboxAndLandsOnPage() {
        val vm = vm()
        vm.openOmnibox()
        vm.navigate("https://a.com")
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
        assertThat(vm.activeTab.value.url).isEqualTo("https://a.com")
    }

    @Test fun newTabGoesToBlankPageNotOmnibox() {
        val vm = vm()
        vm.newTab()
        assertThat(vm.activeTab.value.isNewTab).isTrue()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
    }

    @Test fun menuAndTabSwitcherAreIndependentOfSurface() {
        val vm = vm()
        vm.openTabSwitcher()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.TabSwitcher)
        vm.openMenu()
        assertThat(vm.menuOpen.value).isTrue()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.TabSwitcher)   // 面板不覆盖 surface
    }

    @Test fun suggestionsFilterByQuery() {
        val vm = vm()
        vm.setQuery("com")
        assertThat(vm.suggestions.value).isNotEmpty()
        vm.setQuery("")
        assertThat(vm.suggestions.value).isEmpty()
    }

    @Test fun backClosesMenuBeforeSwitchingSurface() {
        val vm = vm()
        vm.openTabSwitcher()
        vm.openMenu()
        assertThat(vm.onBack(atHome = true, canGoBack = false)).isEqualTo(BackOutcome.CLOSE_PANEL)
        assertThat(vm.menuOpen.value).isFalse()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.TabSwitcher)
    }

    @Test fun backExitsOmniboxThenUsesPageBack() {
        val vm = vm()
        vm.openOmnibox()
        assertThat(vm.onBack(atHome = false, canGoBack = true)).isEqualTo(BackOutcome.EXIT_OMNIBOX)
        assertThat(vm.onBack(atHome = false, canGoBack = true)).isEqualTo(BackOutcome.PAGE_BACK)
    }

    @Test fun backFromTabSwitcherReturnsToPage() {
        val vm = vm()
        vm.openTabSwitcher()
        assertThat(vm.onBack(atHome = true, canGoBack = false)).isEqualTo(BackOutcome.CLOSE_PANEL)
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
    }

    /** 网页退到头时返回键要的是「回首页」，所以 VM 得给出 GO_HOME。 */
    @Test fun backOnAPageWithoutHistoryGoesHome() {
        val vm = vm()
        vm.navigate("https://a.com")
        assertThat(vm.activeTab.value.isNewTab).isFalse()
        assertThat(vm.onBack(atHome = false, canGoBack = false)).isEqualTo(BackOutcome.GO_HOME)
    }

    /** 回首页把当前标签复位，并把二级界面收回去——返回键在哪儿按都该落到同一个状态。 */
    @Test fun goHomeResetsTheActiveTab() {
        val vm = vm()
        vm.navigate("https://a.com")
        vm.openSurface(BrowserSurface.Settings)
        val id = vm.goHome()
        assertThat(id).isEqualTo(vm.activeTab.value.id)
        assertThat(vm.activeTab.value.isNewTab).isTrue()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
        assertThat(vm.menuOpen.value).isFalse()
    }

    @Test fun closingActiveTabKeepsActiveTabValid() {
        val vm = vm()
        val a = vm.activeTab.value.id
        val b = vm.newTab("https://b.com").id
        vm.selectTab(a)
        vm.closeTab(a)                                   // 关掉当前标签
        assertThat(vm.tabsState.value.map { it.id }).containsExactly(b)
        // activeTab 必须跟着落到剩下的标签，而不是停在已关闭的 a
        assertThat(vm.activeTab.value.id).isEqualTo(b)
    }

    // ---------- 地址栏：搜索词 vs 网址 ----------

    /** 之前地址栏只认「带 scheme 的地址」，输入关键词毫无反应——这是最影响可用性的缺陷。 */
    @Test fun keywordInputBecomesASearchUrl() {
        val vm = vm()
        vm.navigate("compose 延迟布局")
        assertThat(vm.activeTab.value.url).startsWith("https://www.bing.com/search?q=")
    }

    @Test fun bareHostInputBecomesHttps() {
        val vm = vm()
        vm.navigate("example.com")
        assertThat(vm.activeTab.value.url).isEqualTo("https://example.com")
    }

    @Test fun fullUrlInputIsKeptVerbatim() {
        val vm = vm()
        vm.navigate("http://a.com/x?y=1")
        assertThat(vm.activeTab.value.url).isEqualTo("http://a.com/x?y=1")
    }

    @Test fun switchingSearchEngineChangesTheSearchUrl() {
        val vm = vm()
        vm.setSearchEngine(SearchEngine.BAIDU)
        vm.navigate("天气")
        assertThat(vm.activeTab.value.url).startsWith("https://www.baidu.com/s?wd=")
    }

    @Test fun blankInputDoesNotNavigate() {
        val vm = vm()
        vm.navigate("   ")
        assertThat(vm.activeTab.value.url).isNull()
    }

    // ---------- 建议 ----------

    @Test fun emptyQuerySuggestsBookmarksThenHistory() {
        val store = InMemoryBrowserStore(
            bookmarks = listOf(Bookmark("A 站", "https://a.com", 0)),
            history = listOf(HistoryEntry("B 站", "https://b.com", 0)),
        )
        val vm = BrowserViewModel(tabs = TabRepository(), store = store)
        val kinds = vm.suggestions.value.map { it.kind }
        assertThat(kinds).containsExactly(SuggestionKind.BOOKMARK, SuggestionKind.HISTORY).inOrder()
    }

    @Test fun urlLikeQueryOffersDirectUrl() {
        val vm = vm()
        vm.setQuery("a.com")
        assertThat(vm.suggestions.value.first().kind).isEqualTo(SuggestionKind.URL)
        assertThat(vm.suggestions.value.first().url).isEqualTo("https://a.com")
    }

    @Test fun textQueryOffersSearch() {
        val vm = vm()
        vm.setQuery("compose")
        assertThat(vm.suggestions.value.first().kind).isEqualTo(SuggestionKind.SEARCH)
    }

    @Test fun matchingBookmarksAndHistoryAppearInSuggestions() {
        val store = InMemoryBrowserStore(
            bookmarks = listOf(Bookmark("Compose 指南", "https://a.com/guide", 0)),
            history = listOf(HistoryEntry("Compose 发布", "https://b.com/post", 0)),
        )
        val vm = BrowserViewModel(tabs = TabRepository(), store = store)
        vm.setQuery("compose")
        val kinds = vm.suggestions.value.map { it.kind }
        assertThat(kinds).contains(SuggestionKind.BOOKMARK)
        assertThat(kinds).contains(SuggestionKind.HISTORY)
    }

    // ---------- 书签 ----------

    @Test fun toggleBookmarkAddsThenRemoves() {
        val vm = vm()
        vm.navigate("https://a.com")
        assertThat(vm.isBookmarked("https://a.com")).isFalse()
        vm.toggleBookmark()
        assertThat(vm.isBookmarked("https://a.com")).isTrue()
        vm.toggleBookmark()
        assertThat(vm.isBookmarked("https://a.com")).isFalse()
    }

    @Test fun toggleBookmarkOnBlankTabDoesNothing() {
        val vm = vm()
        vm.toggleBookmark()
        assertThat(vm.bookmarks.value).isEmpty()
    }

    // ---------- 历史 ----------

    /** navigate 与 onPageFinished 会为同一次访问各报一次，历史里只能留一条。 */
    @Test fun oneNavigationRecordsExactlyOneHistoryEntry() {
        val store = InMemoryBrowserStore()
        val vm = BrowserViewModel(tabs = TabRepository(), store = store)
        vm.navigate("https://a.com")
        val tabId = vm.activeTab.value.id
        vm.onPageFinished(tabId, "A 站", "https://a.com")
        assertThat(store.history.value).hasSize(1)
        // 后到的真实标题覆盖掉占位标题
        assertThat(store.history.value.first().title).isEqualTo("A 站")
    }

    @Test fun incognitoTabsAreNotRecordedInHistory() {
        val store = InMemoryBrowserStore()
        val vm = BrowserViewModel(tabs = TabRepository(), store = store)
        vm.newIncognitoTab()
        vm.navigate("https://secret.com")
        vm.onPageFinished(vm.activeTab.value.id, "秘密", "https://secret.com")
        assertThat(store.history.value).isEmpty()
    }

    /** 后台标签加载完成不能覆盖当前页的地址与标题。 */
    @Test fun backgroundTabFinishDoesNotTouchTheActiveTab() {
        val vm = vm()
        val first = vm.activeTab.value.id
        vm.navigate("https://a.com")
        val second = vm.newTab("https://b.com").id
        vm.onPageFinished(first, "A 站", "https://a.com/final")
        assertThat(vm.activeTab.value.id).isEqualTo(second)
        assertThat(vm.activeTab.value.url).isEqualTo("https://b.com")
        assertThat(vm.tabsState.value.first { it.id == first }.url).isEqualTo("https://a.com/final")
    }

    @Test fun onPageFinishedForUnknownTabIsIgnored() {
        val vm = vm()
        vm.onPageFinished("nope", "X", "https://x.com")
        assertThat(vm.activeTab.value.url).isNull()
    }

    // ---------- 页面状态 ----------

    @Test fun trackerCountIncrementsAndResetsOnNewPage() {
        val vm = vm()
        vm.onTrackerBlocked()
        vm.onTrackerBlocked()
        assertThat(vm.trackerCount.value).isEqualTo(2)
        vm.onPageStarted()
        assertThat(vm.trackerCount.value).isEqualTo(0)
    }

    @Test fun sslWarningIsRaisedAndCleared() {
        val vm = vm()
        vm.onSslError()
        assertThat(vm.sslWarning.value).isTrue()
        vm.onPageStarted()
        assertThat(vm.sslWarning.value).isFalse()
    }

    // ---------- 二级界面 ----------

    @Test fun openingSecondarySurfaceClosesTheMenu() {
        val vm = vm()
        vm.openMenu()
        vm.openSurface(BrowserSurface.Settings)
        assertThat(vm.menuOpen.value).isFalse()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Settings)
    }

    @Test fun backClosesSecondarySurfacesBeforePageBack() {
        for (target in listOf(
            BrowserSurface.Library, BrowserSurface.Downloads,
            BrowserSurface.Privacy, BrowserSurface.Reader, BrowserSurface.Settings,
        )) {
            val vm = vm()
            vm.openSurface(target)
            assertThat(vm.onBack(atHome = false, canGoBack = true)).isEqualTo(BackOutcome.CLOSE_PANEL)
            assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
        }
    }

    @Test fun messageIsConsumedOnce() {
        val vm = vm()
        assertThat(vm.message.value).isNull()
        vm.showMessage("剪贴板为空")
        assertThat(vm.message.value).isEqualTo("剪贴板为空")
        vm.consumeMessage()
        assertThat(vm.message.value).isNull()
    }

    @Test fun newIncognitoTabIsFlaggedAndClosesPanels() {
        val vm = vm()
        vm.openMenu()
        vm.newIncognitoTab()
        assertThat(vm.activeTab.value.isIncognito).isTrue()
        assertThat(vm.menuOpen.value).isFalse()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
    }
}

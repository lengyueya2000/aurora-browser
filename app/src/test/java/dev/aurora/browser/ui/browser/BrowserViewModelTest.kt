package dev.aurora.browser.ui.browser

import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.domain.BackOutcome
import dev.aurora.browser.domain.TabRepository
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
        assertThat(vm.onBack(canGoBack = false)).isEqualTo(BackOutcome.CLOSE_PANEL)
        assertThat(vm.menuOpen.value).isFalse()
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.TabSwitcher)
    }

    @Test fun backExitsOmniboxThenUsesPageBack() {
        val vm = vm()
        vm.openOmnibox()
        assertThat(vm.onBack(canGoBack = true)).isEqualTo(BackOutcome.EXIT_OMNIBOX)
        assertThat(vm.onBack(canGoBack = true)).isEqualTo(BackOutcome.PAGE_BACK)
    }

    @Test fun backFromTabSwitcherReturnsToPage() {
        val vm = vm()
        vm.openTabSwitcher()
        assertThat(vm.onBack(canGoBack = false)).isEqualTo(BackOutcome.CLOSE_PANEL)
        assertThat(vm.surface.value).isEqualTo(BrowserSurface.Page)
    }
}

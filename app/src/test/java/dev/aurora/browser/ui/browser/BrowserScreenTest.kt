package dev.aurora.browser.ui.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BrowserScreenTest {
    @get:Rule val compose = createComposeRule()

    private fun render(vm: BrowserViewModel) {
        compose.setContent { AuroraTheme { BrowserScreen(vm = vm) } }
    }

    @Test fun homeSurfaceShowsToolbarWithTabCount() {
        render(BrowserViewModel())
        compose.onNodeWithContentDescription("主页").assertExists()
        compose.onNodeWithContentDescription("标签页，1 个").assertExists()
    }

    @Test fun omniboxSurfaceHidesFloatingToolbar() {
        val vm = BrowserViewModel()
        render(vm)
        vm.openOmnibox()
        compose.onNodeWithContentDescription("地址栏输入").assertExists()
        compose.onNodeWithContentDescription("主页").assertDoesNotExist()
    }

    @Test fun tabSwitcherHidesFloatingToolbar() {
        val vm = BrowserViewModel()
        render(vm)
        vm.openTabSwitcher()
        compose.onNodeWithText("网格").assertExists()
        compose.onNodeWithContentDescription("主页").assertDoesNotExist()
    }

    @Test fun menuSheetOffersOnlyMilestoneTwoActions() {
        val vm = BrowserViewModel()
        render(vm)
        vm.openMenu()
        compose.onNodeWithText("新建标签页").assertExists()
        compose.onNodeWithText("刷新").assertExists()
        compose.onNodeWithText("关闭全部标签页").assertExists()
        compose.onNodeWithText("标签页数量").assertExists()
    }

    @Test fun menuSheetNewTabRowClosesTheSheetAndAddsATab() {
        val vm = BrowserViewModel()
        render(vm)
        vm.openMenu()
        compose.onNodeWithText("新建标签页").performClick()
        compose.onNodeWithText("新建标签页").assertDoesNotExist()
        assertThat(vm.tabsState.value).hasSize(2)
    }
}

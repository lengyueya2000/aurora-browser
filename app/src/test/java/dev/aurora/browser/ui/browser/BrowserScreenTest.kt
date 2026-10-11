package dev.aurora.browser.ui.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
        // 媒体组：视频下载与投屏
        compose.onNodeWithText("下载视频").assertExists()
        compose.onNodeWithText("投屏").assertExists()
    }

    @Test fun menuSheetNewTabRowClosesTheSheetAndAddsATab() {
        val vm = BrowserViewModel()
        render(vm)
        vm.openMenu()
        // 菜单加了「媒体」组之后整体变长，「标签页」那一组要滚一下才落进可点区域——
        // 直接点一个被裁到视口外的节点，事件落不到它身上。
        compose.onNodeWithText("新建标签页").performScrollTo().performClick()
        compose.onNodeWithText("新建标签页").assertDoesNotExist()
        assertThat(vm.tabsState.value).hasSize(2)
    }

    /**
     * FIX 1 的回归测试：Page 内容必须常驻，否则打开地址栏会把 WebView 移出组合，
     * 其 onDispose 销毁 WebView，回到网页态只能重新加载（丢滚动位置与前进/后退历史）。
     */
    @Test fun pageStaysComposedWhileOmniboxIsOpen() {
        val vm = BrowserViewModel()
        render(vm)
        vm.navigate("https://a.com")
        compose.waitForIdle()
        // 网页态：Page 分支的地址栏（OmniboxBar）在树中
        compose.onNodeWithContentDescription("地址栏，a.com").assertExists()

        vm.openOmnibox()
        compose.waitForIdle()
        // Page 层地址栏仍在（Page 分支未被移除）
        compose.onNodeWithContentDescription("地址栏，a.com").assertExists()
        // 地址栏覆盖层也在
        compose.onNodeWithContentDescription("地址栏输入").assertExists()
    }

    /**
     * 真机回归：星标此前按 `vm.isBookmarked()` 取值，而它内部读的是 `StateFlow.value`——
     * 组合期读不出订阅。收藏状态确实写进去了（书签库里看得到），但星标不会重绘，
     * 要等别的状态顺带触发重组才补上。在雷电模拟器上表现为「点完不变色，打开菜单后才变」。
     */
    @Test fun bookmarkStarUpdatesRightAfterToggling() {
        val vm = BrowserViewModel()
        render(vm)
        vm.navigate("https://a.com")
        compose.waitForIdle()

        compose.onNodeWithContentDescription("收藏").assertExists()
        compose.onNodeWithContentDescription("收藏").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("取消收藏").assertExists()

        compose.onNodeWithContentDescription("取消收藏").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("收藏").assertExists()
    }
}

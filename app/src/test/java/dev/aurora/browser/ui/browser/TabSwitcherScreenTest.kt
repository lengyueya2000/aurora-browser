package dev.aurora.browser.ui.browser

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.domain.model.BrowserTab
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabSwitcherScreenTest {
    @get:Rule val compose = createComposeRule()

    private val tabs = listOf(
        BrowserTab("a", "https://a.com", "A 站"),
        BrowserTab("b", "https://b.com", "B 站"),
        BrowserTab("c", null, "新标签页"),
    )

    private fun render(
        onSelect: (String) -> Unit = {}, onClose: (String) -> Unit = {},
        onNewTab: () -> Unit = {}, onCloseAll: () -> Unit = {}, onDone: () -> Unit = {},
    ) = compose.setContent {
        AuroraTheme {
            TabSwitcherScreen(
                tabs = tabs, activeId = "a",
                onSelect = onSelect, onClose = onClose,
                onNewTab = onNewTab, onCloseAll = onCloseAll, onDone = onDone,
            )
        }
    }

    @Test fun showsTabCountIncludingSingular() {
        render()
        compose.onNodeWithText("3").assertExists()
    }

    @Test fun rendersOneCloseButtonPerTab() {
        render()
        compose.onAllNodesWithContentDescription("关闭标签页").assertCountEquals(3)
    }

    @Test fun gridIsDefaultAndListToggleExists() {
        render()
        compose.onNodeWithText("网格").assertExists()
        compose.onNodeWithText("列表").assertExists()
    }

    @Test fun tappingClosePassesTheRightTabId() {
        var closed: String? = null
        render(onClose = { closed = it })
        compose.onAllNodesWithContentDescription("关闭标签页")[1].performClick()
        assertThat(closed).isEqualTo("b")
    }

    @Test fun tappingNewTabFiresCallback() {
        var n = 0
        render(onNewTab = { n++ })
        compose.onNodeWithContentDescription("新建标签页").performClick()
        assertThat(n).isEqualTo(1)
    }

    @Test fun closeAllFiresCallback() {
        var n = 0
        render(onCloseAll = { n++ })
        compose.onNodeWithContentDescription("关闭全部标签页").performClick()
        assertThat(n).isEqualTo(1)
    }

    // 卡片上的关闭按钮是独立可点目标，必须满足全局约束「最小触摸目标 48×48dp」。
    // 可见底片是 28dp，触摸区由外层 48dp 盒承载，这里量的就是它。
    @Test fun tabCloseButtonsMeetTouchTarget() {
        render()
        val h = compose.onAllNodesWithContentDescription("关闭标签页")[0]
            .getUnclippedBoundsInRoot().height
        assertThat(h.value).isAtLeast(48f)
    }
}

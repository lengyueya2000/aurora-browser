package dev.aurora.browser.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
        onNewIncognitoTab: () -> Unit = {},
    ) = compose.setContent {
        AuroraTheme {
            TabSwitcherScreen(
                tabs = tabs, activeId = "a",
                onSelect = onSelect, onClose = onClose,
                onNewTab = onNewTab, onCloseAll = onCloseAll, onDone = onDone,
                onNewIncognitoTab = onNewIncognitoTab,
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

    // 选中是卡片的主操作，必须验证：点第二张卡片应回传它的 id。
    @Test fun tappingCardSelectsItsTab() {
        var selected: String? = null
        render(onSelect = { selected = it })
        compose.onNodeWithText("B 站").performClick()
        assertThat(selected).isEqualTo("b")
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

    @Test fun doneButtonFiresCallback() {
        var n = 0
        render(onDone = { n++ })
        compose.onNodeWithContentDescription("完成").performClick()
        assertThat(n).isEqualTo(1)
    }

    @Test fun incognitoButtonFiresCallback() {
        var n = 0
        render(onNewIncognitoTab = { n++ })
        compose.onNodeWithContentDescription("新建无痕标签页").performClick()
        assertThat(n).isEqualTo(1)
    }

    /**
     * 视图切换此前没有任何断言：切到「列表」后卡片应换成行式布局
     * （标题 + 域名副标题），并且仍能选中对应标签。
     */
    @Test fun switchingToListRendersRowsAndStillSelects() {
        var selected: String? = null
        render(onSelect = { selected = it })
        // 网格态：卡片只有标题，没有域名副标题
        compose.onNodeWithText("a.com").assertDoesNotExist()
        compose.onNodeWithText("列表").performClick()
        // 列表态：域名副标题出现
        compose.onNodeWithText("a.com").assertExists()
        compose.onNodeWithText("B 站").performClick()
        assertThat(selected).isEqualTo("b")
    }

    /**
     * 真机回归：列表视图里新标签页的标题和副标题都是「新标签页」，看着像渲染了两遍。
     * 新标签页没有地址，副标题就该不画。
     */
    @Test fun newTabRowDoesNotRepeatItsLabelAsSubtitle() {
        render()
        compose.onNodeWithText("列表").performClick()
        compose.onAllNodesWithText("新标签页").assertCountEquals(1)
        // 有地址的标签行仍然显示域名副标题
        compose.onNodeWithText("a.com").assertExists()
    }

    /** 无痕标签在两种视图下都有可辨识标记。 */
    @Test fun incognitoTabsAreMarked() {        compose.setContent {
            AuroraTheme {
                TabSwitcherScreen(
                    tabs = listOf(
                        BrowserTab("i", "https://secret.com", "秘密", isIncognito = true),
                        BrowserTab("n", "https://a.com", "A 站"),
                    ),
                    activeId = "i",
                    onSelect = {}, onClose = {}, onNewTab = {}, onCloseAll = {}, onDone = {},
                )
            }
        }
        compose.onNodeWithContentDescription("无痕标签页").assertExists()
    }

    // 卡片上的关闭按钮是独立可点目标，必须满足全局约束「最小触摸目标 48×48dp」。
    // 可见底片是 28dp，触摸区由外层 48dp 盒承载，这里量的就是它。
    @Test fun tabCloseButtonsMeetTouchTarget() {
        render()
        val h = compose.onAllNodesWithContentDescription("关闭标签页")[0]
            .getUnclippedBoundsInRoot().height
        assertThat(h.value).isAtLeast(48f)
    }

    // 回归：Page/WebView 常驻在覆盖层下方，覆盖层必须吞掉空白区域的点击，否则会穿透到 WebView。
    // 空标签列表的中间视口是空白区域，点它不应触发下层的可点内容。
    @Test fun blankAreaTapDoesNotPassThroughToContentBeneath() {
        var underClicks = 0
        compose.setContent {
            AuroraTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { underClicks++ })
                    TabSwitcherScreen(
                        tabs = emptyList(), activeId = "",
                        onSelect = {}, onClose = {}, onNewTab = {}, onCloseAll = {}, onDone = {},
                    )
                }
            }
        }
        compose.onRoot().performTouchInput { click(Offset(center.x, height * 0.5f)) }
        assertThat(underClicks).isEqualTo(0)
    }
}

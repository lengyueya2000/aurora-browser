package dev.aurora.browser.ui.components

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PanelComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchTogglesAndExposesState() {
        var state = false
        compose.setContent {
            AuroraTheme { AuroraSwitch(checked = state, onCheckedChange = { state = it }, label = "始终使用 HTTPS") }
        }
        compose.onNodeWithText("始终使用 HTTPS").assertIsOff()
        compose.onNodeWithText("始终使用 HTTPS").performClick()
        assertThat(state).isTrue()
    }

    @Test fun switchReflectsCheckedState() {
        compose.setContent { AuroraTheme { AuroraSwitch(checked = true, onCheckedChange = {}, label = "追踪器拦截") } }
        compose.onNodeWithText("追踪器拦截").assertIsOn()
    }

    @Test fun settingRowRendersTitleSubtitleValueAndFiresClick() {
        var clicks = 0
        compose.setContent {
            AuroraTheme {
                SettingRow(title = "主题", subtitle = "跟随系统", value = "深色", onClick = { clicks++ })
            }
        }
        compose.onNodeWithText("主题").assertExists()
        compose.onNodeWithText("跟随系统").assertExists()
        compose.onNodeWithText("深色").assertExists()
        compose.onNodeWithText("主题").performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test fun segmentedControlSelectsOption() {
        var selected = "grid"
        compose.setContent {
            AuroraTheme {
                SegmentedControl(
                    options = listOf("grid" to "网格", "list" to "列表"),
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
        }
        compose.onNodeWithText("列表").performClick()
        assertThat(selected).isEqualTo("list")
    }

    @Test fun sheetDismissesOnScrimTap() {
        var dismissed = 0
        compose.setContent {
            AuroraTheme { AuroraBottomSheet(onDismiss = { dismissed++ }) { } }
        }
        compose.onNodeWithContentDescription("关闭面板").performClick()
        assertThat(dismissed).isEqualTo(1)
    }

    // 每个分段都是独立可点目标，必须满足全局约束「最小触摸目标 48×48dp」。
    @Test fun segmentedControlSegmentsMeetTouchTarget() {
        compose.setContent {
            AuroraTheme {
                SegmentedControl(
                    options = listOf("grid" to "网格", "list" to "列表"),
                    selected = "grid",
                    onSelect = {},
                )
            }
        }
        for (label in listOf("网格", "列表")) {
            val h = compose.onNodeWithText(label).getUnclippedBoundsInRoot().height
            assertThat(h.value).isAtLeast(48f)
        }
    }
}

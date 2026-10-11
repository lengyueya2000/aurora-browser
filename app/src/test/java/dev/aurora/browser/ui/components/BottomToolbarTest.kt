package dev.aurora.browser.ui.components

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomToolbarTest {
    @get:Rule val compose = createComposeRule()

    private fun render(
        tabCount: Int = 3,
        canGoBack: Boolean = true,
        onBack: () -> Unit = {},
        onTabs: () -> Unit = {},
    ) = compose.setContent {
        AuroraTheme {
            BottomToolbar(
                tabCount = tabCount, canGoBack = canGoBack,
                onBack = onBack, onForward = {}, onHome = {}, onTabs = onTabs, onMenu = {},
            )
        }
    }

    @Test fun rendersAllFiveSlotsWithAccessibleNames() {
        render()
        compose.onNodeWithContentDescription("后退").assertExists()
        compose.onNodeWithContentDescription("前进").assertExists()
        compose.onNodeWithContentDescription("主页").assertExists()
        compose.onNodeWithContentDescription("标签页，3 个").assertExists()
        compose.onNodeWithContentDescription("更多").assertExists()
    }

    @Test fun tabCountOver99CollapsesTo99Plus() {
        render(tabCount = 128)
        compose.onNodeWithContentDescription("标签页，99+ 个").assertExists()
    }

    @Test fun backDisabledWhenCannotGoBack() {
        render(canGoBack = false)
        compose.onNodeWithContentDescription("后退").assertIsNotEnabled()
    }

    @Test fun tappingTabsInvokesCallback() {
        var taps = 0
        render(onTabs = { taps++ })
        compose.onNodeWithContentDescription("标签页，3 个").performClick()
        assertThat(taps).isEqualTo(1)
    }

    @Test fun everySlotMeetsMinimumTouchTarget() {
        render()
        for (label in listOf("后退", "前进", "主页", "标签页，3 个", "更多")) {
            val b = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot()
            assertThat(b.width.value).isAtLeast(48f)
            assertThat(b.height.value).isAtLeast(48f)
        }
    }

    @Test fun tabSlotHasClickAction() {
        render()
        compose.onNodeWithContentDescription("标签页，3 个").assertHasClickAction()
    }
}

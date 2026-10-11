package dev.aurora.browser.ui.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OmniboxScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rendersSuggestionsAndQuickActions() {
        compose.setContent {
            AuroraTheme {
                OmniboxScreen(
                    query = "com",
                    suggestions = listOf("Compose 延迟布局", "Compose 转场"),
                    onQueryChange = {}, onSubmit = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithContentDescription("建议：Compose 延迟布局").assertExists()
        compose.onNodeWithContentDescription("扫码").assertExists()
        compose.onNodeWithContentDescription("语音").assertExists()
        compose.onNodeWithContentDescription("粘贴并前往").assertExists()
        compose.onNodeWithContentDescription("无痕").assertExists()
    }

    @Test fun typingReportsQueryChanges() {
        val typed = mutableListOf<String>()
        compose.setContent {
            AuroraTheme {
                OmniboxScreen("", emptyList(), onQueryChange = { typed += it }, onSubmit = {}, onDismiss = {})
            }
        }
        compose.onNodeWithContentDescription("地址栏输入").performTextInput("a")
        assertThat(typed).contains("a")
    }

    @Test fun tappingSuggestionSubmitsIt() {
        var submitted: String? = null
        compose.setContent {
            AuroraTheme {
                OmniboxScreen("c", listOf("Compose"), onQueryChange = {}, onSubmit = { submitted = it }, onDismiss = {})
            }
        }
        compose.onNodeWithContentDescription("建议：Compose").performClick()
        assertThat(submitted).isEqualTo("Compose")
    }

    @Test fun dismissButtonFires() {
        var n = 0
        compose.setContent {
            AuroraTheme { OmniboxScreen("", emptyList(), {}, {}, onDismiss = { n++ }) }
        }
        compose.onNodeWithContentDescription("关闭地址栏").performClick()
        assertThat(n).isEqualTo(1)
    }
}

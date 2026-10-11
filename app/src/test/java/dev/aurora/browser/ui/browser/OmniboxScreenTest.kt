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

    private val suggestions = listOf(
        OmniboxSuggestion("Compose 延迟布局", "https://www.bing.com/search?q=Compose", SuggestionKind.SEARCH),
        OmniboxSuggestion("Compose 转场", "https://www.bing.com/search?q=%E8%BD%AC%E5%9C%BA", SuggestionKind.SEARCH),
    )

    private fun render(
        query: String = "",
        items: List<OmniboxSuggestion> = emptyList(),
        onQueryChange: (String) -> Unit = {},
        onSubmit: (String) -> Unit = {},
        onPickSuggestion: (OmniboxSuggestion) -> Unit = {},
        onDismiss: () -> Unit = {},
        onPasteAndGo: () -> Unit = {},
        onVoice: () -> Unit = {},
        onScan: () -> Unit = {},
        onIncognito: () -> Unit = {},
    ) = compose.setContent {
        AuroraTheme {
            OmniboxScreen(
                query = query,
                suggestions = items,
                onQueryChange = onQueryChange,
                onSubmit = onSubmit,
                onPickSuggestion = onPickSuggestion,
                onDismiss = onDismiss,
                onPasteAndGo = onPasteAndGo,
                onVoice = onVoice,
                onScan = onScan,
                onIncognito = onIncognito,
            )
        }
    }

    @Test fun rendersSuggestionsAndQuickActions() {
        render(query = "com", items = suggestions)
        compose.onNodeWithContentDescription("建议：Compose 延迟布局").assertExists()
        compose.onNodeWithContentDescription("扫码").assertExists()
        compose.onNodeWithContentDescription("语音").assertExists()
        compose.onNodeWithContentDescription("粘贴并前往").assertExists()
        compose.onNodeWithContentDescription("无痕").assertExists()
    }

    /** 建议行显示类型标签，用户能分清「搜索」与「直连网址」。 */
    @Test fun suggestionRowsShowTheirKind() {
        render(
            query = "a.com",
            items = listOf(OmniboxSuggestion("a.com", "https://a.com", SuggestionKind.URL)),
        )
        compose.onNodeWithText("网址").assertExists()
    }

    @Test fun typingReportsQueryChanges() {
        val typed = mutableListOf<String>()
        render(onQueryChange = { typed += it })
        compose.onNodeWithContentDescription("地址栏输入").performTextInput("a")
        assertThat(typed).contains("a")
    }

    /**
     * 点建议行必须加载该行已经解析好的地址，而不是把标题当搜索词再搜一次——
     * 书签/历史建议的标题与地址是两回事。
     */
    @Test fun tappingSuggestionLoadsItsResolvedUrl() {
        var picked: OmniboxSuggestion? = null
        render(query = "c", items = listOf(suggestions[0]), onPickSuggestion = { picked = it })
        compose.onNodeWithContentDescription("建议：Compose 延迟布局").performClick()
        assertThat(picked?.url).isEqualTo("https://www.bing.com/search?q=Compose")
    }

    @Test fun dismissButtonFires() {
        var n = 0
        render(onDismiss = { n++ })
        compose.onNodeWithContentDescription("关闭地址栏").performClick()
        assertThat(n).isEqualTo(1)
    }

    // 快捷动作此前是空实现（点了没反应），这里逐个验证真的回传了事件。
    @Test fun quickActionsFireTheirCallbacks() {
        var paste = 0
        var voice = 0
        var scan = 0
        var incognito = 0
        render(
            onPasteAndGo = { paste++ }, onVoice = { voice++ },
            onScan = { scan++ }, onIncognito = { incognito++ },
        )
        compose.onNodeWithContentDescription("粘贴并前往").performClick()
        compose.onNodeWithContentDescription("语音").performClick()
        compose.onNodeWithContentDescription("扫码").performClick()
        compose.onNodeWithContentDescription("无痕").performClick()
        assertThat(listOf(paste, voice, scan, incognito)).containsExactly(1, 1, 1, 1)
    }
}

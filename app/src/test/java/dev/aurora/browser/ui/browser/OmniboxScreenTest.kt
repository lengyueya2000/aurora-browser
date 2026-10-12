package dev.aurora.browser.ui.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
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
     * 真机反馈：「点击输入框，可以自由编辑网址，而不是默认复制」。
     *
     * 两件事一起钉住：框里确实预填了当前网址（不然没得改），而且**整段选中**——
     * 敲一个字符应该是把它整段换掉，而不是接在末尾变成「https://a.comb」。
     */
    @Test fun prefilledUrlIsSelectedSoTypingReplacesIt() {
        val typed = mutableListOf<String>()
        render(query = "https://a.com", onQueryChange = { typed += it })

        compose.onNodeWithText("https://a.com").assertExists()
        compose.onNodeWithContentDescription("地址栏输入").performTextInput("b")

        assertThat(typed.last()).isEqualTo("b")
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

    /**
     * 真机回归（在雷电模拟器上发现的缺陷）：四个等宽快捷块里最长的是「粘贴并前往」，
     * 标签用默认的 bodyLarge(16sp) 时在窄屏上放不下，真机上被裁成了「粘贴并前」。
     *
     * 断言节点存在是拦不住的——文字节点一直在，只是画不出来。Compose 1.7 的语义树也
     * 不暴露 TextLayoutResult，所以这里改为直接验算布局预算：把生产代码里真实使用的
     * 字号和内边距取出来，检查最窄支持的屏幕宽度下每个块装得下 5 个汉字。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-mdpi")
    fun longestQuickActionLabelFitsOnTheNarrowestSupportedScreen() {
        var labelFontSize: TextUnit? = null
        compose.setContent {
            AuroraTheme { labelFontSize = quickActionLabelStyle().fontSize }
        }
        // mdpi 下 1sp == 1dp，直接把字号当汉字宽度用。
        val neededForFiveCjkGlyphs = labelFontSize!!.value * 5
        val available = quickActionLabelMaxWidth(320.dp).value
        assertThat(available).isAtLeast(neededForFiveCjkGlyphs)
    }
}

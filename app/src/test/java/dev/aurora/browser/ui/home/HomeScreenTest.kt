package dev.aurora.browser.ui.home

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
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val shortcuts = listOf(
        Shortcut("知乎", "https://zhihu.com"),
        Shortcut("哔哩哔哩", "https://bilibili.com"),
    )

    @Test fun rendersShortcutsAndSearchEntry() {
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = {}, onSearch = {}, shortcuts = shortcuts) }
        }
        compose.onNodeWithText("知乎").assertExists()
        compose.onNodeWithText("哔哩哔哩").assertExists()
        compose.onNodeWithContentDescription("搜索或输入网址").assertExists()
    }

    @Test fun tappingShortcutNavigatesToItsUrl() {
        var url: String? = null
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = { url = it }, onSearch = {}, shortcuts = shortcuts) }
        }
        compose.onNodeWithText("知乎").performClick()
        assertThat(url).isEqualTo("https://zhihu.com")
    }

    @Test fun tappingSearchBarOpensOmnibox() {
        var n = 0
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = {}, onSearch = { n++ }, shortcuts = shortcuts) }
        }
        compose.onNodeWithContentDescription("搜索或输入网址").performClick()
        assertThat(n).isEqualTo(1)
    }

    /**
     * 「为你推荐」信息流模块已整体移除：主页现在只有搜索入口 + 快捷入口。
     * 断言标题不在，同时断言下面的推荐卡片也不在（光删标题会留下悬空的卡片）。
     */
    @Test fun homeNoLongerShowsTheRecommendationFeed() {
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = {}, onSearch = {}, shortcuts = shortcuts) }
        }
        compose.onNodeWithText("为你推荐").assertDoesNotExist()
        compose.onNodeWithText("Compose 1.11 稳定版发布：延迟布局与共享元素转场").assertDoesNotExist()
        compose.onNodeWithText("快捷入口").assertExists()
    }
}

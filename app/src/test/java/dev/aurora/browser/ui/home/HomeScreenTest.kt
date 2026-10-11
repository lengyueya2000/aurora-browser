package dev.aurora.browser.ui.home

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.data.FaviconStore
import dev.aurora.browser.media.ResumeEntry
import dev.aurora.browser.ui.theme.AuroraTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeScreenTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

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

    // ---------- 继续观看 ----------

    private val resumable = listOf(
        ResumeEntry(
            url = "https://cdn.example.com/a/index.m3u8",
            title = "第 1 集",
            referer = "https://4kvm.net/play/abc",
            positionMs = 600_000L,
            durationMs = 3_600_000L,
            at = 2L,
        ),
        ResumeEntry(
            url = "https://cdn.example.com/b/index.m3u8",
            title = "第 2 集",
            referer = "https://olevod.com/player/1-2-1.html",
            positionMs = 300_000L,
            durationMs = 0L,
            at = 1L,
        ),
    )

    private fun content(
        onResume: (ResumeEntry) -> Unit = {},
        onForget: (ResumeEntry) -> Unit = {},
    ) {
        compose.setContent {
            AuroraTheme {
                HomeScreen(
                    onNavigate = {}, onSearch = {}, shortcuts = shortcuts,
                    resume = resumable, onResume = onResume, onForgetResume = onForget,
                )
            }
        }
    }

    @Test fun showsResumeCardsWithSourceAndRemainingTime() {
        content()
        compose.onNodeWithText("继续观看").assertExists()
        compose.onNodeWithText("第 1 集").assertExists()
        // 出处用来源页的域名，不是流的 CDN 域名——那个域名用户没见过。
        compose.onNodeWithText("4kvm.net · 还剩 50:00").assertExists()
    }

    /** 时长还不知道时说「看到 5:00」，不能算出一个假的剩余时间。 */
    @Test fun withoutADurationItReportsThePositionInstead() {
        content()
        compose.onNodeWithText("olevod.com · 看到 5:00").assertExists()
    }

    @Test fun tappingAResumeCardResumesThatEntry() {
        var picked: ResumeEntry? = null
        content(onResume = { picked = it })
        compose.onNodeWithText("第 2 集").performClick()
        assertThat(picked?.url).isEqualTo("https://cdn.example.com/b/index.m3u8")
    }

    @Test fun theRemoveButtonDropsTheEntryWithoutResumingIt() {
        var picked: ResumeEntry? = null
        var removed: ResumeEntry? = null
        content(onResume = { picked = it }, onForget = { removed = it })
        compose.onAllNodesWithContentDescription("从「继续观看」中移除")[0].performClick()
        assertThat(removed?.url).isEqualTo("https://cdn.example.com/a/index.m3u8")
        assertThat(picked).isNull()
    }

    /** 没有记录时整块（含标题）都不出现，不留一个空标题。 */
    @Test fun withoutResumeEntriesTheSectionIsHidden() {
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = {}, onSearch = {}, shortcuts = shortcuts) }
        }
        compose.onNodeWithText("继续观看").assertDoesNotExist()
    }

    /** 露太多会把快捷入口挤出屏幕。 */
    @Test fun onlyTheFirstFewResumeCardsAreShown() {
        val many = (1..6).map { resumable.first().copy(title = "第 $it 集", url = "https://c.com/$it") }
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = {}, onSearch = {}, shortcuts = shortcuts, resume = many) }
        }
        compose.onNodeWithText("第 4 集").assertExists()
        compose.onNodeWithText("第 5 集").assertDoesNotExist()
    }

    // ---------- 快捷入口的站点图标 ----------

    /** 取不到图标时画站名首字母——离线、站点没图标、图还没取回来都走这条路。 */
    @Test fun shortcutTilesFallBackToTheFirstLetter() {
        compose.setContent {
            AuroraTheme { HomeScreen(onNavigate = {}, onSearch = {}, shortcuts = shortcuts) }
        }
        compose.onNodeWithText("知").assertExists()
    }

    /**
     * 磁盘上已经有图标时，方块里换成图标本身（首字母不再画）。
     *
     * 图标是异步取的，所以这里先按真实路径把图放进磁盘缓存，再等界面把首字母换掉。
     */
    @Test fun shortcutTilesShowTheSiteIconWhenAvailable() {
        val dir = File(context.cacheDir, "favicons").apply { mkdirs() }
        val icon = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.RED)
        }
        File(dir, "zhihu.com.img").outputStream().use {
            icon.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        compose.setContent {
            AuroraTheme {
                HomeScreen(
                    onNavigate = {}, onSearch = {}, shortcuts = shortcuts,
                    favicons = FaviconStore(context),
                )
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("知").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("知").assertDoesNotExist()
        // 图标只是替掉方块里的首字母，标签文字还在。
        compose.onNodeWithText("知乎").assertExists()
    }
}

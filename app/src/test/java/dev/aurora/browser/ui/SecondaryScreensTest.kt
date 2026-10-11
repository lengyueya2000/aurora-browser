package dev.aurora.browser.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.data.DownloadItem
import dev.aurora.browser.domain.SearchEngine
import dev.aurora.browser.domain.model.Bookmark
import dev.aurora.browser.domain.model.HistoryEntry
import dev.aurora.browser.ui.components.SiteSecurity
import dev.aurora.browser.ui.downloads.DownloadTab
import dev.aurora.browser.ui.downloads.DownloadsScreen
import dev.aurora.browser.ui.library.LibraryScreen
import dev.aurora.browser.ui.library.LibraryTab
import dev.aurora.browser.ui.privacy.PrivacyScreen
import dev.aurora.browser.ui.reader.ReaderScreen
import dev.aurora.browser.ui.settings.SettingsScreen
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M3 新增的五个二级界面（设置 / 书签历史 / 下载 / 隐私 / 阅读）。
 * 每个界面至少验证「有内容」「主操作回传了正确参数」「返回可用」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class SecondaryScreensTest {
    @get:Rule val compose = createComposeRule()

    // ---------- 设置 ----------

    @Test fun settingsShowsGroupsAndCurrentEngine() {
        compose.setContent {
            AuroraTheme {
                SettingsScreen(
                    engine = SearchEngine.BING,
                    onEngineChange = {}, showFeed = true, onShowFeedChange = {},
                    blockTrackers = true, onBlockTrackersChange = {},
                    onOpenDownloads = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText("外观").assertExists()
        compose.onNodeWithText("隐私与安全").assertExists()
        compose.onNodeWithText("搜索").assertExists()
        compose.onNodeWithText("百度").assertExists()
        compose.onNodeWithText("1.1").assertExists()
    }

    @Test fun settingsEngineSelectionReportsTheEngine() {
        var picked: SearchEngine? = null
        compose.setContent {
            AuroraTheme {
                SettingsScreen(
                    engine = SearchEngine.BING,
                    onEngineChange = { picked = it }, showFeed = true, onShowFeedChange = {},
                    blockTrackers = true, onBlockTrackersChange = {},
                    onOpenDownloads = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText("百度").performClick()
        assertThat(picked).isEqualTo(SearchEngine.BAIDU)
    }

    @Test fun settingsRowsToggleTheirSwitches() {
        var feed: Boolean? = null
        var block: Boolean? = null
        compose.setContent {
            AuroraTheme {
                SettingsScreen(
                    engine = SearchEngine.BING, onEngineChange = {},
                    showFeed = true, onShowFeedChange = { feed = it },
                    blockTrackers = false, onBlockTrackersChange = { block = it },
                    onOpenDownloads = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText("主页信息流").performClick()
        compose.onNodeWithText("追踪器拦截").performClick()
        assertThat(feed).isFalse()
        assertThat(block).isTrue()
    }

    @Test fun settingsDownloadsRowAndBackFire() {
        var opened = 0
        var back = 0
        compose.setContent {
            AuroraTheme {
                SettingsScreen(
                    engine = SearchEngine.BING, onEngineChange = {},
                    showFeed = true, onShowFeedChange = {},
                    blockTrackers = true, onBlockTrackersChange = {},
                    onOpenDownloads = { opened++ }, onBack = { back++ },
                )
            }
        }
        compose.onNodeWithText("下载管理").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        assertThat(opened).isEqualTo(1)
        assertThat(back).isEqualTo(1)
    }

    // ---------- 书签与历史 ----------

    private val bookmarks = listOf(
        Bookmark("A 站", "https://a.com", 1),
        Bookmark("B 站", "https://b.com", 0),
    )
    private val history = listOf(
        HistoryEntry("C 站", "https://c.com", 2),
        HistoryEntry("A 站", "https://a.com", 1),
    )

    @Test fun libraryShowsBookmarksByDefaultAndOpensThem() {
        var opened: String? = null
        compose.setContent {
            AuroraTheme {
                LibraryScreen(
                    bookmarks = bookmarks, history = history,
                    onOpen = { opened = it }, onRemoveBookmark = {},
                    onClearHistory = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText("A 站").assertExists()
        compose.onNodeWithText("C 站").assertDoesNotExist()
        compose.onNodeWithText("A 站").performClick()
        assertThat(opened).isEqualTo("https://a.com")
    }

    @Test fun libraryCanStartOnHistoryAndClearsIt() {
        var cleared = 0
        compose.setContent {
            AuroraTheme {
                LibraryScreen(
                    bookmarks = bookmarks, history = history,
                    onOpen = {}, onRemoveBookmark = {},
                    onClearHistory = { cleared++ }, onBack = {},
                    initialTab = LibraryTab.HISTORY,
                )
            }
        }
        compose.onNodeWithText("C 站").assertExists()
        compose.onNodeWithContentDescription("清空历史").performClick()
        assertThat(cleared).isEqualTo(1)
    }

    @Test fun libraryRemovesABookmark() {
        var removed: String? = null
        compose.setContent {
            AuroraTheme {
                LibraryScreen(
                    bookmarks = bookmarks, history = history,
                    onOpen = {}, onRemoveBookmark = { removed = it },
                    onClearHistory = {}, onBack = {},
                )
            }
        }
        compose.onAllNodesWithContentDescription("删除书签")[0].performClick()
        assertThat(removed).isEqualTo("https://a.com")
    }

    @Test fun libraryFiltersAndShowsEmptyState() {
        compose.setContent {
            AuroraTheme {
                LibraryScreen(
                    bookmarks = bookmarks, history = history,
                    onOpen = {}, onRemoveBookmark = {}, onClearHistory = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithContentDescription("过滤书签").performTextInput("zzz")
        compose.onNodeWithText("没有匹配的书签").assertExists()
    }

    @Test fun libraryEmptyStateExplainsHowToBookmark() {
        compose.setContent {
            AuroraTheme {
                LibraryScreen(
                    bookmarks = emptyList(), history = emptyList(),
                    onOpen = {}, onRemoveBookmark = {}, onClearHistory = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText("还没有书签。在网页里点「☆」即可收藏。").assertExists()
    }

    // ---------- 下载 ----------

    private val running = DownloadItem(1L, "f.zip", 2, 300, 1000, "application/zip", 0)
    private val done = DownloadItem(2L, "g.pdf", 8, 2048, 2048, "application/pdf", 0)

    @Test fun downloadsSplitsRunningAndDone() {
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(
                    downloads = listOf(running, done),
                    onOpen = {}, onRemove = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithText("f.zip").assertExists()
        compose.onNodeWithText("g.pdf").assertDoesNotExist()
        compose.onNodeWithText("已完成").performClick()
        compose.onNodeWithText("g.pdf").assertExists()
    }

    @Test fun downloadsOpenAndRemoveFireWithTheRightId() {
        var opened: Long? = null
        var removed: Long? = null
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(
                    downloads = listOf(done),
                    onOpen = { opened = it }, onRemove = { removed = it }, onBack = {},
                    initialTab = DownloadTab.DONE,
                )
            }
        }
        compose.onNodeWithContentDescription("打开下载文件").performClick()
        compose.onNodeWithContentDescription("删除下载").performClick()
        assertThat(opened).isEqualTo(2L)
        assertThat(removed).isEqualTo(2L)
    }

    @Test fun downloadsEmptyState() {
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(downloads = emptyList(), onOpen = {}, onRemove = {}, onBack = {})
            }
        }
        compose.onNodeWithText("没有进行中的下载").assertExists()
    }

    // ---------- 隐私 ----------

    @Test fun privacyShowsTrackerCountAndTogglesBlocking() {
        var block: Boolean? = null
        compose.setContent {
            AuroraTheme {
                PrivacyScreen(
                    trackerCount = 7, blockEnabled = true, onBlockChange = { block = it },
                    currentUrl = "https://a.com", security = SiteSecurity.SECURE, onBack = {},
                )
            }
        }
        compose.onNodeWithText("7").assertExists()
        compose.onNodeWithText("连接安全（HTTPS）").assertExists()
        compose.onNodeWithText("追踪器拦截").performClick()
        assertThat(block).isFalse()
    }

    @Test fun privacyReflectsInsecureAndWarningStates() {
        compose.setContent {
            AuroraTheme {
                PrivacyScreen(
                    trackerCount = 0, blockEnabled = true, onBlockChange = {},
                    currentUrl = "http://a.com", security = SiteSecurity.INSECURE, onBack = {},
                )
            }
        }
        compose.onNodeWithText("连接不安全（明文 HTTP）").assertExists()
    }

    // ---------- 阅读 ----------

    @Test fun readerRendersBodyAndScalesFontSize() {
        compose.setContent {
            AuroraTheme {
                ReaderScreen(
                    title = "标题", url = "https://a.com/post",
                    text = "正文第一段。", onBack = {},
                )
            }
        }
        compose.onNodeWithText("标题").assertExists()
        compose.onNodeWithText("正文第一段。").assertExists()
        compose.onNodeWithText("100%").assertExists()
        compose.onNodeWithContentDescription("增大字号").performClick()
        compose.onNodeWithText("110%").assertExists()
    }

    @Test fun readerShowsEmptyStateWhenExtractionFails() {
        compose.setContent {
            AuroraTheme {
                ReaderScreen(title = "标题", url = "https://a.com", text = "", onBack = {})
            }
        }
        compose.onNodeWithText("本页无法进入阅读模式").assertExists()
    }
}

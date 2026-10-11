package dev.aurora.browser.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                    onEngineChange = {},
                    blockTrackers = true, onBlockTrackersChange = {},
                    onOpenDownloads = {}, onBack = {}, versionName = "9.9-test",
                )
            }
        }
        compose.onNodeWithText("外观").assertExists()
        compose.onNodeWithText("隐私与安全").assertExists()
        compose.onNodeWithText("搜索").assertExists()
        compose.onNodeWithText("百度").assertExists()
        compose.onNodeWithText("9.9-test").assertExists()
    }

    @Test fun settingsEngineSelectionReportsTheEngine() {
        var picked: SearchEngine? = null
        compose.setContent {
            AuroraTheme {
                SettingsScreen(
                    engine = SearchEngine.BING,
                    onEngineChange = { picked = it },
                    blockTrackers = true, onBlockTrackersChange = {},
                    onOpenDownloads = {}, onBack = {}, versionName = "9.9-test",
                )
            }
        }
        compose.onNodeWithText("百度").performClick()
        assertThat(picked).isEqualTo(SearchEngine.BAIDU)
    }

    @Test fun trackerSwitchRowToggles() {
        var block: Boolean? = null
        compose.setContent {
            AuroraTheme {
                SettingsScreen(
                    engine = SearchEngine.BING, onEngineChange = {},
                    blockTrackers = false, onBlockTrackersChange = { block = it },
                    onOpenDownloads = {}, onBack = {}, versionName = "9.9-test",
                )
            }
        }
        compose.onNodeWithText("追踪器拦截").performClick()
        assertThat(block).isTrue()
    }

    /**
     * 「为你推荐」信息流模块已整体移除，设置里的开关也必须一起消失——
     * 留一个点不动的开关比没有这个开关更糟。
     */
    @Test fun settingsNoLongerOffersTheRemovedHomeFeedToggle() {
        compose.setContent {
            AuroraTheme {
                SettingsScreen(
                    engine = SearchEngine.BING, onEngineChange = {},
                    blockTrackers = true, onBlockTrackersChange = {},
                    onOpenDownloads = {}, onBack = {}, versionName = "9.9-test",
                )
            }
        }
        compose.onNodeWithText("主页信息流").assertDoesNotExist()
    }

    @Test fun settingsDownloadsRowAndBackFire() {
        var opened = 0
        var back = 0
        compose.setContent {
            AuroraTheme {
                SettingsScreen(
                    engine = SearchEngine.BING, onEngineChange = {},
                    blockTrackers = true, onBlockTrackersChange = {},
                    onOpenDownloads = { opened++ }, onBack = { back++ }, versionName = "9.9-test",
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

    /**
     * 旧版本存下来的历史里同一地址可能有多条（那时只跟上一条比对去重）。
     * 列表的 key 用 URL，重复的 key 会让列表直接抛异常——这里必须只画一行。
     */
    @Test fun libraryCollapsesDuplicateHistoryUrls() {
        val duplicated = listOf(
            HistoryEntry("A 站", "https://a.com", 3),
            HistoryEntry("A 站", "https://a.com", 2),
            HistoryEntry("C 站", "https://c.com", 1),
        )
        compose.setContent {
            AuroraTheme {
                LibraryScreen(
                    bookmarks = emptyList(), history = duplicated,
                    onOpen = {}, onRemoveBookmark = {},
                    onClearHistory = {}, onBack = {},
                    initialTab = LibraryTab.HISTORY,
                )
            }
        }
        compose.onAllNodesWithText("A 站").assertCountEquals(1)
        compose.onNodeWithText("C 站").assertExists()
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

    // ---------- 页头固定（真机回归） ----------

    /**
     * 真机回归：设置与隐私两个界面此前把 `ScreenHeader` 放在了滚动容器**内**，
     * 往下滑页头就跟着滚走——而这两个界面没有悬浮工具栏，返回箭头是唯一的应用内
     * 返回入口，滚走之后用户只能靠系统返回键。原型里 `.topbar` 是 `flex:0 0 auto`，
     * 书签/下载/阅读三个界面也一直是页头固定，只有这两个漏了。
     *
     * 屏高取 640dp，保证正文确实比视口长；同时断言正文**确实滚了**，
     * 否则「页头没动」可能只是因为压根没滚动。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h640dp-xhdpi")
    fun settingsHeaderStaysPutWhileTheBodyScrolls() {
        compose.setContent {
            AuroraTheme {
                SettingsScreen(
                    engine = SearchEngine.BING,
                    onEngineChange = {},
                    blockTrackers = true, onBlockTrackersChange = {},
                    onOpenDownloads = {}, onBack = {}, versionName = "9.9-test",
                )
            }
        }
        assertHeaderStaysPut(firstBodyNode = "外观") {
            compose.onNodeWithText("项目主页").performScrollTo()
        }
    }

    // 隐私页正文只有 ~320dp，视口要压到 200dp 才会真的溢出。
    @Test
    @Config(sdk = [34], qualifiers = "w411dp-h200dp-xhdpi")
    fun privacyHeaderStaysPutWhileTheBodyScrolls() {
        compose.setContent {
            AuroraTheme {
                PrivacyScreen(
                    trackerCount = 3, blockEnabled = true, onBlockChange = {},
                    currentUrl = "https://a.com", security = SiteSecurity.SECURE, onBack = {},
                )
            }
        }
        assertHeaderStaysPut(firstBodyNode = "个追踪器已拦截（本页）") {
            compose.onNodeWithText("第三方 Cookie").performScrollTo()
        }
    }

    private fun assertHeaderStaysPut(firstBodyNode: String, scroll: () -> Unit) {
        fun topOf(text: String) = compose.onNodeWithText(text).getUnclippedBoundsInRoot().top.value
        val headerBefore = compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot().top.value
        val bodyBefore = topOf(firstBodyNode)

        scroll()

        assertThat(topOf(firstBodyNode)).isLessThan(bodyBefore)
        assertThat(compose.onNodeWithContentDescription("返回").getUnclippedBoundsInRoot().top.value)
            .isWithin(0.5f).of(headerBefore)
    }
}

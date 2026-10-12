package dev.aurora.browser.ui

import android.app.DownloadManager
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
import dev.aurora.browser.media.OfflineItem
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
                    desktopMode = false, onDesktopModeChange = {},
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
                    desktopMode = false, onDesktopModeChange = {},
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
                    desktopMode = false, onDesktopModeChange = {},
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
                    desktopMode = false, onDesktopModeChange = {},
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
                    desktopMode = false, onDesktopModeChange = {},
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
        compose.onNodeWithContentDescription("打开文件").performClick()
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

    /**
     * 离线缓存（Media3）和系统下载共用一张列表：站点只给 HLS 分片时没法存成单个文件，
     * 只能进离线缓存，用户看到的是同一处「已下载」。
     */
    @Test fun downloadsShowOfflineCacheRows() {
        val offline = OfflineItem(
            url = "https://c.com/v/master.m3u8",
            title = "离线电影",
            state = OfflineItem.State.COMPLETED,
            percent = 100f,
            bytes = 314_572_800L,
        )
        var opened: OfflineItem? = null
        var removed: OfflineItem? = null
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(
                    downloads = emptyList(),
                    onOpen = {}, onRemove = {}, onBack = {},
                    initialTab = DownloadTab.DONE,
                    offline = listOf(offline),
                    onOpenOffline = { opened = it },
                    onRemoveOffline = { removed = it },
                )
            }
        }
        compose.onNodeWithText("离线电影").assertExists()
        compose.onNodeWithContentDescription("离线播放").performClick()
        compose.onNodeWithContentDescription("删除下载").performClick()
        assertThat(opened?.url).isEqualTo("https://c.com/v/master.m3u8")
        assertThat(removed?.url).isEqualTo("https://c.com/v/master.m3u8")
    }

    /**
     * 下载中的那条：既要有「暂停」也要有「边下边看」。
     *
     * 边下边看只有离线缓存做得到——播放器读的是下载用的同一份分片缓存，已落盘的直接命中、
     * 没下到的当场补；直链下到一半的 mp4/zip 打不开，所以那条路不给这个按钮。
     */
    @Test fun aDownloadingStreamOffersPauseAndWatchWhileDownloading() {
        val offline = OfflineItem(
            url = "https://c.com/v/master.m3u8",
            title = "正在下的剧",
            state = OfflineItem.State.DOWNLOADING,
            percent = 12f,
            bytes = 40L * 1024 * 1024,
        )
        var paused: OfflineItem? = null
        var watched: OfflineItem? = null
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(
                    downloads = emptyList(),
                    onOpen = {}, onRemove = {}, onBack = {},
                    offline = listOf(offline),
                    onOpenOffline = { watched = it },
                    onRemoveOffline = {},
                    onPauseOffline = { paused = it },
                )
            }
        }
        compose.onNodeWithContentDescription("暂停下载").assertExists()
        compose.onNodeWithContentDescription("边下边看").assertExists()
        compose.onNodeWithContentDescription("暂停下载").performClick()
        compose.onNodeWithContentDescription("边下边看").performClick()
        assertThat(paused?.url).isEqualTo("https://c.com/v/master.m3u8")
        assertThat(watched?.url).isEqualTo("https://c.com/v/master.m3u8")
    }

    /**
     * 还没采到速度时那一行要写「测速中…」，不能把速度段留空。
     *
     * 离线缓存的已下载字节每 5 秒才落一次库，所以刚打开下载页必然还没有第二个样本。
     * 之前这一段是直接省掉的，用户看到的就是「这个页面又看不到下载速度了」。
     */
    @Test fun aRunningStreamShowsTheSpeedSlotWhileMeasuring() {
        val offline = OfflineItem(
            url = "https://c.com/v/master.m3u8",
            title = "刚开的剧",
            state = OfflineItem.State.DOWNLOADING,
            percent = 25f,
            bytes = 100L * 1024 * 1024,
            speed = 0L,
        )
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(
                    downloads = emptyList(),
                    onOpen = {}, onRemove = {}, onBack = {},
                    offline = listOf(offline),
                )
            }
        }
        compose.onNodeWithText("离线缓存 · 100.0 MB / 约 400.0 MB · 测速中…").assertExists()
    }

    /**
     * 字节数为 0 的「已完成」写「已缓存」，不写「0 B」。
     *
     * 同一条流再下一次时 Media3 复用同一份缓存、立刻标完成，而新记录里的字节数是 0。
     * 显示「离线缓存 · 0 B」看上去就是没下下来。
     */
    @Test fun aReusedCacheRowDoesNotClaimZeroBytes() {
        val offline = OfflineItem(
            url = "https://c.com/v/master.m3u8",
            title = "已经缓存过的剧",
            state = OfflineItem.State.COMPLETED,
            percent = 100f,
            bytes = 0L,
        )
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(
                    downloads = emptyList(),
                    onOpen = {}, onRemove = {}, onBack = {},
                    initialTab = DownloadTab.DONE,
                    offline = listOf(offline),
                )
            }
        }
        compose.onNodeWithText("离线缓存 · 已缓存").assertExists()
        compose.onNodeWithText("离线缓存 · 0 B").assertDoesNotExist()
    }

    /** 已暂停的那条：同一个位置换成「继续」，而且仍然可以边下边看。 */
    @Test fun aPausedStreamOffersResume() {
        val offline = OfflineItem(
            url = "https://c.com/v/master.m3u8",
            title = "暂停的剧",
            state = OfflineItem.State.STOPPED,
            percent = 12f,
            bytes = 40L * 1024 * 1024,
        )
        var resumed: OfflineItem? = null
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(
                    downloads = emptyList(),
                    onOpen = {}, onRemove = {}, onBack = {},
                    offline = listOf(offline),
                    onOpenOffline = {},
                    onRemoveOffline = {},
                    onResumeOffline = { resumed = it },
                )
            }
        }
        compose.onNodeWithText("离线缓存 · 已暂停 · 40.0 MB").assertExists()
        compose.onNodeWithContentDescription("继续下载").assertExists()
        compose.onNodeWithContentDescription("继续下载").performClick()
        assertThat(resumed?.url).isEqualTo("https://c.com/v/master.m3u8")
    }

    /**
     * 离线缓存的实际占用要显示出来。
     *
     * 这是「删除有没有真把数据删掉」在没 root 的手机上唯一能看见的证据：删一条，这个数变小。
     * 拿「所有条目的总大小」加出来是不算数的——那只是索引里的数字，缓存里有没有分片它不知道。
     */
    @Test fun theOfflineCacheFootprintIsShown() {
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(
                    downloads = emptyList(),
                    onOpen = {}, onRemove = {}, onBack = {},
                    cacheBytes = 519L * 1024 * 1024,
                )
            }
        }
        compose.onNodeWithText("离线缓存占用 519.0 MB").assertExists()
    }

    /** 一条缓存都没有时不占一行：空页面上写「占用 0 B」纯属噪音。 */
    @Test fun anEmptyCacheLeavesNoFootprintLine() {
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(downloads = emptyList(), onOpen = {}, onRemove = {}, onBack = {})
            }
        }
        compose.onNodeWithText("离线缓存占用 0 B").assertDoesNotExist()
    }

    /**
     * 系统下载器那条：下完了给「打开文件」，但**不给暂停按钮**。
     *
     * `DownloadManager` 的 `pauseDownload`/`resumeDownload` 是 @hide 的，公开 API 里没有，
     * 放一个按不动的按钮比不放更糟。
     */
    @Test fun aCompletedFileRowOffersOpenButNoPause() {
        val file = DownloadItem(
            id = 7L,
            title = "movie.mp4",
            status = DownloadManager.STATUS_SUCCESSFUL,
            bytesDownloaded = 4L * 1024 * 1024,
            totalBytes = 4L * 1024 * 1024,
            mimeType = "video/mp4",
            reason = 0,
        )
        compose.setContent {
            AuroraTheme {
                DownloadsScreen(
                    downloads = listOf(file),
                    onOpen = {}, onRemove = {}, onBack = {},
                    initialTab = DownloadTab.DONE,
                )
            }
        }
        compose.onNodeWithText("movie.mp4").assertExists()
        compose.onNodeWithContentDescription("打开文件").assertExists()
        compose.onNodeWithContentDescription("暂停下载").assertDoesNotExist()
        compose.onNodeWithContentDescription("继续下载").assertDoesNotExist()
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
                    desktopMode = false, onDesktopModeChange = {},
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

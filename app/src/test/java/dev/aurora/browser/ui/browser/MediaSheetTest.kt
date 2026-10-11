package dev.aurora.browser.ui.browser

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.AuroraTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 媒体面板（下载视频 / 投屏）的两个抽屉。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class MediaSheetTest {
    @get:Rule val compose = createComposeRule()

    private val sources = listOf(
        VideoSource("https://a.com/movie.mp4", "video/mp4", "movie.mp4"),
        VideoSource("blob:https://b.com/uuid", "", "流式视频"),
    )

    @Test fun videoSourceSheetListsEverySource() {
        compose.setContent {
            AuroraTheme {
                VideoSourceSheet(
                    title = "选择要下载的视频",
                    sources = sources,
                    icon = AuroraIcons.Download,
                    onPick = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("选择要下载的视频").assertExists()
        compose.onNodeWithText("movie.mp4").assertExists()
        compose.onNodeWithText("a.com · video/mp4").assertExists()
    }

    /**
     * 流式地址（blob/MSE）点不了：标成「不支持」，而不是给一个点了没反应的按钮。
     */
    @Test fun streamSourcesAreShownAsUnsupportedAndAreNotClickable() {
        var picked: VideoSource? = null
        compose.setContent {
            AuroraTheme {
                VideoSourceSheet(
                    title = "选择要下载的视频",
                    sources = sources,
                    icon = AuroraIcons.Download,
                    onPick = { picked = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("不支持").assertExists()
        compose.onNodeWithText("流式视频").performClick()
        assertThat(picked).isNull()
    }

    @Test fun pickingADownloadableSourceReportsIt() {
        var picked: VideoSource? = null
        compose.setContent {
            AuroraTheme {
                VideoSourceSheet(
                    title = "选择要下载的视频",
                    sources = sources,
                    icon = AuroraIcons.Download,
                    onPick = { picked = it },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("movie.mp4").performClick()
        assertThat(picked?.url).isEqualTo("https://a.com/movie.mp4")
    }

    @Test fun castOptionsOfferMirroringAndVideoCasting() {
        var mirror = 0
        var castVideo = 0
        compose.setContent {
            AuroraTheme {
                CastOptionsSheet(
                    onMirror = { mirror++ },
                    onCastVideo = { castVideo++ },
                    onDismiss = {},
                )
            }
        }
        compose.onNodeWithText("屏幕镜像").performClick()
        compose.onNodeWithText("投屏播放视频").performClick()
        assertThat(mirror).isEqualTo(1)
        assertThat(castVideo).isEqualTo(1)
    }
}

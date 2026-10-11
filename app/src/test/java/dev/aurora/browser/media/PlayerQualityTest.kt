package dev.aurora.browser.media

import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.media.PlayerQuality.VideoTrackInfo
import org.junit.Test

/**
 * 清晰度选项的整理。真实场景是 HLS 的 `master.m3u8` 里同一清晰度可能有两条轨道
 * （avc1 与 hevc），全列出来用户只会看到一排「1080P」。
 */
class PlayerQualityTest {

    private fun track(height: Int, bitrate: Int, group: Int = 0, index: Int = 0) =
        VideoTrackInfo(groupIndex = group, trackIndex = index, height = height, bitrate = bitrate)

    @Test fun labelsFollowWhatChinesePlayersShow() {
        assertThat(PlayerQuality.label(2160, 20_000_000)).isEqualTo("4K")
        assertThat(PlayerQuality.label(1440, 8_000_000)).isEqualTo("2K")
        assertThat(PlayerQuality.label(1080, 4_000_000)).isEqualTo("1080P")
        assertThat(PlayerQuality.label(720, 2_000_000)).isEqualTo("720P")
        assertThat(PlayerQuality.label(480, 1_000_000)).isEqualTo("480P")
        assertThat(PlayerQuality.label(360, 600_000)).isEqualTo("360P")
        // 非标准高度如实写出来，别硬套成上一档。
        assertThat(PlayerQuality.label(540, 1_200_000)).isEqualTo("540P")
    }

    @Test fun unknownHeightFallsBackToBitrate() {
        assertThat(PlayerQuality.label(0, 1_200_000)).isEqualTo("1.2 Mbps")
        assertThat(PlayerQuality.label(0, 0)).isEqualTo("未知清晰度")
    }

    @Test fun sameHeightIsDedupedKeepingTheHighestBitrate() {
        val options = PlayerQuality.options(
            listOf(
                track(height = 1080, bitrate = 3_000_000, index = 0),
                track(height = 1080, bitrate = 5_000_000, index = 1),
                track(height = 720, bitrate = 1_500_000, index = 2),
            ),
        )
        assertThat(options.map { it.label }).containsExactly("1080P", "720P").inOrder()
        // 留下的是码率更高的那条轨道，而不是先出现的那条。
        assertThat(options.first().trackIndex).isEqualTo(1)
    }

    @Test fun optionsAreSortedFromHighestToLowest() {
        val options = PlayerQuality.options(
            listOf(
                track(height = 360, bitrate = 500_000),
                track(height = 1080, bitrate = 4_000_000),
                track(height = 720, bitrate = 2_000_000),
            ),
        )
        assertThat(options.map { it.label }).containsExactly("1080P", "720P", "360P").inOrder()
    }

    /** 读不到高度的轨道排在最后，标签用码率——总比把它们丢掉好。 */
    @Test fun tracksWithoutHeightGoLast() {
        val options = PlayerQuality.options(
            listOf(
                track(height = 0, bitrate = 900_000),
                track(height = 720, bitrate = 2_000_000),
            ),
        )
        assertThat(options.map { it.label }).containsExactly("720P", "0.9 Mbps").inOrder()
    }

    @Test fun emptyInputGivesNoChoice() {
        assertThat(PlayerQuality.options(emptyList())).isEmpty()
    }

    @Test fun labelOfFindsTheSelectedTrack() {
        val options = PlayerQuality.options(
            listOf(
                track(height = 1080, bitrate = 4_000_000, group = 0, index = 0),
                track(height = 720, bitrate = 2_000_000, group = 0, index = 1),
            ),
        )
        assertThat(PlayerQuality.labelOf(options, groupIndex = 0, trackIndex = 1)).isEqualTo("720P")
        // 选了列表里没有的轨道（比如音频组）时给 null，调用方回落到「自动」。
        assertThat(PlayerQuality.labelOf(options, groupIndex = 1, trackIndex = 0)).isNull()
    }

    /** groupIndex 是「视频组」里的序号，所以多组视频也要各自对齐。 */
    @Test fun groupIndexIsCarriedThrough() {
        val options = PlayerQuality.options(
            listOf(
                track(height = 1080, bitrate = 4_000_000, group = 0, index = 0),
                track(height = 480, bitrate = 800_000, group = 1, index = 0),
            ),
        )
        assertThat(options.map { it.groupIndex }).containsExactly(0, 1).inOrder()
    }
}

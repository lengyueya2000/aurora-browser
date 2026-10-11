package dev.aurora.browser.media

/**
 * 清晰度选项的推导。输入是「从 ExoPlayer 的视频轨道里读出来的档位」，输出是给用户看的列表。
 *
 * HLS 的 `master.m3u8` 本来就带好几档码率，ExoPlayer 默认自动选——网差时它会自己降到
 * 低码率，这本身是对的，但用户看不到「现在是多少」也改不了，就会觉得「画质糊」。
 * 这里把档位整理成人类能读的标签，界面再决定要不要覆盖自动选择。
 *
 * 与 media3 解耦：只吃自己的 [VideoTrackInfo]，所以能在普通 JVM 测试里跑。
 */
object PlayerQuality {

    /**
     * 一档可选的视频轨道。
     *
     * [groupIndex] 是**视频组**里的序号（从 0 起，跳过音频与字幕组），不是所有轨道组的序号——
     * 界面侧就是按这个序号去取对应的 `TrackGroup` 来设覆盖的。
     */
    data class VideoTrackInfo(
        val groupIndex: Int,
        val trackIndex: Int,
        val height: Int,
        val bitrate: Int,
    )

    /** 一个选项。[label] 是「自动」或 `1080P` 这类。 */
    data class Option(val label: String, val groupIndex: Int, val trackIndex: Int)

    /** 「自动」不进列表：它是「不设覆盖」这个状态，界面单独给一行。 */
    const val AUTO_LABEL = "自动"

    /**
     * 按清晰度去重（同一高度只留码率最高的那档），从高到低排。
     *
     * 同一档位重复出现很常见：HLS 里同一清晰度可能有不同编码（avc1 / hevc）的两条轨道。
     * 全都列出来用户只会看到一排「1080P」。
     */
    fun options(tracks: List<VideoTrackInfo>): List<Option> {
        val known = tracks.filter { it.height > 0 }
            .groupBy { it.height }
            .map { (_, sameHeight) -> sameHeight.maxBy { it.bitrate } }
            .sortedByDescending { it.height }
        // 读不到高度的轨道（少见，但确实有）按码率排在最后，标签用码率。
        val unknown = tracks.filter { it.height <= 0 }.sortedByDescending { it.bitrate }
        return (known + unknown).map { Option(label(it.height, it.bitrate), it.groupIndex, it.trackIndex) }
    }

    /** 界面显示当前清晰度用：在选项里找与当前选中轨道对应的那个标签。 */
    fun labelOf(options: List<Option>, groupIndex: Int, trackIndex: Int): String? =
        options.firstOrNull { it.groupIndex == groupIndex && it.trackIndex == trackIndex }?.label

    /**
     * 清晰度标签。只给标准档位起「简称」，其余如实写高度——
     * 540 写成「480P」会让用户以为拿到的比实际更低。
     */
    fun label(height: Int, bitrate: Int): String = when {
        height >= 2160 -> "4K"
        height == 1440 -> "2K"
        height == 1080 -> "1080P"
        height == 720 -> "720P"
        height == 480 -> "480P"
        height == 360 -> "360P"
        height > 0 -> "${height}P"
        // 码率是 bit/s，不是 kb/s：写成 Mbps 要除以一百万。
        bitrate > 0 -> "%.1f Mbps".format(bitrate / 1_000_000f)
        else -> "未知清晰度"
    }
}

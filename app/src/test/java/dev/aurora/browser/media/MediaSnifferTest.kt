package dev.aurora.browser.media

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 流地址嗅探：分类与挑选。
 *
 * 这是「全屏 / 下载 / 投屏」在 MSE 站点上能不能用的关键——页面里的 `<video>` 只有
 * `blob:`，真实地址只能从网络层拿。分类错了，用户点「下载」就会得到一个几 KB 的
 * 播放列表文本，或者干脆什么都没有。
 */
class MediaSnifferTest {

    private val tab = "tab-1"

    /**
     * 嗅探器是单例，同一个 JVM 里所有测试方法共用它。每个方法前清一遍，
     * 否则上一条用例记下的地址会漏到下一条（forkEvery 只保证按类隔离，不按方法）。
     */
    @Before
    fun resetSniffer() {
        MediaSniffer.clear(tab)
        MediaSniffer.clear("a")
        MediaSniffer.clear("b")
    }

    @After
    fun cleanSniffer() {
        resetSniffer()
    }

    @Test fun playlistsAndDirectFilesAreClassified() {
        assertThat(MediaSniffer.kindOf("https://c.com/a/master.m3u8")).isEqualTo(MediaSniffer.Kind.PLAYLIST)
        assertThat(MediaSniffer.kindOf("https://c.com/a/index.mpd")).isEqualTo(MediaSniffer.Kind.PLAYLIST)
        assertThat(MediaSniffer.kindOf("https://c.com/a/v.mp4")).isEqualTo(MediaSniffer.Kind.FILE)
        assertThat(MediaSniffer.kindOf("https://c.com/a/v.flv")).isEqualTo(MediaSniffer.Kind.FILE)
        assertThat(MediaSniffer.kindOf("https://c.com/a/v.mkv")).isEqualTo(MediaSniffer.Kind.FILE)
    }

    /** 分片既不能单独播也不能单独下，记下来只会把播放列表挤出去。 */
    @Test fun segmentsAndNonMediaAreIgnored() {
        assertThat(MediaSniffer.kindOf("https://c.com/a/seg-1-v1-a1.ts")).isNull()
        assertThat(MediaSniffer.kindOf("https://c.com/a/init.m4s")).isNull()
        assertThat(MediaSniffer.kindOf("https://c.com/app.js")).isNull()
        assertThat(MediaSniffer.kindOf("https://c.com/style.css")).isNull()
        assertThat(MediaSniffer.kindOf("https://c.com/player/vod/1-82861-1.html")).isNull()
        assertThat(MediaSniffer.kindOf("https://c.com/api/play?id=1")).isNull()
    }

    /** 有的站点把播放列表藏在查询串里，路径上没有扩展名。 */
    @Test fun playlistInQueryStringIsStillRecognised() {
        assertThat(MediaSniffer.kindOf("https://c.com/play?url=/x/y.m3u8&t=1"))
            .isEqualTo(MediaSniffer.Kind.PLAYLIST)
    }

    /**
     * `.mpd` 藏在查询串里也要收——和 `MediaUrls.isStream` 是同一把尺子。
     *
     * 口径漂移过一次：嗅探收进来了，下载分流却判成直链，交给系统下载器只存下一个清单文本。
     */
    @Test fun dashInQueryStringIsStillAPlaylist() {
        val url = "https://c.com/play?url=/x/y.mpd&t=1"
        assertThat(MediaSniffer.kindOf(url)).isEqualTo(MediaSniffer.Kind.PLAYLIST)
        assertThat(MediaUrls.isStream(url)).isTrue()
    }

    /** 查询串里的扩展名不能当成路径扩展名。 */
    @Test fun extensionComesFromThePathOnly() {
        assertThat(MediaSniffer.extensionOf("https://c.com/a/v.mp4?sig=x.ts")).isEqualTo("mp4")
        assertThat(MediaSniffer.extensionOf("https://c.com/a/v")).isEmpty()
        assertThat(MediaSniffer.extensionOf("https://c.com/a/v.")).isEmpty()
    }

    /**
     * 欧乐影院的真实情形：先请求 `master.m3u8`，再请求它的变体播放列表。
     * 挑出来的必须是 master——它带多档清晰度，交给播放器才能自适应。
     */
    @Test fun masterPlaylistWinsOverItsVariants() {
        val base = "https://cloud.yuntongclouds.com/ts6/20261003/bMG1dGgo/mp4/bMG1dGgo.mp4"
        MediaSniffer.record(tab, "$base/master.m3u8")
        MediaSniffer.record(tab, "$base/index-v1-a1.m3u8")
        MediaSniffer.record(tab, "$base/seg-1-v1-a1.ts")

        assertThat(MediaSniffer.playable(tab)).isEqualTo("$base/master.m3u8")
    }

    /** 只有变体播放列表（没有 master）时也得挑得出来。 */
    @Test fun loneVariantPlaylistIsUsable() {
        MediaSniffer.record(tab, "https://c.com/v/index.m3u8")
        assertThat(MediaSniffer.playable(tab)).isEqualTo("https://c.com/v/index.m3u8")
    }

    /** 没有播放列表就退回直链文件。 */
    @Test fun directFileIsUsedWhenThereIsNoPlaylist() {
        MediaSniffer.record(tab, "https://c.com/v/a.mp4")
        assertThat(MediaSniffer.playable(tab)).isEqualTo("https://c.com/v/a.mp4")
        assertThat(MediaSniffer.directFile(tab)).isEqualTo("https://c.com/v/a.mp4")
    }

    /** 直链文件里挑最通用的那个：mp4 优先于 flv。 */
    @Test fun directFilePrefersMp4OverFlv() {
        MediaSniffer.record(tab, "https://c.com/v/a.flv")
        MediaSniffer.record(tab, "https://c.com/v/b.mp4")
        assertThat(MediaSniffer.directFile(tab)).isEqualTo("https://c.com/v/b.mp4")
    }

    /** HLS 站点没有直链，`directFile` 必须如实返回 null，而不是把播放列表当文件给出去。 */
    @Test fun playlistIsNotOfferedAsADirectFile() {
        MediaSniffer.record(tab, "https://c.com/v/master.m3u8")
        assertThat(MediaSniffer.directFile(tab)).isNull()
    }

    /** 同一个地址重复出现只记一条，且挪到队尾（站点当下正在用的那条）。 */
    @Test fun repeatedUrlIsDeduplicatedAndMovedToTheEnd() {
        MediaSniffer.record(tab, "https://c.com/v/a.mp4")
        MediaSniffer.record(tab, "https://c.com/v/b.mp4")
        MediaSniffer.record(tab, "https://c.com/v/a.mp4")
        assertThat(MediaSniffer.candidates(tab))
            .containsExactly("https://c.com/v/b.mp4", "https://c.com/v/a.mp4").inOrder()
    }

    /** 换页要清空，否则新页面上点「下载」会下到上一个视频的流。 */
    @Test fun clearForgetsTheTab() {
        MediaSniffer.record(tab, "https://c.com/v/a.mp4")
        MediaSniffer.clear(tab)
        assertThat(MediaSniffer.playable(tab)).isNull()
        assertThat(MediaSniffer.directFile(tab)).isNull()
    }

    /** 标签页之间不能串台。 */
    @Test fun tabsAreIsolated() {
        MediaSniffer.record("a", "https://c.com/v/a.mp4")
        assertThat(MediaSniffer.playable("b")).isNull()
        MediaSniffer.clear("a")
    }

    /** 长会话里列表不能无限涨。 */
    @Test fun historyIsBounded() {
        repeat(60) { MediaSniffer.record(tab, "https://c.com/v/$it.mp4") }
        assertThat(MediaSniffer.candidates(tab).size).isAtMost(24)
        // 最新的那条一定还在。
        assertThat(MediaSniffer.playable(tab)).isEqualTo("https://c.com/v/59.mp4")
        MediaSniffer.clear(tab)
    }
}

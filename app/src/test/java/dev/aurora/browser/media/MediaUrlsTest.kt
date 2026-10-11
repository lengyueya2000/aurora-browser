package dev.aurora.browser.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 「这个地址该当文件下，还是该交给离线缓存」的判断。判断错了代价很直接：
 * 把 `.m3u8` 交给系统下载器，用户只会拿到一个几 KB 的文本文件。
 */
class MediaUrlsTest {

    @Test fun playlistsAreRecognized() {
        assertThat(MediaUrls.isPlaylist("https://c.com/v/master.m3u8")).isTrue()
        assertThat(MediaUrls.isPlaylist("https://c.com/v/manifest.mpd")).isTrue()
        // CDN 常把签名参数挂在扩展名后面。
        assertThat(MediaUrls.isPlaylist("https://c.com/v/index.m3u8?auth=abc&t=1")).isTrue()
        // 大小写不固定。
        assertThat(MediaUrls.isPlaylist("https://c.com/v/MASTER.M3U8")).isTrue()
    }

    @Test fun plainFilesAreNotPlaylists() {
        assertThat(MediaUrls.isPlaylist("https://c.com/v/movie.mp4")).isFalse()
        assertThat(MediaUrls.isPlaylist("https://c.com/v/seg-1.ts")).isFalse()
        assertThat(MediaUrls.isPlaylist("https://c.com/v/clip.m4s")).isFalse()
        // 扩展名不是结尾就不能当播放列表，否则站点把 m3u8 当参数传也会被误判。
        assertThat(MediaUrls.isPlaylist("https://c.com/v/page?next=m3u8")).isFalse()
        assertThat(MediaUrls.isPlaylist("https://c.com/v/notes.m3u8.html")).isFalse()
    }

    /** 只有 HLS 需要显式建源（顺带打开 chunkless 预解析），DASH 走默认工厂。 */
    @Test fun onlyM3u8CountsAsHls() {
        assertThat(MediaUrls.isHls("https://c.com/v/master.m3u8")).isTrue()
        assertThat(MediaUrls.isHls("https://c.com/v/manifest.mpd")).isFalse()
        assertThat(MediaUrls.isHls("https://c.com/v/movie.mp4")).isFalse()
    }
}

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

    /**
     * 没有 `.m3u8` 后缀的播放列表也要认出来，口径必须和 `MediaSniffer` 收地址的尺子一致。
     *
     * 同类站点里 `/m3u8/<hash>`、`/play?url=xxx.m3u8` 这类写法很常见。嗅探认了、建源不认，
     * 结果就是「地址拿到了却一播就报容器不支持」——播放器那层的换源重试能兜住，
     * 但没必要先猜错一次。
     */
    @Test fun hlsWithoutTheExtensionStillCountsAsHls() {
        assertThat(MediaUrls.isHls("https://c.com/m3u8/a54f4c74d9a560d6e86f17c28ddd24cd")).isTrue()
        assertThat(MediaUrls.isHls("https://c.com/play?url=abc.m3u8&t=1")).isTrue()
        assertThat(MediaUrls.isHls("https://c.com/v/MASTER.M3U8")).isTrue()
    }

    /**
     * 下载分流用的是宽口径 [MediaUrls.isStream]。
     *
     * 拿窄口径（[MediaUrls.isPlaylist]）分流的话，嗅探回来的 `/m3u8/<hash>`、
     * `/play?url=xxx.m3u8` 会被当成直链交给系统下载器——用户点完「下载」拿到一个
     * 几 KB 的文本文件，视频一个字节都没有。
     */
    @Test fun theDownloadForkUsesTheWideYardstick() {
        assertThat(MediaUrls.isStream("https://c.com/v/master.m3u8")).isTrue()
        assertThat(MediaUrls.isStream("https://c.com/v/manifest.mpd")).isTrue()
        assertThat(MediaUrls.isStream("https://c.com/m3u8/a54f4c74d9a560d6e86f17c28ddd24cd"))
            .isTrue()
        assertThat(MediaUrls.isStream("https://c.com/play?url=abc.m3u8&t=1")).isTrue()

        assertThat(MediaUrls.isStream("https://c.com/v/movie.mp4")).isFalse()
        assertThat(MediaUrls.isStream("https://c.com/v/seg-1.ts")).isFalse()
    }
}

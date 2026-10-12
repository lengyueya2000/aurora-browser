package dev.aurora.browser.media

import android.app.DownloadManager
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.data.DownloadItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放器底部那条下载状态。
 *
 * 两种下载的来源完全不同（系统下载器 / Media3 离线缓存），但用户看到的只是一行字，
 * 所以这两种要归一到同一个说法上；差别只在「总量准不准」——直链准，HLS 只能反推，
 * 反推出来的带个「约」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadStatusTest {

    private fun file(
        status: Int,
        bytes: Long = 0L,
        total: Long = -1L,
        speed: Long = 0L,
    ) = DownloadItem(
        id = 1L,
        title = "movie.mp4",
        status = status,
        bytesDownloaded = bytes,
        totalBytes = total,
        mimeType = null,
        reason = 0,
        speed = speed,
    )

    private fun offline(
        state: OfflineItem.State,
        bytes: Long = 0L,
        percent: Float = 0f,
        speed: Long = 0L,
    ) = OfflineItem(
        url = "https://a.com/x.m3u8",
        title = "兰香如故 · 第 2 集",
        state = state,
        percent = percent,
        bytes = bytes,
        speed = speed,
    )

    // ---------- 文件下载 ----------

    @Test fun runningFileShowsSizeAndSpeed() {
        val status = fileDownloadStatus(
            file(DownloadManager.STATUS_RUNNING, bytes = 1_048_576L, total = 4_194_304L, speed = 524_288L),
        )
        assertThat(status.label).isEqualTo("正在下载 · 1.0 MB / 4.0 MB · 512.0 KB/s")
        assertThat(status.progress).isEqualTo(0.25f)
        assertThat(status.running).isTrue()
    }

    @Test fun aFileWithoutAKnownTotalFallsBackToPercent() {
        // 服务器没给 Content-Length：这时报百分比，报「0 B / 未知大小」看不出还剩多少。
        val status = fileDownloadStatus(
            file(DownloadManager.STATUS_RUNNING, bytes = 1_048_576L, total = -1L),
        )
        assertThat(status.label).isEqualTo("正在下载 · 1.0 MB")
        assertThat(status.progress).isNull()
    }

    @Test fun finishedFileReportsItsSizeOnly() {
        val status = fileDownloadStatus(
            file(DownloadManager.STATUS_SUCCESSFUL, bytes = 4_194_304L, total = 4_194_304L),
        )
        assertThat(status.label).isEqualTo("已下载 · 4.0 MB")
        assertThat(status.progress).isNull()
        assertThat(status.running).isFalse()
    }

    @Test fun failedFileSaysSo() {
        val status = fileDownloadStatus(file(DownloadManager.STATUS_FAILED, bytes = 1_000L))
        assertThat(status.label).isEqualTo("下载失败")
        assertThat(status.running).isFalse()
    }

    // ---------- 离线缓存 ----------

    @Test fun runningOfflineCacheEstimatesTheTotalWithAYue() {
        // HLS：下了 100 MB、已完成 25% → 总量约 400 MB。反推出来的数字必须带「约」。
        val status = offlineDownloadStatus(
            offline(
                OfflineItem.State.DOWNLOADING,
                bytes = 100L * 1024 * 1024,
                percent = 25f,
                speed = 2L * 1024 * 1024,
            ),
        )
        assertThat(status.label).isEqualTo("正在离线缓存 · 100.0 MB / 约 400.0 MB · 2.0 MB/s")
        assertThat(status.progress).isEqualTo(0.25f)
        assertThat(status.running).isTrue()
    }

    @Test fun aFreshOfflineCacheHasNoBarYet() {
        // 一个分片都没下完时比例是 0：画一条 0% 的条不如不画。
        val status = offlineDownloadStatus(
            offline(OfflineItem.State.DOWNLOADING, bytes = 512L * 1024, percent = 0f),
        )
        assertThat(status.label).isEqualTo("正在离线缓存 · 512.0 KB")
        assertThat(status.progress).isNull()
    }

    @Test fun finishedOfflineCacheReportsWhatItTook() {
        val status = offlineDownloadStatus(
            offline(OfflineItem.State.COMPLETED, bytes = 900L * 1024 * 1024, percent = 100f),
        )
        assertThat(status.label).isEqualTo("已离线缓存 · 900.0 MB")
        assertThat(status.running).isFalse()
    }

    @Test fun failedOfflineCacheSaysSo() {
        assertThat(offlineDownloadStatus(offline(OfflineItem.State.FAILED)).label)
            .isEqualTo("离线缓存失败")
    }

    @Test fun aStoppedOfflineCacheStillCountsAsRunning() {
        // 「已暂停」不是失败也不是完成：那一行要继续留着，用户回来还能接着下。
        val status = offlineDownloadStatus(
            offline(OfflineItem.State.STOPPED, bytes = 1_048_576L, percent = 10f),
        )
        assertThat(status.running).isTrue()
        assertThat(status.label).startsWith("正在离线缓存 · ")
    }
}

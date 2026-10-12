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
        // 速度还没采到（Media3 每 5 秒才落一次库）时写「测速中…」而不是把这一段省掉：
        // 省掉的话刚打开列表的几秒里速度字段是空的，看起来就是「速度显示坏了」。
        assertThat(status.label).isEqualTo("正在离线缓存 · 512.0 KB · 测速中…")
        assertThat(status.progress).isNull()
    }

    /** 采到速度之后「测速中…」要让位给真数字，不能两个都写。 */
    @Test fun aMeasuredSpeedReplacesTheMeasuringNote() {
        val status = offlineDownloadStatus(
            offline(
                OfflineItem.State.DOWNLOADING,
                bytes = 100L * 1024 * 1024,
                percent = 25f,
                speed = 1_048_576L,
            ),
        )
        assertThat(status.label).isEqualTo("正在离线缓存 · 100.0 MB / 约 400.0 MB · 1.0 MB/s")
    }

    /**
     * 字节数为 0 的「已完成」是「之前就缓存过」，不是失败。
     *
     * 同一条流再下一次时，Media3 会复用同一份缓存、立刻把新记录标成完成，而新记录里的
     * 字节数是 0。显示「已离线缓存 · 0 B」会让人以为没下下来。
     */
    @Test fun aReusedCacheEntryDoesNotClaimZeroBytes() {
        assertThat(offlineDownloadStatus(offline(OfflineItem.State.COMPLETED)).label)
            .isEqualTo("已离线缓存")
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

    @Test fun aPausedOfflineCacheSaysPausedAndStaysOnScreen() {
        // 暂停不是失败也不是完成：那一行要继续留着（`isRunning` 把 STOPPED 算在内，
        // 播放器靠它决定这条还在不在），但文案得是「已暂停」——用户刚从下载页按了暂停，
        // 回到播放器还看到「正在离线缓存」就说不通了。
        val item = offline(OfflineItem.State.STOPPED, bytes = 1_048_576L, percent = 10f)
        val status = offlineDownloadStatus(item)
        assertThat(status.label).isEqualTo("已暂停 · 1.0 MB")
        assertThat(status.running).isFalse() // 不再亮色，但它还在列表里
        assertThat(item.isRunning).isTrue()
        assertThat(item.isPaused).isTrue()
    }

    @Test fun aRunningOfflineCacheIsNotPaused() {
        // 反向锁一下：正在下的不能被当成暂停——判反了会把「暂停」按钮画成「继续」。
        val item = offline(OfflineItem.State.DOWNLOADING, bytes = 512L * 1024, percent = 5f)
        assertThat(item.isActive).isTrue()
        assertThat(item.isPaused).isFalse()
        assertThat(offlineDownloadStatus(item).label).startsWith("正在离线缓存 · ")
    }

    @Test fun aPausedFileDownloadSaysPausedToo() {
        // 系统下载器自己会因为「等网络」把一条挂起；那时报「正在下载」同样是骗人的。
        val item = file(DownloadManager.STATUS_PAUSED, bytes = 2_097_152L, total = 8_388_608L)
        val status = fileDownloadStatus(item)
        assertThat(status.label).isEqualTo("已暂停 · 2.0 MB")
        assertThat(status.running).isFalse()
        assertThat(item.isRunning).isTrue() // 仍算「没结束」，播放器那条不会消失
    }

    // ---------- 边下边看 ----------

    /**
     * 没下完也能看。
     *
     * 播放器读的是下载用的同一份 `SimpleCache`：已落盘的分片直接命中、没下到的当场从网络补，
     * 所以「下到一半」「已暂停」「下完了」这三种都能点开。
     */
    @Test fun aHalfDownloadedStreamIsAlreadyPlayable() {
        assertThat(offline(OfflineItem.State.DOWNLOADING, bytes = 1024L, percent = 3f).isPlayable)
            .isTrue()
        assertThat(offline(OfflineItem.State.STOPPED, bytes = 1024L, percent = 3f).isPlayable)
            .isTrue()
        assertThat(offline(OfflineItem.State.COMPLETED, bytes = 1024L, percent = 100f).isPlayable)
            .isTrue()
    }

    @Test fun aFailedStreamIsNotPlayable() {
        // 失败的那条缓存可能只剩一个残缺索引，点开只会一直转圈——不给播放按钮。
        assertThat(offline(OfflineItem.State.FAILED).isPlayable).isFalse()
    }
}

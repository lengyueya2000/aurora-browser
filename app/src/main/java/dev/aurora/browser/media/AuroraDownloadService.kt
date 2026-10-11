package dev.aurora.browser.media

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import dev.aurora.browser.R

/**
 * 撑住离线下载的前台服务。
 *
 * 分片下载是长时间任务，用户切走应用后没有前台服务就会被系统连任务一起收走——
 * 表现是「点了下载，回来看进度卡在一半」。下载本身仍由 [OfflineMedia] 的
 * `DownloadManager` 做，这里只负责让它活到下载完。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AuroraDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    /* foregroundNotificationUpdateInterval= */ 1000L,
    CHANNEL_ID,
    R.string.offline_channel_name,
    R.string.offline_channel_description,
) {

    override fun getDownloadManager(): DownloadManager = OfflineMedia.downloadManager(this)

    /** 不做「等有 WiFi 再下」这类调度：用户点了就现在下。 */
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification = DownloadNotificationHelper(this, CHANNEL_ID).buildProgressNotification(
        this,
        android.R.drawable.stat_sys_download,
        /* contentIntent= */ null,
        /* message= */ null,
        downloads,
        notMetRequirements,
    )

    companion object {
        const val CHANNEL_ID = "aurora_offline_downloads"
        private const val FOREGROUND_NOTIFICATION_ID = 4201
    }
}

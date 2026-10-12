package dev.aurora.browser.media

import dev.aurora.browser.data.DownloadItem
import dev.aurora.browser.data.DownloadsRepository

/**
 * 播放器底部那条下载状态。
 *
 * 为什么要在播放器里再显示一遍：点了「下载」之后用户人还在播放器里，而下载管理是另一个
 * 界面——不看一眼列表就完全不知道下得怎么样、快不快。这一行就是把那一眼搬过来。
 *
 * @param label 直接可以显示的一行字，含动词（「正在下载」「已离线缓存」）。
 * @param progress 0f..1f；`null` 表示画不出进度条（总量还不知道，或者已经下完）。
 * @param running 还在下。界面靠它决定用亮色还是灰色。
 */
data class DownloadStatus(
    val label: String,
    val progress: Float?,
    val running: Boolean,
)

/**
 * 文件下载（系统 `DownloadManager`）的那一行。
 *
 * 直链的总长度是服务器给的，准确，所以不带「约」。
 */
fun fileDownloadStatus(item: DownloadItem): DownloadStatus = DownloadStatus(
    label = when {
        item.isSuccess ->
            "已下载 · " + DownloadsRepository.formatSize(
                item.totalBytes.takeIf { it > 0 } ?: item.bytesDownloaded,
            )
        item.isRunning ->
            "正在下载 · " + DownloadsRepository.formatProgress(
                downloaded = item.bytesDownloaded,
                total = item.totalBytes,
                percent = item.progress * 100f,
                speed = item.speed,
                approxTotal = false,
            )
        else -> "下载失败"
    },
    progress = if (item.isRunning && item.totalBytes > 0) item.progress else null,
    running = item.isRunning,
)

/**
 * 离线缓存（Media3）的那一行。
 *
 * 总长度 Media3 给不出来（见 [OfflineItem.totalBytes]），是从已完成比例反推的，所以带「约」。
 */
fun offlineDownloadStatus(item: OfflineItem): DownloadStatus = DownloadStatus(
    label = when {
        item.isCompleted -> "已离线缓存 · ${DownloadsRepository.formatSize(item.bytes)}"
        item.isFailed -> "离线缓存失败"
        else -> "正在离线缓存 · " + DownloadsRepository.formatProgress(
            downloaded = item.bytes,
            total = item.totalBytes,
            percent = item.percent,
            speed = item.speed,
            approxTotal = true,
        )
    },
    // 分片是一段一段完成的，比例条按分片数走；一个分片都没下完时不画。
    progress = if (item.isRunning && item.percent > 0f) item.progress else null,
    running = item.isRunning,
)

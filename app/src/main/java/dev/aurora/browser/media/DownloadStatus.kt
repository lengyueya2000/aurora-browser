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
        // 系统自己会把下载挂起（等网络、等重试）。这时还说「正在下载」是骗人的。
        item.isPaused -> "已暂停 · " + DownloadsRepository.formatSize(item.bytesDownloaded)
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
    // 挂起中不算「亮色进行中」，但那一行要留着（`isRunning` 把 PAUSED 也算在内）。
    running = item.isActive,
)

/**
 * 离线缓存（Media3）的那一行。
 *
 * 总长度 Media3 给不出来（见 [OfflineItem.totalBytes]），是从已完成比例反推的，所以带「约」。
 */
fun offlineDownloadStatus(item: OfflineItem): DownloadStatus = DownloadStatus(
    label = when {
        // 字节数为 0 的「已完成」是「之前就缓存过、Media3 复用了同一份缓存」，不是失败。
        item.isCompleted ->
            if (item.bytes > 0) "已离线缓存 · ${DownloadsRepository.formatSize(item.bytes)}"
            else "已离线缓存"
        item.isFailed -> "离线缓存失败"
        // 用户从下载页按了暂停，回到播放器还看到「正在离线缓存」就说不通了。
        item.isPaused -> "已暂停 · " + DownloadsRepository.formatSize(item.bytes)
        else -> "正在离线缓存 · " + DownloadsRepository.formatProgress(
            downloaded = item.bytes,
            total = item.totalBytes,
            percent = item.percent,
            speed = item.speed,
            approxTotal = true,
        ) + DownloadsRepository.formatSpeedNote(item.speed)
    },
    // 分片是一段一段完成的，比例条按分片数走；一个分片都没下完时不画。
    progress = if (item.isRunning && item.percent > 0f) item.progress else null,
    // 暂停了就不再是「亮色进行中」，但那一行要留着（`isRunning` 把 STOPPED 也算在内）。
    running = item.isActive,
)

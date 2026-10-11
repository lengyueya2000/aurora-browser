package dev.aurora.browser.data

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import dev.aurora.browser.ui.browser.DownloadRequest

/** 一条下载记录。`status` 是 `DownloadManager.STATUS_*`。 */
data class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val mimeType: String?,
    val reason: Int,
) {
    val isRunning: Boolean get() = status == DownloadManager.STATUS_RUNNING ||
        status == DownloadManager.STATUS_PENDING || status == DownloadManager.STATUS_PAUSED
    val isSuccess: Boolean get() = status == DownloadManager.STATUS_SUCCESSFUL

    /** 0f..1f；总长度未知时返回 0。 */
    val progress: Float
        get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * 下载管理（规格 5.8）。交给系统 `DownloadManager`：后台下载、断点续传、通知栏进度
 * 都由它负责，不必自己写前台服务。
 */
class DownloadsRepository(private val context: Context) {

    private val manager: DownloadManager?
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    /** 入队。返回下载 id；系统服务不可用时返回 null。 */
    fun enqueue(request: DownloadRequest, referer: String? = null): Long? {
        val dm = manager ?: return null
        val fileName = deriveFileName(request.url, request.contentDisposition, request.mimeType)
        val target = Uri.parse(request.url)
        val req = DownloadManager.Request(target).apply {
            setTitle(fileName)
            setMimeType(request.mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 落到公共「下载」目录，用户能在文件管理器里找到。
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
            referer?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Referer", it) }
        }
        return runCatching { dm.enqueue(req) }.getOrNull()
    }

    fun query(): List<DownloadItem> {
        val dm = manager ?: return emptyList()
        val cursor: Cursor = runCatching {
            dm.query(DownloadManager.Query())
        }.getOrNull() ?: return emptyList()
        return cursor.use { c ->
            val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val soFarCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val mimeCol = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            val reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON)
            buildList {
                while (c.moveToNext()) {
                    add(
                        DownloadItem(
                            id = if (idCol >= 0) c.getLong(idCol) else 0L,
                            title = if (titleCol >= 0) c.getString(titleCol).orEmpty() else "",
                            status = if (statusCol >= 0) c.getInt(statusCol) else 0,
                            bytesDownloaded = if (soFarCol >= 0) c.getLong(soFarCol) else 0L,
                            totalBytes = if (totalCol >= 0) c.getLong(totalCol) else -1L,
                            mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null,
                            reason = if (reasonCol >= 0) c.getInt(reasonCol) else 0,
                        ),
                    )
                }
            }.sortedByDescending { it.id }
        }
    }

    fun remove(id: Long) {
        runCatching { manager?.remove(id) }
    }

    /** 已下载文件的 content:// 地址，可直接交给 ACTION_VIEW。 */
    fun contentUri(id: Long): Uri? = runCatching { manager?.getUriForDownloadedFile(id) }.getOrNull()

    companion object {
        /** 从 Content-Disposition / MIME 推断文件名，兜底用 URL 末段。 */
        fun deriveFileName(url: String, contentDisposition: String?, mimeType: String?): String =
            URLUtil.guessFileName(url, contentDisposition, mimeType)

        /** 人类可读的体积，用于列表展示。 */
        fun formatSize(bytes: Long): String = when {
            bytes < 0 -> "未知大小"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}

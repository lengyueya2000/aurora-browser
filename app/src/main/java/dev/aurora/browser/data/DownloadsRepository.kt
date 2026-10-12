package dev.aurora.browser.data

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.webkit.URLUtil

/** 交给 `DownloadManager` 的一次下载请求。 */
data class DownloadRequest(
    val url: String,
    val userAgent: String?,
    val contentDisposition: String?,
    val mimeType: String?,
    val contentLength: Long,
)

/** 一条下载记录。`status` 是 `DownloadManager.STATUS_*`。 */
data class DownloadItem(
    val id: Long,
    val title: String,
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val mimeType: String?,
    val reason: Int,
    /** 字节/秒。0 表示「还不知道」（刚入队，或者已经停下）。 */
    val speed: Long = 0L,
    /**
     * 这条下的是哪个地址。
     *
     * 播放器要拿它认领「正在播的这个视频的下载」：列表里只有标题，而标题是文件名，
     * 跟页面上的地址对不上。
     */
    val uri: String? = null,
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

    /**
     * 速度计。系统下载器只给「已下载多少字节」，速度得自己按两次采样的差算，所以这个
     * 实例必须活过单次 [query] 调用。
     */
    private val speed = DownloadSpeed()

    /**
     * 入队。返回下载 id；地址不可下载或系统服务不可用时返回 null。
     *
     * 整个方法体都在 `runCatching` 里，包括 `DownloadManager.Request` 的**构造**：它的
     * 构造函数就会对非 http/https 地址抛 `IllegalArgumentException`（`Can only download
     * HTTP/HTTPS URIs: blob:...`）。只把 `enqueue` 包起来是不够的——视频站的 MSE 播放器
     * 给出的正是 `blob:` 地址，真机上点「下载视频」会直接闪退。
     */
    fun enqueue(request: DownloadRequest, referer: String? = null): Long? = runCatching {
        val dm = manager ?: return@runCatching null
        val target = Uri.parse(request.url)
        if (target.scheme?.lowercase() !in DOWNLOADABLE_SCHEMES) return@runCatching null
        val fileName = deriveFileName(request.url, request.contentDisposition, request.mimeType)
        val req = DownloadManager.Request(target).apply {
            setTitle(fileName)
            setMimeType(request.mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // 落到公共「下载」目录，用户能在文件管理器里找到。
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            request.userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
            referer?.takeIf { it.isNotBlank() }?.let { addRequestHeader("Referer", it) }
        }
        dm.enqueue(req)
    }.getOrNull()

    /** 这个地址能不能交给 `DownloadManager`（它只认 http/https/ftp）。 */
    fun canDownload(url: String): Boolean =
        runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull() in DOWNLOADABLE_SCHEMES

    fun query(): List<DownloadItem> {
        val dm = manager ?: return emptyList()
        val cursor: Cursor = runCatching {
            dm.query(DownloadManager.Query())
        }.getOrNull() ?: return emptyList()
        val now = SystemClock.elapsedRealtime()
        return cursor.use { c ->
            val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val soFarCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val mimeCol = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
            val reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON)
            val uriCol = c.getColumnIndex(DownloadManager.COLUMN_URI)
            buildList {
                while (c.moveToNext()) {
                    val id = if (idCol >= 0) c.getLong(idCol) else 0L
                    val soFar = if (soFarCol >= 0) c.getLong(soFarCol) else 0L
                    add(
                        DownloadItem(
                            id = id,
                            title = if (titleCol >= 0) c.getString(titleCol).orEmpty() else "",
                            status = if (statusCol >= 0) c.getInt(statusCol) else 0,
                            bytesDownloaded = soFar,
                            totalBytes = if (totalCol >= 0) c.getLong(totalCol) else -1L,
                            mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null,
                            reason = if (reasonCol >= 0) c.getInt(reasonCol) else 0,
                            // 每次查询都记一次采样：两次查询之间隔了多久，速度就按多久算。
                            speed = speed.sample("file-$id", soFar, now),
                            uri = if (uriCol >= 0) c.getString(uriCol) else null,
                        ),
                    )
                }
            }
                .sortedByDescending { it.id }
                .also { list -> speed.retain(list.mapTo(mutableSetOf()) { "file-${it.id}" }) }
        }
    }

    fun remove(id: Long) {
        runCatching { manager?.remove(id) }
    }

    /** 已下载文件的 content:// 地址，可直接交给 ACTION_VIEW。 */
    fun contentUri(id: Long): Uri? = runCatching { manager?.getUriForDownloadedFile(id) }.getOrNull()

    companion object {
        /** `DownloadManager` 只接受这三种 scheme。 */
        val DOWNLOADABLE_SCHEMES = setOf("http", "https", "ftp")

        /** 从 Content-Disposition / MIME 推断文件名，兜底用 URL 末段。 */
        fun deriveFileName(url: String, contentDisposition: String?, mimeType: String?): String =
            URLUtil.guessFileName(url, contentDisposition, mimeType)?.takeIf { it.isNotBlank() }
                ?: "download"

        /**
         * 「已下载 / 总大小 · 速度」。
         *
         * 总大小和速度都可能是「还不知道」：HLS 的总长度要下完才知道，这里是从已完成比例
         * 反推的，所以带个「约」；速度要两次采样才算得出来。不知道的那段整段省掉——写
         * 「0 B/s」看起来像卡住了，其实只是还没测出来。总大小还估不出来时改报百分比，
         * 否则看不出还剩多少。
         *
         * @param approxTotal 总大小是不是反推出来的（HLS 是，直链不是）。
         */
        fun formatProgress(
            downloaded: Long,
            total: Long,
            percent: Float,
            speed: Long,
            approxTotal: Boolean,
        ): String {
            val size = if (total > 0) {
                val prefix = if (approxTotal) "约 " else ""
                "${formatSize(downloaded)} / $prefix${formatSize(total)}"
            } else {
                val pct = if (percent > 0f && percent < 100f) " · ${percent.toInt()}%" else ""
                formatSize(downloaded) + pct
            }
            return if (speed > 0) "$size · ${formatSize(speed)}/s" else size
        }

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

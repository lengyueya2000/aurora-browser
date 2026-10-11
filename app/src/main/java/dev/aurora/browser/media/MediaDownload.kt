package dev.aurora.browser.media

import android.content.Context
import android.webkit.WebSettings
import dev.aurora.browser.data.DownloadRequest
import dev.aurora.browser.data.DownloadsRepository

/**
 * 「下载这个地址」的唯一分岔口。
 *
 * 网页浮层、主菜单、内置播放器三处都要做同一个判断：播放列表（HLS/DASH）是一堆分片，
 * 交给 Media3 的离线缓存；直链才交给系统下载器。各写一遍迟早漂移——真漂移过一次，
 * 结果是把 `.m3u8` 交给系统下载器，用户拿到一个几 KB 的文本文件。
 */
object MediaDownload {

    /** 入队结果：是否已开始，以及一句可以直接给用户看的话。 */
    data class Result(val started: Boolean, val message: String)

    /**
     * @param referer 页面地址。有的 CDN 会校验 Referer，没有就会 403。
     * @param downloads 复用调用方已有的仓库实例；不传就现建一个（很薄，只是包着系统服务）。
     */
    fun enqueue(
        context: Context,
        url: String,
        title: String,
        referer: String? = null,
        downloads: DownloadsRepository = DownloadsRepository(context),
    ): Result {
        if (MediaUrls.isPlaylist(url)) {
            // HLS/DASH 是一堆分片：交给 Media3 的离线缓存，下完能离线播。
            OfflineMedia.enqueue(context, url, title, referer)
            return Result(true, "已开始离线缓存，可在「下载管理」里离线观看")
        }
        val request = DownloadRequest(
            url = url,
            // `DownloadListener` 那条路拿不到 UA，这里补上——不少视频 CDN 会校验 UA。
            userAgent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull(),
            contentDisposition = null,
            mimeType = null,
            contentLength = -1L,
        )
        val id = downloads.enqueue(request, referer = referer)
        return if (id != null) {
            Result(true, "已开始下载")
        } else {
            Result(false, "无法开始下载")
        }
    }
}

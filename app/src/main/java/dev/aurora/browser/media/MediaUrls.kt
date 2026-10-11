package dev.aurora.browser.media

/**
 * 媒体地址的分类。嗅探、下载分流、播放器建源都要判断「这是播放列表还是单个文件」，
 * 判断规则只写一处，免得三处各写一遍再各自漂移。
 */
object MediaUrls {

    /** 分片清单：交给播放器边下边播，不能当文件下载。 */
    fun isPlaylist(url: String): Boolean {
        val path = pathOf(url)
        return path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    /** HLS。只有它需要显式建 `HlsMediaSource`（顺带打开 chunkless 预解析）。 */
    fun isHls(url: String): Boolean = pathOf(url).endsWith(".m3u8")

    /** 只看路径，忽略查询串与锚点——很多 CDN 把扩展名之外的参数塞在 `?` 后面。 */
    private fun pathOf(url: String): String =
        url.substringBefore('?').substringBefore('#').lowercase()
}

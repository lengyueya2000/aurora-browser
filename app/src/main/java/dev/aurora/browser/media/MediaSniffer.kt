package dev.aurora.browser.media

import java.util.concurrent.ConcurrentHashMap

/**
 * 记下每个标签页真正在播的那个流地址。
 *
 * 为什么不能只看页面里的 `<video>`：现在的视频站基本都走 MSE——`<video>` 的 `src` 是
 * `blob:`，真实地址（HLS 的 `.m3u8`、直链的 `.mp4`/`.flv`）只出现在网络层。欧乐影院
 * 就是典型：页面里是个 plyr + hls.js，`<video>` 挂的是 `blob:`，真流在
 * `.../master.m3u8`。
 *
 * 网络层的每一个请求都会经过 `WebViewClient.shouldInterceptRequest`，在那里把媒体
 * 地址记下来，播放 / 下载 / 投屏就都有地址可用了。这个类只做分类与挑选，不碰 Android
 * API，因此可以纯 JVM 单测。
 */
object MediaSniffer {

    /** 每个标签页最多记这么多条，免得长会话里无限增长。 */
    private const val MAX_PER_TAB = 24

    /** 流式播放列表：交给 ExoPlayer 直接播，也是离线下载的入口。 */
    private val PLAYLIST_EXTENSIONS = setOf("m3u8", "mpd")

    /** 直链媒体文件：可以直接下载成一个能分享的文件。 */
    private val FILE_EXTENSIONS = setOf("mp4", "flv", "mkv", "webm", "mov", "m4v")

    /**
     * HLS/DASH 的分片（`.ts`、`.m4s`）。既不能单独播也不能单独下，记下来只会把
     * 播放列表挤出列表，直接丢掉。
     */
    private val SEGMENT_EXTENSIONS = setOf("ts", "m4s", "aac")

    /**
     * 直链文件的优先级。
     *
     * 同一次播放里站点可能同时拉了多个文件（预览图那种不算，走不到这里），挑一个
     * 「最像正片」的：mp4 最通用，flv 其次（国内站点常见），其余往后排。
     */
    private val FILE_PRIORITY = listOf("mp4", "flv", "mkv", "webm", "mov", "m4v")

    private val byTab = ConcurrentHashMap<String, MutableList<String>>()

    /** 记一条请求。不是媒体地址就忽略。 */
    fun record(tabId: String, url: String) {
        if (kindOf(url) == null) return
        val list = byTab.getOrPut(tabId) { mutableListOf() }
        synchronized(list) {
            // 同一个地址会随分片/重试被反复请求，去重后按「最近一次出现」重排，
            // 这样列表尾部永远是站点当下正在用的那条。
            list.remove(url)
            list.add(url)
            while (list.size > MAX_PER_TAB) list.removeAt(0)
        }
    }

    /** 换页就清空：上一条视频的地址对新页面没有意义。 */
    fun clear(tabId: String) {
        byTab.remove(tabId)
    }

    /** 该标签页当前最该拿去播放的地址。 */
    fun playable(tabId: String): String? {
        val urls = snapshot(tabId)
        val playlists = urls.filter { kindOf(it) == Kind.PLAYLIST }
        if (playlists.isNotEmpty()) {
            // 优先 HLS（站点最常见），其次 DASH。
            val hls = playlists.filter { extensionOf(it) == "m3u8" }.ifEmpty { playlists }
            // master 播放列表带多档清晰度，比单档变体更值得交给播放器。
            return hls.lastOrNull { it.contains("master", ignoreCase = true) } ?: hls.last()
        }
        return directFile(tabId)
    }

    /** 该标签页当前最该拿去「存成一个文件」的地址；只有直链才有。 */
    fun directFile(tabId: String): String? {
        val files = snapshot(tabId).filter { kindOf(it) == Kind.FILE }
        if (files.isEmpty()) return null
        for (ext in FILE_PRIORITY) {
            files.lastOrNull { extensionOf(it) == ext }?.let { return it }
        }
        return null
    }

    /** 该标签页嗅探到的所有候选（供 UI 展示 / 测试断言）。 */
    fun candidates(tabId: String): List<String> = snapshot(tabId)

    private fun snapshot(tabId: String): List<String> {
        val list = byTab[tabId] ?: return emptyList()
        return synchronized(list) { list.toList() }
    }

    /** 地址属于哪一类；不是媒体地址返回 null。 */
    internal fun kindOf(url: String): Kind? {
        val ext = extensionOf(url)
        return when {
            ext in PLAYLIST_EXTENSIONS -> Kind.PLAYLIST
            ext in FILE_EXTENSIONS -> Kind.FILE
            ext in SEGMENT_EXTENSIONS -> null
            // 有些站点把播放列表藏在查询串里（`/play?url=xxx.m3u8`），路径上没有扩展名。
            url.contains("m3u8", ignoreCase = true) -> Kind.PLAYLIST
            url.contains(".mpd", ignoreCase = true) -> Kind.PLAYLIST
            else -> null
        }
    }

    /** 取 URL 路径部分的扩展名（小写，不含点）；查询串里的扩展名不算。 */
    internal fun extensionOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/', "")
        val dot = last.lastIndexOf('.')
        if (dot < 0 || dot == last.length - 1) return ""
        return last.substring(dot + 1).lowercase()
    }

    enum class Kind { PLAYLIST, FILE }
}

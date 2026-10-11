package dev.aurora.browser.ui.browser

import java.net.URI

/**
 * 页面上找到的一段媒体源：`<video>` 自身的 `src` / `currentSrc`，或它的 `<source>` 子元素。
 *
 * @param type `<source type="...">` 声明的 MIME，没有则为空串。
 * @param label 列表里给人看的短名（文件名，取不到就用主机名；流式地址固定为「流式视频」）。
 */
data class VideoSource(val url: String, val type: String, val label: String) {
    /**
     * `blob:` / `data:` 地址。
     *
     * 这类地址只在当前页面会话里有效，出了页面就取不到内容：DownloadManager 下不了，
     * 投屏设备也播不了。站点用 MSE（`MediaSource`）播放时视频源就是 blob——这也是
     * 「很多网站的视频下载不了」的根本原因，必须如实告诉用户，而不是给个点了没反应的按钮。
     */
    val isStream: Boolean
        get() = url.startsWith("blob:", ignoreCase = true) ||
            url.startsWith("data:", ignoreCase = true)

    /** 列表里显示的副标题：主机名 + 格式提示。刻意不显示整条 URL——太长了。 */
    val subtitle: String
        get() = buildString {
            if (isStream) {
                append(if (url.startsWith("data:", ignoreCase = true)) "data 地址" else "blob 地址")
                append(" · 无法下载")
                return@buildString
            }
            append(runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { label })
            if (type.isNotBlank()) append(" · ").append(type)
        }
}

/**
 * 从当前页面提取可下载 / 可投屏的视频地址。
 *
 * 为什么不能靠 `DownloadListener`：那个回调只在「浏览器决定不渲染、转交下载」时触发
 * （`Content-Disposition: attachment` 或非渲染型 MIME）。HTML5 的 `<video>` 是**内联
 * 渲染**的，点它不会触发任何下载回调——所以必须自己进页面里找。
 */
object VideoExtractor {

    /**
     * 注入页面的脚本，返回 `[{url, type}, ...]`。
     *
     * 返回数组而不是 `JSON.stringify` 的结果：`evaluateJavascript` 会把返回值再 JSON 编码
     * 一次，返回字符串会被套两层引号，得解析两次。
     */
    val SCRIPT: String = """
        (function(){
          var out = [], seen = {};
          function abs(u){
            try { return new URL(u, document.baseURI).href; } catch (e) { return ''; }
          }
          function add(u, type){
            u = abs(u);
            if (!u || seen[u]) return;
            seen[u] = 1;
            out.push({ url: u, type: type || '' });
          }
          var vs = document.querySelectorAll('video');
          for (var i = 0; i < vs.length; i++) {
            var v = vs[i];
            if (v.currentSrc) add(v.currentSrc, '');
            if (v.getAttribute('src')) add(v.getAttribute('src'), v.getAttribute('type') || '');
            var ss = v.querySelectorAll('source');
            for (var j = 0; j < ss.length; j++) {
              add(ss[j].getAttribute('src'), ss[j].getAttribute('type') || '');
            }
          }
          return out;
        })()
    """.trimIndent()

    /** 把 `evaluateJavascript` 回传的 JSON 文本解析成媒体源列表；坏数据一律当空。 */
    fun parse(raw: String?): List<VideoSource> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        val array = runCatching {
            org.json.JSONTokener(raw).nextValue() as? org.json.JSONArray
        }.getOrNull() ?: return emptyList()

        val seen = mutableSetOf<String>()
        val out = mutableListOf<VideoSource>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val url = obj.optString("url").takeIf { it.isNotBlank() } ?: continue
            if (!seen.add(url)) continue
            out += VideoSource(url, obj.optString("type"), labelOf(url))
        }
        return out
    }

    /**
     * 列表里显示的短名：URL 路径末段；取不到就退到主机名，再不行就用整条地址。
     *
     * `blob:` / `data:` 是例外：它们的「路径」是一串会话 UUID，直接显示会占满三行
     * 而且毫无信息量，所以统一叫「流式视频」。
     */
    internal fun labelOf(url: String): String {
        if (url.startsWith("blob:", ignoreCase = true) || url.startsWith("data:", ignoreCase = true)) {
            return "流式视频"
        }
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val name = uri.path.orEmpty().substringAfterLast('/', "")
        if (name.isNotBlank()) return name
        return uri.host?.takeIf { it.isNotBlank() } ?: url
    }
}

package dev.aurora.browser.domain

/**
 * 地址栏输入解析：能识别成地址的直接导航，否则交给搜索引擎。
 *
 * 判定刻意保守——把可疑输入当搜索词，也好过把「怎么学 kotlin」拼成一个坏 URL。
 * 因此：含空白字符或非 ASCII 字符（中文等）的一律按搜索处理。
 */
object UrlResolver {

    /** 带 scheme 的完整地址：http://…、https://…、ftp://… */
    private val WITH_SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://\\S*$")

    /** 无 authority 的 scheme：about:blank、data:…、file:…、javascript:… */
    private val SCHEME_ONLY = Regex("^(about|data|file|blob|javascript|mailto|tel):\\S*$", RegexOption.IGNORE_CASE)

    /** 形如 example.com、a.b.co.uk:8080/path?q=1 —— 顶级域至少两位字母。 */
    private val HOST_LIKE = Regex(
        "^(?:[a-zA-Z0-9](?:[a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(?::\\d{1,5})?(?:[/?#]\\S*)?$",
    )

    private val LOCALHOST_LIKE = Regex("^localhost(?::\\d{1,5})?(?:[/?#]\\S*)?$", RegexOption.IGNORE_CASE)

    private val IP_LIKE = Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?:[/?#]\\S*)?$")

    /** 输入是否可以当作地址直接加载。 */
    fun isUrlLike(raw: String): Boolean {
        val input = raw.trim()
        if (input.isEmpty()) return false
        // 地址里不该出现裸空白；中文域名这类非 ASCII 输入也走搜索，交给搜索引擎兜底。
        if (input.any { it.isWhitespace() || it.code > 0x7F }) return false
        return WITH_SCHEME.matches(input) ||
            SCHEME_ONLY.matches(input) ||
            IP_LIKE.matches(input) ||
            LOCALHOST_LIKE.matches(input) ||
            HOST_LIKE.matches(input)
    }

    /**
     * 输入 → 可加载地址。识别为地址时补全 `https://`，否则拼成搜索引擎查询地址。
     * 空输入返回空串，调用方据此忽略提交。
     */
    fun resolve(raw: String, engine: SearchEngine = SearchEngine.Default): String {
        val input = raw.trim()
        if (input.isEmpty()) return ""
        if (WITH_SCHEME.matches(input) || SCHEME_ONLY.matches(input)) return input
        if (isUrlLike(input)) return "https://$input"
        return engine.searchUrl(input)
    }
}

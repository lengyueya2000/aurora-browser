package dev.aurora.browser.domain


/**
 * 版本更新检测的核心逻辑。
 *
 * 刻意做成**纯函数 + 注入 HTTP client**：版本比较和 JSON 解析是最容易写错的地方
 * （`1.9` 和 `1.10` 的字符串比较就是经典坑），必须能在 JVM 单元测试里直接驱动，
 * 不能依赖真机或网络。
 *
 * ## 为什么用 /releases 而不是 /releases/latest
 *
 * 实测 `/releases/latest` 会**忽略 pre-release**，返回的是 v1.17——而本项目的
 * v1.18 之后全部发布为 pre-release。所以必须取 `/releases` 列表的第一个非
 * draft 元素。
 */
object UpdateChecker {

    /** /releases 列表里一个可用的版本。 */
    data class ReleaseInfo(
        val version: String,
        val notes: String,
        val pageUrl: String,
    )

    sealed interface CheckResult {
        data class NewVersion(val info: ReleaseInfo) : CheckResult
        data object UpToDate : CheckResult
        data object Failed : CheckResult
    }

    /**
     * 解析版本号为 **3 段**整数 `[major, minor, patch]`。
     *
     * - 兼容 `v` 前缀（大小写不敏感：`v1.22` / `V1.22`）和纯数字 `1.22`。
     * - **2 段或 3 段都合法**：本项目 versionName 就是两段（`1.22`），缺的 patch 补 0。
     * - 1 段（`v1`）无效——太含糊，不该被当成新版本误报。
     * - 多于 3 段取前三段（`1.2.3.4` → `[1,2,3]`）。
     * - 任何一段不是数字 → null。
     */
    fun parseVersion(tag: String): IntArray? {
        val raw = tag.trim()
        if (raw.isEmpty()) return null
        val body = if (raw.startsWith("v", ignoreCase = true)) raw.drop(1) else raw
        if (body.isEmpty()) return null
        val parts = body.split('.')
        // 当前版本形如 1.22（两段），tag 可能有 patch（v1.2.3），两者都要支持。
        if (parts.size < 2) return null
        val result = IntArray(3) // 缺的段补 0
        for (i in 0..2) {
            if (i >= parts.size) break
            val p = parts[i]
            // 只认纯数字，不接受 +1 / -1 / 空格等写法。
            if (p.isEmpty() || !p.all { it in '0'..'9' }) return null
            result[i] = p.toIntOrNull() ?: return null
        }
        return result
    }

    /**
     * 候选版本是否比当前版本新。
     *
     * 逐段整数比较，`1.10 > 1.9`（字符串比较会得到相反结果）。任一解析失败就
     * 返回 false——宁可不提示，也不能因为一个奇怪的 tag 误报更新。
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val c = parseVersion(candidate) ?: return false
        val cur = parseVersion(current) ?: return false
        for (i in 0..2) {
            if (c[i] > cur[i]) return true
            if (c[i] < cur[i]) return false
        }
        return false
    }

    /**
     * 从 `/releases` 的 JSON 数组里取最新可用版本。
     *
     * - 跳过 `draft = true` 的条目（草稿不该提示）。
     * - **不**按 `prerelease` 过滤：本项目当前所有发布都是 pre-release。
     * - 缺 `tag_name` / `html_url` 或 tag 解析失败 → null。
     * - `body` 缺失时更新说明为空串。
     */
    fun parseLatestRelease(json: String): ReleaseInfo? {
        val objects = parseObjectArray(json) ?: return null
        for (obj in objects) {
            if (obj["draft"] as? Boolean == true) continue
            val tag = obj["tag_name"] as? String ?: continue
            if (parseVersion(tag) == null) continue
            val url = obj["html_url"] as? String ?: continue
            if (url.isEmpty()) continue
            return ReleaseInfo(
                version = tag,
                notes = obj["body"] as? String ?: "",
                pageUrl = url,
            )
        }
        return null
    }

    /**
     * 极简 JSON 解析：只支持「顶层数组，元素是扁平 object，值是字符串/布尔/null」。
     *
     * GitHub 的 release 列表字段很多（assets 里还有嵌套数组），但我们**只要**
     * `tag_name` / `html_url` / `body` / `draft` 四个扁平字段。写一个够用的解析器
     * 而不是用 `org.json`——后者在 JVM 单元测试里是 Android stub，一调用就抛
     * `RuntimeException: Stub!`，会让纯逻辑测试根本跑不起来（本项目之前吃过这个亏）。
     *
     * 遇到不认识的结构就跳过该字段，不抛异常——解析失败一律当作「没有更新」。
     */
    private fun parseObjectArray(json: String): List<Map<String, Any>>? {
        val s = json.trim()
        if (s.length < 2 || s[0] != '[' || s.last() != ']') return null
        val inner = s.substring(1, s.length - 1)
        val result = mutableListOf<Map<String, Any>>()
        var i = 0
        while (i < inner.length) {
            // 找下一个 '{'
            val start = indexOfUnquoted(inner, '{', i) ?: break
            val end = matchingBrace(inner, start) ?: break
            result += parseFlatObject(inner.substring(start + 1, end))
            i = end + 1
        }
        return result
    }

    /** 找字符在字符串中的位置，忽略引号内的字符。 */
    private fun indexOfUnquoted(s: String, target: Char, from: Int): Int? {
        var i = from
        var inString = false
        var escaped = false
        while (i < s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    target -> return i
                }
            }
            i++
        }
        return null
    }

    /** 找与 start 处的 '{' 配对的 '}'，忽略引号与转义。 */
    private fun matchingBrace(s: String, start: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** 解析扁平 object 的 `key: value` 对，只收字符串/布尔/null。 */
    private fun parseFlatObject(body: String): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        var i = 0
        while (i < body.length) {
            val q1 = body.indexOf('"', i)
            if (q1 < 0) break
            val q2 = quotedEnd(body, q1) ?: break
            val key = body.substring(q1 + 1, q2)
            val colon = body.indexOf(':', q2 + 1)
            if (colon < 0) break
            var v = colon + 1
            while (v < body.length && body[v].isWhitespace()) v++
            when {
                body.startsWith("null", v) -> { /* 跳过 null */ i = v + 4 }
                body.startsWith("true", v) -> { out[key] = true; i = v + 4 }
                body.startsWith("false", v) -> { out[key] = false; i = v + 5 }
                v < body.length && body[v] == '"' -> {
                    val end = quotedEnd(body, v) ?: break
                    out[key] = unescape(body.substring(v + 1, end))
                    i = end + 1
                }
                else -> {
                    // 数字/数组/对象：我们不关心，跳到下一个逗号。
                    val comma = body.indexOf(',', v)
                    i = if (comma < 0) body.length else comma + 1
                }
            }
        }
        return out
    }

    /** 找到引号字符串的结束位置（处理转义）。 */
    private fun quotedEnd(s: String, start: Int): Int? {
        var i = start + 1
        var escaped = false
        while (i < s.length) {
            when {
                escaped -> escaped = false
                s[i] == '\\' -> escaped = true
                s[i] == '"' -> return i
            }
            i++
        }
        return null
    }

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { append('\n'); i += 2 }
                    'r' -> { append('\r'); i += 2 }
                    't' -> { append('\t'); i += 2 }
                    '"' -> { append('"'); i += 2 }
                    '\\' -> { append('\\'); i += 2 }
                    else -> { append(s[i]); i++ }
                }
            } else {
                append(s[i]); i++
            }
        }
    }

    /**
     * 跑一次完整检测。HTTP client 由调用方注入，返回 `null` 代表失败。
     *
     * 这样测试里可以喂假 JSON，不需要网络也不需要真机。
     */
    fun check(
        currentVersion: String,
        ignoreVersion: String?,
        fetch: () -> String?,
    ): CheckResult {
        val json = fetch() ?: return CheckResult.Failed
        val latest = parseLatestRelease(json) ?: return CheckResult.Failed
        if (!isNewer(latest.version, currentVersion)) return CheckResult.UpToDate
        if (latest.version == ignoreVersion) return CheckResult.UpToDate
        return CheckResult.NewVersion(latest)
    }
}

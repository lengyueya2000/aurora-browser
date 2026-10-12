package dev.aurora.browser.media

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/** 一集：给人看的标签 + 该集的页面地址。 */
data class Episode(val label: String, val url: String)

/**
 * 一部剧的选集列表。
 *
 * @param currentIndex 正在看的那一集在 [episodes] 里的下标；`-1` 表示当前页不在列表里
 *   （面板照常列，只是没有「正在看」的高亮）。
 */
data class EpisodeList(
    val seriesTitle: String,
    val episodes: List<Episode>,
    val currentIndex: Int,
) {
    val size: Int get() = episodes.size

    /** 有没有「下一集」：`index` 之后还有条目才算。 */
    fun hasNext(index: Int): Boolean = index >= 0 && index + 1 < episodes.size

    fun nextIndex(index: Int): Int? = if (hasNext(index)) index + 1 else null

    fun labelOf(index: Int): String = episodes.getOrNull(index)?.label.orEmpty()
}

/**
 * 选集列表的解析与编解码。
 *
 * **这里只做判定，不做提取**：注入脚本负责把页面上的候选链接抓回来（见
 * [dev.aurora.browser.ui.browser.EpisodeExtractor]），是不是选集、怎么排序、当前是哪一集
 * 全在这个纯函数里判。这么切是因为判定规则是这个功能里最需要反复调的部分——放在这里能写
 * 单测、改一次不用重装页面逻辑；脚本越简单越不容易被站点的 CSP 或 DOM 重建搞坏。
 *
 * 判定用「主键法」：把 URL 末段的**尾部数字**换成 `#` 当主键，同主键的归为一簇。
 *
 * ```
 * /player/vod/1-82861-1.html  → 主键 1-82861-#   集号 1
 * /player/vod/1-82861-2.html  → 主键 1-82861-#   集号 2   ← 同簇
 * /voddetail/1-82861.html     → 主键 1-#         集号 82861 ← 另一簇，会被 ≥3 条的门槛挡掉
 * /play/ch4k8uf4p             → 主键 ch4k8uf4p  集号 null  ← 没有尾数字，主键即自身
 * ```
 *
 * 注意「尾部数字」而不是「最后一段数字」：`1-82861` 的最后一段数字是 `82861`，拿它当集号
 * 会把详情页的链接排到第 82861 集去。只认尾部的连续数字，详情页就自然落到另一簇。
 *
 * 门槛取严（同簇 ≥ [MIN_CLUSTER] 条且必须包含当前页）是有意的：把「相关推荐」误判成选集，
 * 后果是面板里冒出一堆不相关的集——不至于坏，但很难看，用户会以为功能是坏的。宁可漏，不可错。
 *
 * 主键法有一条明摆着的盲区：**地址是不透明 ID 的站点**（4kvm.net 的 `/play/ch4k8uf4p`、
 * 爱奇艺的 `/v_xxxxx.html`）——同一部剧的每一集地址之间没有任何共性，主键各不相同，
 * 分不出簇。这类站点只能靠「链接自己标明是选集」这条退路，见 [clusterByEpisodeMark]。
 */
object EpisodeCodec {

    /** 一季最多这么多集；防呆，不是防 Binder。 */
    const val MAX_EPISODES = 300

    /** 脚本最多回这么多条候选，超出的丢掉。 */
    const val MAX_CANDIDATES = 400

    /** 同一簇至少这么多条才算剧集——2 条太容易是「上一集 / 下一集」两个按钮。 */
    const val MIN_CLUSTER = 3

    /** 查询串兜底规则里，允许不同的参数名。 */
    private val EPISODE_PARAMS = setOf(
        "ep", "episode", "episodes", "index", "idx", "sid", "ji", "jishu", "v", "id",
    )

    /**
     * 把脚本回传的候选解析成一部剧。
     *
     * @param raw `evaluateJavascript` 的返回值：`[{href, text, inList, ep}, …]`。
     * @return 判不出剧集时返回 `null`——调用方据此当成单集视频，一切照旧。
     */
    fun parse(raw: String?, pageUrl: String, pageTitle: String): EpisodeList? {
        if (raw.isNullOrBlank() || raw == "null") return null
        val array = runCatching {
            JSONTokener(raw).nextValue() as? JSONArray
        }.getOrNull() ?: return null
        val candidates = ArrayList<Candidate>(minOf(array.length(), MAX_CANDIDATES))
        for (i in 0 until array.length()) {
            if (candidates.size >= MAX_CANDIDATES) break
            val o = array.optJSONObject(i) ?: continue
            val href = o.optString("href")
            if (href.isBlank()) continue
            candidates += Candidate(
                href = href,
                text = o.optString("text").trim().take(24),
                inList = o.optBoolean("inList"),
                ep = o.optBoolean("ep"),
            )
        }
        return build(candidates, pageUrl, pageTitle)
    }

    /**
     * 候选链接。
     *
     * @param inList 脚本判定它落在疑似选集容器里。
     * @param ep 脚本判定它**自己**标明了「这是第几集」（同一记号全页 ≥ 3 条）。
     */
    internal data class Candidate(
        val href: String,
        val text: String,
        val inList: Boolean,
        val ep: Boolean = false,
    )

    internal fun build(candidates: List<Candidate>, pageUrl: String, pageTitle: String): EpisodeList? {
        val page = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val pageKey = normalize(pageUrl)

        // ① 清洗：绝对化、只留 http(s)、去重。
        // 当前页自己**要留着**：站点常把正在播的那一集也渲染成链接，留着它才能标出「正在看」，
        // 也才有它自己的标签可用。判簇靠的是「主键与当前页相同」，不靠「列表里有当前页」。
        val seen = HashSet<String>()
        val resolved = ArrayList<Episode>(candidates.size)
        val flags = ArrayList<Boolean>(candidates.size)
        val marks = ArrayList<Boolean>(candidates.size)
        for (c in candidates) {
            val url = resolve(page, c.href) ?: continue
            if (!seen.add(url)) continue
            resolved += Episode(label = c.text.ifBlank { labelOf(url) }, url = url)
            flags += c.inList
            marks += c.ep
        }
        if (resolved.size < MIN_CLUSTER) return null

        val pick = clusterByMainKey(resolved, flags, pageUrl)
            ?: clusterByQueryParam(resolved, flags, page)
            ?: clusterByEpisodeMark(resolved, marks)
            ?: return null
        val episodes = pick.take(MAX_EPISODES)
        val current = episodes.indexOfFirst { normalize(it.url) == pageKey }
        return EpisodeList(
            seriesTitle = seriesTitle(pageTitle, pageUrl),
            episodes = episodes,
            currentIndex = current,
        )
    }

    /**
     * 主键分簇：取主键与当前页相同的那一簇。
     *
     * 容器信号（`inList`）比「URL 长得像」更可靠，所以只要有候选带了容器标记，就要求同一簇里
     * 至少 [MIN_CLUSTER] 条也在容器内；一条都没带容器标记时（脚本走了兜底选择器）才只看数量。
     */
    private fun clusterByMainKey(
        resolved: List<Episode>,
        flags: List<Boolean>,
        pageUrl: String,
    ): List<Episode>? {
        val pageMainKey = mainKey(pageUrl) ?: return null
        val anyInList = flags.any { it }
        val clusters = LinkedHashMap<String, MutableList<Int>>()
        resolved.forEachIndexed { i, e ->
            val key = mainKey(e.url) ?: return@forEachIndexed
            clusters.getOrPut(key) { mutableListOf() } += i
        }
        val hit = clusters[pageMainKey] ?: return null
        if (hit.size < MIN_CLUSTER) return null
        if (anyInList && hit.count { flags[it] } < MIN_CLUSTER) return null
        return sort(hit.map { resolved[it] })
    }

    /**
     * 查询串兜底：同一个路径、只有「集号类」参数不同。
     *
     * 给 `/play?ep=2` 这种把集号放查询串里的站点用。同样要求 ≥ [MIN_CLUSTER] 条。
     */
    private fun clusterByQueryParam(
        resolved: List<Episode>,
        flags: List<Boolean>,
        page: URI,
    ): List<Episode>? {
        val anyInList = flags.any { it }
        val samePath = ArrayList<Int>()
        resolved.forEachIndexed { i, e ->
            val u = runCatching { URI(e.url) }.getOrNull() ?: return@forEachIndexed
            if (u.host != page.host) return@forEachIndexed
            if (u.path.orEmpty() != page.path.orEmpty()) return@forEachIndexed
            samePath += i
        }
        if (samePath.size < MIN_CLUSTER) return null
        if (anyInList && samePath.count { flags[it] } < MIN_CLUSTER) return null

        val pageParams = queryMap(page)
        // 参数名集合必须一致，且只有集号类参数不同——否则「同一个页面带不同筛选条件」也会被算成剧集。
        val names = samePath.map { queryMap(URI(resolved[it].url)) }
        if (names.any { it.keys != pageParams.keys }) return null
        if (pageParams.isEmpty()) return null
        if (pageParams.keys.any { it !in EPISODE_PARAMS }) return null

        return sort(samePath.map { resolved[it] })
    }

    /**
     * 自我标明的选集：站点给每条链接自己打了「这是第几集」的记号，而地址之间毫无共性。
     *
     * 4kvm.net 就是标准样子——47 条 `<a href="/play/ch4k8uf4p" class="episode-link"
     * data-episode="1">`，每一集的 ID 都是不透明串，主键法只能分出一条一条的独苗。
     * 爱奇艺的 `/v_xxxxx.html` 同理。
     *
     * 门槛两条：带记号的 ≥ [MIN_CLUSTER] 条，且其中能读出集号的**不同**集号也 ≥
     * [MIN_CLUSTER] 个。第二条是必须的——面板里排不出顺序的一堆格子还不如不给面板
     * （规格 §2.2「不做猜测」）。集号取不到时（地址没数字、标签也不是数字）就认输。
     *
     * 这是三条路里最弱的一条（记号是站点自己写的，我们只是照信），所以排在主键法与
     * 查询串法之后：前两条能判出来时，说明地址本身就有结构，那个证据比记号硬。
     */
    private fun clusterByEpisodeMark(resolved: List<Episode>, marks: List<Boolean>): List<Episode>? {
        val hits = resolved.filterIndexed { i, _ -> marks[i] }
        if (hits.size < MIN_CLUSTER) return null
        val numbers = hits.map { episodeNumber(it.url, it.label) }
        if (numbers.filterNotNull().distinct().size < MIN_CLUSTER) return null
        return sort(hits)
    }

    /** 按集号排；取不到集号的排到最后，保持原有文档顺序（稳定排序）。 */
    private fun sort(episodes: List<Episode>): List<Episode> =
        episodes.sortedBy { episodeNumber(it.url, it.label) ?: Int.MAX_VALUE }

    /**
     * 分簇用的主键。
     *
     * 末段去掉扩展名后，**尾部**连续数字换成 `#`；没有尾部数字时把查询串也算进主键——
     * 那种 URL 里一个集号证据都没有，`/list?sort=a` 和 `/list?sort=b` 不能因为路径相同就
     * 被当成同一部剧的两集（那正是「筛选条件」这类误判）。集号在查询串里的站点走
     * [clusterByQueryParam] 那条路。
     */
    internal fun mainKey(url: String): String? {
        val segment = lastSegment(url) ?: return null
        val m = TRAILING_DIGITS.find(segment)
        if (m != null) return segment.substring(0, m.range.first) + "#"
        val query = runCatching { URI(url).rawQuery }.getOrNull().orEmpty()
        val key = if (query.isBlank()) segment else "$segment?$query"
        return key.ifBlank { null }
    }

    /**
     * 集号：**标签优先**，标签里读不出来才退回地址。
     *
     * 顺序反过来的话，地址是不透明 ID 的站点会全线读错：4kvm.net 的
     * `/play/ch4k8uf55` 是第 **2** 集，末尾那个 `55` 只是 ID 的两位，跟集号毫无关系。
     * 而站点写在标签里的「第 2 集」「EP02」「E2」「S01E02」是它自己给出的直接证据。
     *
     * 标签里确实读不出数字（「播放」「正片」「HD」）时才用地址末段的尾部数字——
     * 那是 `/player/vod/1-82861-3.html` 这类站点的证据。
     */
    internal fun episodeNumber(url: String, label: String): Int? =
        labelNumber(label) ?: urlNumber(url)

    /**
     * 标签里的集号。
     *
     * 先找「第 N 集 / 話 / 话」这种带量词的写法（`第2季 第3集` 要读 3 不是 2），
     * 再找 `第 N` / `EP N` / `S01E02` / `E2` 这些前缀写法，最后才退到标签里的第一个数字。
     */
    internal fun labelNumber(label: String): Int? {
        LABEL_EPISODE.find(label)?.let { return it.groupValues[1].toIntOrNull() }
        LABEL_PREFIX.find(label)?.let { return it.groupValues[1].toIntOrNull() }
        return DIGITS.find(label)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** 地址末段的尾部数字。 */
    private fun urlNumber(url: String): Int? =
        lastSegment(url)?.let { TRAILING_DIGITS.find(it) }
            ?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 剧名：从页面标题里削掉集号与站名后缀。削没了就退回 host。
     *
     * 结果只用于显示（标题行、主页卡片），不参与任何匹配——削错一个词不会让功能坏掉。
     */
    fun seriesTitle(pageTitle: String, pageUrl: String): String {
        var t = pageTitle
        NOISE.forEach { t = t.replace(it, " ") }
        t = t.trim().trim(*TRIM_CHARS)
        t = t.replace(Regex("\\s{2,}"), " ").trim()
        if (t.isBlank()) {
            t = runCatching { URI(pageUrl).host }.getOrNull().orEmpty()
        }
        return t.ifBlank { pageUrl }
    }

    /** 列表里显示的短名：URL 末段；取不到就退回主机名。 */
    private fun labelOf(url: String): String =
        lastSegment(url)?.takeIf { it.isNotBlank() }
            ?: runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { url }

    /** 末段：去掉查询串、片段与扩展名。 */
    private fun lastSegment(url: String): String? {
        val path = runCatching { URI(url) }.getOrNull()?.path?.takeIf { it.isNotBlank() }
            ?: url.substringBefore('?').substringBefore('#').substringAfter("://").substringAfter('/', "")
        val last = path.substringAfterLast('/', "")
        if (last.isBlank()) return null
        val dot = last.lastIndexOf('.')
        return if (dot > 0) last.substring(0, dot) else last
    }

    /** 相对地址按当前页解析；`javascript:` / `data:` 这类一律丢掉。 */
    private fun resolve(page: URI, href: String): String? {
        val u = runCatching { page.resolve(href.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (u.host.isNullOrBlank()) return null
        return u.toString()
    }

    /** 比较用的归一化形式：去掉片段与末尾斜杠。 */
    private fun normalize(url: String): String =
        url.substringBefore('#').removeSuffix("/")

    private fun queryMap(uri: URI): Map<String, String> {
        val q = uri.rawQuery ?: return emptyMap()
        return q.split('&').mapNotNull { pair ->
            if (pair.isBlank()) return@mapNotNull null
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            k to v
        }.toMap()
    }

    // ── Intent 编解码（宿主 → 播放器）────────────────────────────

    fun toJson(list: EpisodeList): String {
        val arr = JSONArray()
        list.episodes.take(MAX_EPISODES).forEach {
            arr.put(JSONObject().put("label", it.label).put("url", it.url))
        }
        return JSONObject()
            .put("series", list.seriesTitle)
            .put("current", list.currentIndex)
            .put("episodes", arr)
            .toString()
    }

    /** 坏数据当 `null`：播放器宁可没有选集，也不能因为一条坏 JSON 起不来。 */
    fun fromJson(raw: String?): EpisodeList? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val o = JSONTokener(raw).nextValue() as? JSONObject ?: return null
            val arr = o.optJSONArray("episodes") ?: return null
            val episodes = (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = e.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Episode(label = e.optString("label").ifBlank { url }, url = url)
            }.take(MAX_EPISODES)
            if (episodes.isEmpty()) return null
            EpisodeList(
                seriesTitle = o.optString("series"),
                episodes = episodes,
                currentIndex = o.optInt("current", -1),
            )
        }.getOrNull()
    }

    /** 尾部连续数字。 */
    private val TRAILING_DIGITS = Regex("(\\d+)$")

    private val DIGITS = Regex("(\\d+)")

    /** 标签里的集号：「第 N 集 / 話 / 话」。 */
    private val LABEL_EPISODE = Regex("第\\s*(\\d+)\\s*[集話话]")

    /** 标签里的集号：`第 N` / `EP N` / `S01E02` / `E2`。 */
    private val LABEL_PREFIX = Regex("(?i)(?:第|EP|S\\d+\\s*E|E)\\s*(\\d+)")

    /** 标题里的集号与站名后缀。 */
    private val NOISE = listOf(
        Regex("第\\s*\\d+\\s*[集話话]"),
        Regex("第\\s*\\d+\\s*季"),
        Regex("(?i)\\bEP\\s*\\d+\\b"),
        Regex("(?i)\\bS\\d+\\s*E\\d+\\b"),
        Regex("(?i)\\bE\\s*\\d+\\b"),
        Regex("_?在线观看"),
        Regex("高清完整版"),
        Regex("免费在线播放"),
        Regex("_?完整版"),
        Regex("_?高清"),
        Regex("_?免费"),
        Regex("-\\s*[^-]{2,12}影(院|视)"),
    )

    private val TRIM_CHARS = charArrayOf(
        ' ', '\t', '-', '_', '|', '·', '—', ',', '，', '、', '(', ')', '（', '）', '【', '】',
    )
}

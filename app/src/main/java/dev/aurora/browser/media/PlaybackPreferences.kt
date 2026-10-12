package dev.aurora.browser.media

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条可以「接着看」的记录。
 *
 * 除了进度还带着标题和来源页地址：主页那张卡片要显示「哪一集」，点进去也要能把
 * Referer 一起交给播放器（见 [RefererFallbackDataSource]）。
 *
 * 还带着**选集列表**（[episodes]，[EpisodeCodec.toJson] 的原样字符串）。不存的话，从
 * 「继续观看」点回去就只剩一个光秃秃的播放器：选集是页面上下文，而这条记录是播放器写的，
 * 播放器手里只有当初随 Intent 传进来的那份。少了它，左下角的「上一集 / 下一集」会整组消失
 * ——用户会以为功能被删了，而不是「这一页没判出剧集」。
 */
data class ResumeEntry(
    val url: String,
    val title: String,
    val referer: String?,
    val positionMs: Long,
    val durationMs: Long,
    /** 最后一次观看的时间戳，用来排序。 */
    val at: Long,
    /** 选集列表（`EpisodeCodec.toJson` 的原样字符串）；单集视频为 `null`。 */
    val episodes: String? = null,
    /** 当时看的是第几集。列表里的 `current` 是页面上的那一集，切集后不再等于它。 */
    val episodeIndex: Int = -1,
) {
    /** 已看比例，0..1；时长还不知道时为 0。 */
    val progress: Float
        get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/**
 * 一部剧的观看记录：哪些集看完了。
 *
 * 为什么不能拿「继续观看」列表反推：播完的集会被 [PlaybackPreferences.forget] 删掉
 * （那是为了不让主页堆一串看完的集），所以续播列表里恰恰没有「已看」的证据。
 *
 * @param key 剧集标识，见 [PlaybackPreferences.seriesKey]。
 */
data class WatchedSeries(val key: String, val episodes: Set<Int>, val at: Long)

/**
 * 播放偏好：**记忆播放进度**、上次用的倍速，以及主页「继续观看」要的那张列表。
 *
 * 起因是「播放效果不太好」里的两条——每次全屏都从第 0 秒开始、每次都要重新调倍速。
 *
 * 进度存成**一个按时间倒序的数组**（[KEY_RESUME]），而不是「一个地址一个键」：
 * 主页要按最近看的顺序列出来，只存「地址 → 进度」的话就得把偏好文件里所有键捞出来
 * 才能拼出这个列表，而键名是 URL 的哈希，反推不回地址。
 *
 * 键名从 1.10 的 `pos_<hash>` 换成数组之后，**老用户的进度不会丢**：读的时候
 * [resumeMs] 还会回退去查那个旧键，只是旧进度不会出现在「继续观看」里（那条记录没有
 * 标题和时间），看一次就补上了。
 *
 * 什么时候不记：看得太浅（不足 [MIN_RESUME_MS]）或已经看到结尾（离结束不足
 * [TAIL_MS]）都不记——否则用户重开时会「接着播」到第 3 秒或直接跳到结尾，比从头播还烦。
 */
class PlaybackPreferences(private val prefs: SharedPreferences) {

    /** 接着播的位置。列表里没有就回退查 1.10 及以前写下的旧键。 */
    fun resumeMs(url: String): Long {
        val entry = resumable().firstOrNull { it.url == url }
        if (entry != null) return entry.positionMs
        return prefs.getLong(legacyPositionKey(url), 0L)
    }

    /**
     * 记下进度。
     *
     * @param title 页面标题；空的话卡片上就显示地址。
     * @param referer 来源页地址，重开时要一起交给播放器。
     * @param episodes 选集列表（`EpisodeCodec.toJson` 的原样字符串）。存下来是为了让
     *   「继续观看」点回去时左下角那对「上一集 / 下一集」还在——那是页面上下文，丢了
     *   播放器就无从知道还有没有相邻的集。
     * @param episodeIndex 当时看的是第几集。
     */
    fun remember(
        url: String,
        positionMs: Long,
        durationMs: Long,
        title: String = "",
        referer: String? = null,
        episodes: String? = null,
        episodeIndex: Int = -1,
    ) {
        if (url.isBlank()) return
        if (positionMs < MIN_RESUME_MS) return
        if (durationMs > 0L && positionMs > durationMs - TAIL_MS) return
        val entry = ResumeEntry(
            url = url,
            title = title.ifBlank { url },
            referer = referer?.takeIf { it.isNotBlank() },
            positionMs = positionMs,
            durationMs = durationMs.coerceAtLeast(0L),
            at = System.currentTimeMillis(),
            episodes = episodes?.takeIf { it.isNotBlank() },
            episodeIndex = episodeIndex,
        )
        // 同一地址只留最新的一条并移到最前，与书签 / 历史的做法一致。
        val next = (listOf(entry) + resumable().filterNot { it.url == url }).take(MAX_RESUME)
        write(next)
    }

    /** 播完或用户手动重看时清掉，免得下次又跳回去。 */
    fun forget(url: String) {
        val next = resumable().filterNot { it.url == url }
        write(next)
        prefs.edit().remove(legacyPositionKey(url)).apply()
    }

    /**
     * 「继续观看」列表，最近看的在最前。
     *
     * 解析失败（存储损坏）时当空列表——这个列表在主页首屏上，不能因为一条坏数据崩掉。
     */
    fun resumable(): List<ResumeEntry> {
        val raw = prefs.getString(KEY_RESUME, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                ResumeEntry(
                    url = url,
                    title = o.optString("title").ifBlank { url },
                    referer = o.optString("referer").takeIf { it.isNotBlank() },
                    positionMs = o.optLong("position"),
                    durationMs = o.optLong("duration"),
                    at = o.optLong("at"),
                    episodes = o.optString("episodes").takeIf { it.isNotBlank() },
                    episodeIndex = o.optInt("episodeIndex", -1),
                )
            }.sortedByDescending { it.at }
        }.getOrDefault(emptyList())
    }

    fun speed(): Float {
        val stored = prefs.getFloat(KEY_SPEED, 1f)
        return if (stored in PlayerGestures.SPEEDS) stored else 1f
    }

    fun rememberSpeed(speed: Float) {
        if (speed !in PlayerGestures.SPEEDS) return
        prefs.edit().putFloat(KEY_SPEED, speed).apply()
    }

    // ── 追剧模式（见 EpisodeList / EpisodeSession）──────────────

    /** 自动播放下一集；默认开。 */
    fun autoNext(): Boolean = runCatching { prefs.getBoolean(KEY_AUTO_NEXT, true) }.getOrDefault(true)

    fun rememberAutoNext(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_NEXT, enabled).apply()
    }

    /** 这一部剧里已经看完的集号。 */
    fun watched(seriesKey: String): Set<Int> =
        watchedSeries().firstOrNull { it.key == seriesKey }?.episodes.orEmpty()

    /**
     * 记下「这一集看完了」。
     *
     * 无痕标签不写——与「无痕不记历史」同一个口径，由调用方决定要不要调。
     */
    fun markWatched(seriesKey: String, episode: Int) {
        if (seriesKey.isBlank() || episode < 0) return
        val current = watched(seriesKey)
        if (episode in current) return
        val entry = WatchedSeries(
            key = seriesKey,
            // 集号上限只是防呆（一季 500 集是荒谬的）：真到了上限，被丢的是**新标记的
            // 那一个**——`current + episode` 把新元素排在最后，`take` 从前面留。
            episodes = (current + episode).take(MAX_WATCHED_PER_SERIES).toSet(),
            at = System.currentTimeMillis(),
        )
        writeWatched(
            (listOf(entry) + watchedSeries().filterNot { it.key == seriesKey }).take(MAX_WATCHED_SERIES),
        )
    }

    fun forgetWatched(seriesKey: String) {
        writeWatched(watchedSeries().filterNot { it.key == seriesKey })
    }

    /** 观看记录列表，最近看的在最前。坏数据一律当空列表。 */
    fun watchedSeries(): List<WatchedSeries> {
        val raw = prefs.getString(KEY_WATCHED, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val key = o.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val eps = o.optJSONArray("eps") ?: JSONArray()
                WatchedSeries(
                    key = key,
                    episodes = (0 until eps.length()).map { eps.optInt(it, -1) }
                        .filter { it >= 0 }.toSet(),
                    at = o.optLong("at"),
                )
            }.sortedByDescending { it.at }
        }.getOrDefault(emptyList())
    }

    private fun writeWatched(list: List<WatchedSeries>) {
        val array = JSONArray()
        list.forEach { s ->
            array.put(
                JSONObject()
                    .put("key", s.key)
                    .put("eps", JSONArray(s.episodes.sorted()))
                    .put("at", s.at),
            )
        }
        prefs.edit().putString(KEY_WATCHED, array.toString()).apply()
    }

    private fun write(list: List<ResumeEntry>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("url", it.url)
                    .put("title", it.title)
                    .put("referer", it.referer ?: "")
                    .put("position", it.positionMs)
                    .put("duration", it.durationMs)
                    .put("at", it.at)
                    .put("episodes", it.episodes ?: "")
                    .put("episodeIndex", it.episodeIndex),
            )
        }
        prefs.edit().putString(KEY_RESUME, array.toString()).apply()
    }

    private fun legacyPositionKey(url: String): String = "$KEY_POSITION_PREFIX${url.hashCode()}"

    companion object {
        private const val PREFS_NAME = "aurora_playback"
        private const val KEY_RESUME = "resume_v2"
        private const val KEY_POSITION_PREFIX = "pos_"
        private const val KEY_SPEED = "speed"
        private const val KEY_AUTO_NEXT = "auto_next"
        private const val KEY_WATCHED = "watched_v1"

        /** 看不够这么久就不值得记，从头播反而更符合预期。 */
        const val MIN_RESUME_MS = 5_000L

        /** 离结尾不足这么久算「看完了」，下次从头开始。 */
        const val TAIL_MS = 30_000L

        /** 列表上限：主页只露前几条，留一点余量给「最近看的顺序」调整。 */
        const val MAX_RESUME = 30

        /** 记多少部剧的「已看」；超出丢最旧的。 */
        const val MAX_WATCHED_SERIES = 50

        /** 每部剧记多少集。 */
        const val MAX_WATCHED_PER_SERIES = 500

        /**
         * 剧集标识：主机 + 剧名。
         *
         * 带上主机是为了不让两个不同站点的同名剧互相污染「已看」——「已看」是按剧集整体
         * 记的，串了的话用户会看到「明明没看过却打着勾」。
         */
        fun seriesKey(seriesTitle: String, anyEpisodeUrl: String): String {
            val host = runCatching { java.net.URI(anyEpisodeUrl).host }.getOrNull().orEmpty()
            return "$host|$seriesTitle"
        }

        fun create(context: Context): PlaybackPreferences =
            PlaybackPreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

package dev.aurora.browser.media

/**
 * 播放器里「这一集 / 下一集」的当前状态。
 *
 * 单独抽出来是因为切集这件事的**判定**全在这里，而它有几个很容易写错的地方，值得写单测
 * 钉住：`index` 可能不在列表里（当前页没被渲染成链接，见 [EpisodeList.currentIndex]），
 * 也可能是最后一集；[next] / [prev] 只在真的还有下一集 / 上一集时才给东西。
 *
 * 刻意**不持有播放器**：这里只回答「下一集是哪一集」，真正换地址、查进度、报错都在
 * [dev.aurora.browser.media.PlayerActivity] 里做。这样这份逻辑在 Robolectric 下不用起
 * ExoPlayer 就能测。
 *
 * @param list 判出来的选集；单集视频为 `null`。
 * @param index 当前集下标；`-1` 表示不在列表里。不在列表里时 [next] 取第 0 集——
 *   站点把正在播的那集渲染成 `<span>` 时就是这个情况，此时「下一集」仍然应该是列表里的
 *   第一条（而不是没有下一集）。
 * @param autoNext 片尾要不要自动播下一集。关掉时 UI 仍会弹倒计时，只是默认不动。
 */
data class EpisodePlayback(
    val list: EpisodeList?,
    val index: Int,
    val autoNext: Boolean,
) {
    val hasEpisodes: Boolean get() = (list?.size ?: 0) > 0

    /** 一季多少集；单集视频为 0。 */
    val count: Int get() = list?.size ?: 0

    /** 下一集的下标；没有下一集（单集视频、最后一集）时为 `null`。 */
    val nextIndex: Int?
        get() {
            val l = list ?: return null
            if (l.size == 0) return null
            val i = if (index < 0) 0 else index + 1
            return if (i < l.size) i else null
        }

    /** 下一集；没有下一集时为 `null`。 */
    val next: Episode? get() = nextIndex?.let { list?.episodes?.getOrNull(it) }

    /**
     * 上一集的下标；没有上一集（单集视频、第一集、当前页不在列表里）时为 `null`。
     *
     * `index < 0` 时不给「上一集」：[nextIndex] 那种情况能退回第 0 集，是因为「往后走」总有个
     * 明确的起点，而「往前走」没有——连当前是第几集都不知道，蒙一个只会把用户送错地方。
     */
    val prevIndex: Int?
        get() {
            val l = list ?: return null
            if (index <= 0 || index >= l.size) return null
            return index - 1
        }

    /** 上一集；没有上一集时为 `null`。 */
    val prev: Episode? get() = prevIndex?.let { list?.episodes?.getOrNull(it) }

    fun episode(i: Int): Episode? = list?.episodes?.getOrNull(i)

    /** 当前集的下标；不在列表里时给 `-1`，别把 `-1` 当成「第 0 集」。 */
    fun currentIndex(): Int = if (episode(index) != null) index else -1

    /**
     * 标题行显示的文本。
     *
     * 有选集时给「剧名 · 第 N 集」，让用户一眼知道在看第几集——站点的页面标题往往只有剧名。
     * 集号优先用 URL 末段的尾数字（[EpisodeCodec.episodeNumber]），取不到就退回列表里的
     * 原始标签（可能是「第 3 集」也可能是「EP03」）。判不出剧集时原样回退到 [fallback]。
     */
    fun title(fallback: String): String {
        val l = list ?: return fallback
        val ep = episode(index) ?: return fallback
        val series = l.seriesTitle.ifBlank { fallback }
        val label = episodeLabel(ep)
        if (label.isBlank()) return series
        return "$series · $label"
    }

    /** 集标签：URL 里的集号优先（「第 12 集」比页面上的「12」清楚），没有就用原标签。 */
    fun episodeLabel(ep: Episode): String {
        val n = number(ep)
        return if (n != null) "第 $n 集" else ep.label
    }

    /** 当前集的标签；当前页不在列表里时为 `null`。 */
    fun currentLabel(): String? = episode(index)?.let { episodeLabel(it) }

    /**
     * 集号。既是格子里的短名（「12」比「第 12 集」在 5 列网格里放得下），
     * 也是「已看」标记的键——见 [dev.aurora.browser.media.PlaybackPreferences.markWatched]。
     */
    fun number(ep: Episode): Int? = EpisodeCodec.episodeNumber(ep.url, ep.label)

    /** 格子里的短名：集号优先；没有集号就用原标签截断。 */
    fun shortLabel(ep: Episode): String = number(ep)?.toString() ?: ep.label.take(4)

    fun withIndex(i: Int): EpisodePlayback = copy(index = i)

    fun withAutoNext(enabled: Boolean): EpisodePlayback = copy(autoNext = enabled)

    companion object {
        /** 单集视频。 */
        val Single = EpisodePlayback(list = null, index = -1, autoNext = true)

        fun of(list: EpisodeList?, autoNext: Boolean): EpisodePlayback =
            EpisodePlayback(list = list, index = list?.currentIndex ?: -1, autoNext = autoNext)
    }
}

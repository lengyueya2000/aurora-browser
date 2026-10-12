package dev.aurora.browser.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 播放器里的选集状态：上一集 / 下一集是哪一集、标题怎么显示。
 *
 * 重点钉住几个容易写错的地方：当前集不在列表里（`index = -1`）时下一集仍应是第 0 集，
 * 但上一集该是「没有」；最后一集不能再给出下一集，第一集不能再给出上一集。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodePlaybackTest {

    private fun list(vararg urls: String, current: Int = 0, series: String = "功夫女足"): EpisodeList =
        EpisodeList(
            seriesTitle = series,
            episodes = urls.mapIndexed { i, u -> Episode(label = "第 ${i + 1} 集", url = u) },
            currentIndex = current,
        )

    private val show = list(
        "https://a.com/player/vod/1-82861-1.html",
        "https://a.com/player/vod/1-82861-2.html",
        "https://a.com/player/vod/1-82861-3.html",
    )

    // ── 单集视频 ───────────────────────────────────────────────

    @Test fun aSingleVideoHasNoEpisodesAndNoNext() {
        val p = EpisodePlayback.Single
        assertThat(p.hasEpisodes).isFalse()
        assertThat(p.next).isNull()
        assertThat(p.title("某个视频")).isEqualTo("某个视频")
    }

    // ── 下一集 ─────────────────────────────────────────────────

    @Test fun nextIsTheFollowingEpisode() {
        val p = EpisodePlayback.of(show, autoNext = true).withIndex(1)
        assertThat(p.nextIndex).isEqualTo(2)
        assertThat(p.next?.url).isEqualTo("https://a.com/player/vod/1-82861-3.html")
    }

    @Test fun theLastEpisodeHasNoNext() {
        val p = EpisodePlayback.of(show, autoNext = true).withIndex(2)
        assertThat(p.nextIndex).isNull()
        assertThat(p.next).isNull()
    }

    /** 站点把正在播的那一集渲染成 `<span>` 时 `currentIndex` 是 -1，下一集该是列表第一条。 */
    @Test fun anUnlistedCurrentEpisodeFallsBackToTheFirstEntry() {
        val p = EpisodePlayback.of(show, autoNext = true).withIndex(-1)
        assertThat(p.currentIndex()).isEqualTo(-1)
        assertThat(p.nextIndex).isEqualTo(0)
        assertThat(p.next?.url).isEqualTo("https://a.com/player/vod/1-82861-1.html")
    }

    @Test fun anOutOfRangeIndexHasNoNext() {
        val p = EpisodePlayback.of(show, autoNext = true).withIndex(9)
        assertThat(p.nextIndex).isNull()
        assertThat(p.next).isNull()
        assertThat(p.currentIndex()).isEqualTo(-1)
    }

    @Test fun anEmptyEpisodeArrayIsTreatedAsASingleVideo() {
        val p = EpisodePlayback.of(EpisodeList("剧", emptyList(), -1), autoNext = true)
        assertThat(p.hasEpisodes).isFalse()
        assertThat(p.next).isNull()
    }

    // ── 上一集 ─────────────────────────────────────────────────

    @Test fun prevIsTheEpisodeBefore() {
        val p = EpisodePlayback.of(show, autoNext = true).withIndex(2)
        assertThat(p.prevIndex).isEqualTo(1)
        assertThat(p.prev?.url).isEqualTo("https://a.com/player/vod/1-82861-2.html")
    }

    @Test fun theFirstEpisodeHasNoPrev() {
        val p = EpisodePlayback.of(show, autoNext = true).withIndex(0)
        assertThat(p.prevIndex).isNull()
        assertThat(p.prev).isNull()
    }

    /**
     * 当前页不在列表里时**不**给上一集。
     *
     * 与 [anUnlistedCurrentEpisodeFallsBackToTheFirstEntry] 的「下一集退回第 0 集」是刻意
     * 不对称的：往后走总有起点，往前走没有，蒙一个会把用户送到别的地方。
     */
    @Test fun anUnlistedCurrentEpisodeHasNoPrev() {
        val p = EpisodePlayback.of(show, autoNext = true).withIndex(-1)
        assertThat(p.prevIndex).isNull()
        assertThat(p.prev).isNull()
    }

    @Test fun anOutOfRangeIndexHasNoPrev() {
        assertThat(EpisodePlayback.of(show, autoNext = true).withIndex(9).prevIndex).isNull()
    }

    @Test fun aSingleVideoHasNoPrev() {
        assertThat(EpisodePlayback.Single.prevIndex).isNull()
        assertThat(EpisodePlayback.Single.prev).isNull()
    }

    // ── 标题 ───────────────────────────────────────────────────

    @Test fun theTitleShowsTheSeriesAndTheEpisodeNumber() {
        val p = EpisodePlayback.of(show, autoNext = true).withIndex(1)
        assertThat(p.title("功夫女足第2集")).isEqualTo("功夫女足 · 第 2 集")
    }

    /** 集号从 URL 末段取，比页面上可能写着「12」的标签清楚。 */
    @Test fun theEpisodeNumberComesFromTheUrlNotTheLabel() {
        val l = EpisodeList(
            seriesTitle = "某剧",
            episodes = listOf(Episode(label = "12", url = "https://a.com/p/7-3-12.html")),
            currentIndex = 0,
        )
        assertThat(EpisodePlayback.of(l, autoNext = true).title("x")).isEqualTo("某剧 · 第 12 集")
    }

    @Test fun aLabelWithoutANumberIsUsedAsIs() {
        val l = EpisodeList(
            seriesTitle = "某剧",
            episodes = listOf(Episode(label = "预告", url = "https://a.com/p/trailer")),
            currentIndex = 0,
        )
        assertThat(EpisodePlayback.of(l, autoNext = true).title("x")).isEqualTo("某剧 · 预告")
    }

    @Test fun anUnlistedEpisodeKeepsTheOriginalTitle() {
        val p = EpisodePlayback.of(show, autoNext = true).withIndex(-1)
        assertThat(p.title("功夫女足第2集")).isEqualTo("功夫女足第2集")
    }

    // ── 开关与不可变性 ─────────────────────────────────────────

    @Test fun theAutoNextFlagIsCarriedButDoesNotChangeTheNextEpisode() {
        val off = EpisodePlayback.of(show, autoNext = false)
        assertThat(off.autoNext).isFalse()
        assertThat(off.next?.url).isEqualTo("https://a.com/player/vod/1-82861-2.html")
        assertThat(off.withAutoNext(true).autoNext).isTrue()
        assertThat(off.autoNext).isFalse()   // 原对象没被改
    }

    @Test fun ofTakesTheCurrentIndexFromTheList() {
        val p = EpisodePlayback.of(list("https://a.com/p/1", "https://a.com/p/2", "https://a.com/p/3", current = 2), autoNext = true)
        assertThat(p.index).isEqualTo(2)
        assertThat(p.currentIndex()).isEqualTo(2)
    }
}

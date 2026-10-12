package dev.aurora.browser.media

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 记忆播放进度与倍速。核心是「什么时候**不**记」——记错了比不记更烦：
 * 看到第 3 秒就退出，下次「接着播」等于从头播；看完最后 10 秒退出，下次直接跳到结尾。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackPreferencesTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = PlaybackPreferences.create(context)

    private val url = "https://c.com/player/vod/1-82861-1.html"

    @Test fun unknownVideoStartsFromZero() {
        assertThat(prefs.resumeMs(url)).isEqualTo(0L)
    }

    @Test fun positionIsRememberedAndReadBack() {
        prefs.remember(url, positionMs = 754_000L, durationMs = 3_600_000L)
        assertThat(prefs.resumeMs(url)).isEqualTo(754_000L)
    }

    /** 不同视频各记各的，键按 URL 区分。 */
    @Test fun positionsAreKeptPerVideo() {
        prefs.remember(url, 100_000L, 3_600_000L)
        prefs.remember("https://c.com/other", 200_000L, 3_600_000L)
        assertThat(prefs.resumeMs(url)).isEqualTo(100_000L)
        assertThat(prefs.resumeMs("https://c.com/other")).isEqualTo(200_000L)
    }

    @Test fun tooShallowPositionsAreNotRemembered() {
        prefs.remember(url, positionMs = 2_000L, durationMs = 3_600_000L)
        assertThat(prefs.resumeMs(url)).isEqualTo(0L)
    }

    /** 已经看到结尾的视频下次从头播，而不是跳到最后 10 秒。 */
    @Test fun finishedVideosAreNotResumed() {
        prefs.remember(url, positionMs = 3_590_000L, durationMs = 3_600_000L)
        assertThat(prefs.resumeMs(url)).isEqualTo(0L)
    }

    /** 时长还没读出来时不能按「看完了」处理，否则长视频看到一半就丢了进度。 */
    @Test fun unknownDurationStillRemembers() {
        prefs.remember(url, positionMs = 600_000L, durationMs = 0L)
        assertThat(prefs.resumeMs(url)).isEqualTo(600_000L)
    }

    @Test fun forgetClearsThePosition() {
        prefs.remember(url, 600_000L, 3_600_000L)
        prefs.forget(url)
        assertThat(prefs.resumeMs(url)).isEqualTo(0L)
    }

    @Test fun speedDefaultsToOneAndRoundTrips() {
        assertThat(prefs.speed()).isEqualTo(1f)
        prefs.rememberSpeed(1.5f)
        assertThat(prefs.speed()).isEqualTo(1.5f)
    }

    /** 只接受界面上给出的档位，别的值写进去等于给下次启动埋一个怪倍速。 */
    @Test fun unsupportedSpeedIsIgnored() {
        prefs.rememberSpeed(1.5f)
        prefs.rememberSpeed(1.7f)
        assertThat(prefs.speed()).isEqualTo(1.5f)
    }

    /** 偏好文件被写坏（或来自更老的版本）时回落到 1x，而不是把怪值喂给播放器。 */
    @Test fun corruptedSpeedFallsBackToOne() {
        context.getSharedPreferences("aurora_playback", Context.MODE_PRIVATE)
            .edit()
            .putFloat("speed", 12f)
            .commit()
        assertThat(PlaybackPreferences.create(context).speed()).isEqualTo(1f)
    }

    // ---------- 主页「继续观看」 ----------

    private val other = "https://c.com/player/vod/2-100-1.html"

    @Test fun resumeListStartsEmpty() {
        assertThat(prefs.resumable()).isEmpty()
    }

    /** 卡片要显示「哪一集」，还要能把 Referer 交回播放器，所以标题与来源页都得存下来。 */
    @Test fun resumeEntryKeepsTitleAndReferer() {
        prefs.remember(url, 600_000L, 3_600_000L, title = "第 1 集", referer = "https://c.com/play/1")
        val entry = prefs.resumable().single()
        assertThat(entry.url).isEqualTo(url)
        assertThat(entry.title).isEqualTo("第 1 集")
        assertThat(entry.referer).isEqualTo("https://c.com/play/1")
        assertThat(entry.positionMs).isEqualTo(600_000L)
        assertThat(entry.durationMs).isEqualTo(3_600_000L)
    }

    /** 标题为空时退回地址，卡片上不能是一行空白。 */
    @Test fun blankTitleFallsBackToTheUrl() {
        prefs.remember(url, 600_000L, 3_600_000L, title = "  ")
        assertThat(prefs.resumable().single().title).isEqualTo(url)
    }

    @Test fun blankRefererIsStoredAsNothing() {
        prefs.remember(url, 600_000L, 3_600_000L, referer = "   ")
        assertThat(prefs.resumable().single().referer).isNull()
    }

    /**
     * 选集要跟着续播记录一起存。
     *
     * 不存的话，从「继续观看」点回去的播放器手里没有选集列表，左下角那对
     * 「上一集 / 下一集」会整组消失——用户会以为功能被删了（这就是线上反馈的那条）。
     */
    @Test fun resumeEntryKeepsTheEpisodeList() {
        val list = EpisodeList(
            seriesTitle = "兰香如故",
            episodes = listOf(
                Episode("第 1 集", "https://c.com/play/a"),
                Episode("第 2 集", "https://c.com/play/b"),
                Episode("第 3 集", "https://c.com/play/c"),
            ),
            currentIndex = 0,
        )
        prefs.remember(
            url, 600_000L, 3_600_000L,
            title = "兰香如故 · 第 2 集",
            episodes = EpisodeCodec.toJson(list),
            episodeIndex = 1,
        )
        val entry = prefs.resumable().single()
        val restored = EpisodeCodec.fromJson(entry.episodes)
        assertThat(restored?.seriesTitle).isEqualTo("兰香如故")
        assertThat(restored?.episodes?.map { it.url })
            .containsExactly("https://c.com/play/a", "https://c.com/play/b", "https://c.com/play/c")
            .inOrder()
        // 正在播的是第 2 集，而列表里的 `current` 是页面上的第 1 集——下标必须单独存，
        // 否则切过集之后点回来会跳错集。
        assertThat(entry.episodeIndex).isEqualTo(1)
        assertThat(restored?.currentIndex).isEqualTo(0)
    }

    /** 单集视频：没有选集，存下来就是空的（不能存出一个空列表来冒充剧集）。 */
    @Test fun singleVideoKeepsNoEpisodeList() {
        prefs.remember(url, 600_000L, 3_600_000L)
        val entry = prefs.resumable().single()
        assertThat(entry.episodes).isNull()
        assertThat(entry.episodeIndex).isEqualTo(-1)
        assertThat(EpisodeCodec.fromJson(entry.episodes)).isNull()
    }

    /** 最近看的在最前——卡片第一张应该是用户最可能想接着看的那一集。 */
    @Test fun resumeListIsNewestFirst() {
        prefs.remember(url, 600_000L, 3_600_000L)
        prefs.remember(other, 300_000L, 3_600_000L)
        assertThat(prefs.resumable().map { it.url }).containsExactly(other, url).inOrder()
    }

    /** 同一集看两次只留一条，位置更新到最新。 */
    @Test fun rememberingAgainMovesToFrontWithoutDuplicating() {
        prefs.remember(url, 600_000L, 3_600_000L)
        prefs.remember(other, 300_000L, 3_600_000L)
        prefs.remember(url, 900_000L, 3_600_000L)
        val list = prefs.resumable()
        assertThat(list.map { it.url }).containsExactly(url, other).inOrder()
        assertThat(list.first().positionMs).isEqualTo(900_000L)
    }

    /** 「不记」的规则对新列表同样生效：看到第 3 秒就退出不该出现在卡片上。 */
    @Test fun shallowAndFinishedPositionsDoNotEnterTheList() {
        prefs.remember(url, 2_000L, 3_600_000L)
        prefs.remember(other, 3_590_000L, 3_600_000L)
        assertThat(prefs.resumable()).isEmpty()
    }

    @Test fun forgetRemovesTheEntryFromTheList() {
        prefs.remember(url, 600_000L, 3_600_000L)
        prefs.remember(other, 600_000L, 3_600_000L)
        prefs.forget(url)
        assertThat(prefs.resumable().map { it.url }).containsExactly(other)
    }

    @Test fun progressIsTheWatchedFraction() {
        prefs.remember(url, 900_000L, 3_600_000L)
        assertThat(prefs.resumable().single().progress).isWithin(0.001f).of(0.25f)
    }

    /** 时长还不知道时没有比例可言，进度条按 0 画（不是 100%）。 */
    @Test fun progressIsZeroWhenTheDurationIsUnknown() {
        prefs.remember(url, 900_000L, 0L)
        assertThat(prefs.resumable().single().progress).isEqualTo(0f)
    }

    /** 列表有上限，不然它会随着观看记录无限长下去。 */
    @Test fun resumeListIsCapped() {
        repeat(PlaybackPreferences.MAX_RESUME + 5) { i ->
            prefs.remember("https://c.com/v/$i", 600_000L, 3_600_000L)
        }
        assertThat(prefs.resumable()).hasSize(PlaybackPreferences.MAX_RESUME)
    }

    /**
     * 1.10 及以前只写了 `pos_<hash>` 这一个键。升级后进度不能丢——老用户点开同一集
     * 仍然要接着播，只是那条记录不会出现在「继续观看」里（没有标题和时间）。
     */
    @Test fun legacyPositionKeysAreStillResumed() {
        val legacy = "https://c.com/old"
        context.getSharedPreferences("aurora_playback", Context.MODE_PRIVATE)
            .edit()
            .putLong("pos_${legacy.hashCode()}", 480_000L)
            .commit()
        assertThat(PlaybackPreferences.create(context).resumeMs(legacy)).isEqualTo(480_000L)
        assertThat(PlaybackPreferences.create(context).resumable()).isEmpty()
    }

    /** 存储损坏时当空列表，不能让主页首屏崩掉。 */
    @Test fun corruptedResumeListReadsAsEmpty() {
        context.getSharedPreferences("aurora_playback", Context.MODE_PRIVATE)
            .edit()
            .putString("resume_v2", "{ not json")
            .commit()
        assertThat(PlaybackPreferences.create(context).resumable()).isEmpty()
    }

    /** 列表里少了 url 的记录直接跳过，其余照常读出来。 */
    @Test fun entriesWithoutAUrlAreSkipped() {
        context.getSharedPreferences("aurora_playback", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "resume_v2",
                """[{"title":"没有地址","position":600000,"duration":3600000,"at":2},
                    {"url":"$url","title":"有地址","position":600000,"duration":3600000,"at":1}]""",
            )
            .commit()
        assertThat(PlaybackPreferences.create(context).resumable().map { it.title })
            .containsExactly("有地址")
    }

    // ---------- 追剧模式 ----------

    private val show = PlaybackPreferences.seriesKey("功夫女足", url)

    /** 默认开：追剧这件事本身就是「接着看」，默认关等于这功能默认不生效。 */
    @Test fun autoNextDefaultsToOn() {
        assertThat(prefs.autoNext()).isTrue()
        prefs.rememberAutoNext(false)
        assertThat(prefs.autoNext()).isFalse()
        assertThat(PlaybackPreferences.create(context).autoNext()).isFalse()
    }

    @Test fun watchedEpisodesAreRememberedPerShow() {
        prefs.markWatched(show, 1)
        prefs.markWatched(show, 2)
        assertThat(prefs.watched(show)).containsExactly(1, 2)
        // 别的剧互不污染。
        assertThat(prefs.watched(PlaybackPreferences.seriesKey("另一部", url))).isEmpty()
    }

    /** 同一集重复标记不该重复写（否则每次播完都重写一遍 JSON）。 */
    @Test fun markingTheSameEpisodeTwiceIsANoOp() {
        prefs.markWatched(show, 3)
        prefs.markWatched(show, 3)
        assertThat(prefs.watched(show)).containsExactly(3)
    }

    /** 读回来还是集号，不因为 JSON 里存的是字符串 / 数字而漂移。 */
    @Test fun watchedSurvivesARoundTripThroughDisk() {
        prefs.markWatched(show, 12)
        assertThat(PlaybackPreferences.create(context).watched(show)).containsExactly(12)
    }

    @Test fun forgetWatchedClearsOneShow() {
        prefs.markWatched(show, 1)
        val other = PlaybackPreferences.seriesKey("另一部", url)
        prefs.markWatched(other, 1)
        prefs.forgetWatched(show)
        assertThat(prefs.watched(show)).isEmpty()
        assertThat(prefs.watched(other)).containsExactly(1)
    }

    /** 空键、负集号直接忽略：写进去只会在偏好文件里留一条谁也读不出来的垃圾。 */
    @Test fun blankKeysAndNegativeNumbersAreIgnored() {
        prefs.markWatched("", 1)
        prefs.markWatched("   ", 1)
        prefs.markWatched(show, -1)
        assertThat(prefs.watchedSeries()).isEmpty()
    }

    @Test fun watchedListIsNewestFirst() {
        val other = PlaybackPreferences.seriesKey("另一部", url)
        prefs.markWatched(show, 1)
        prefs.markWatched(other, 1)
        assertThat(prefs.watchedSeries().map { it.key }).containsExactly(other, show).inOrder()
    }

    @Test fun watchedListIsCapped() {
        repeat(PlaybackPreferences.MAX_WATCHED_SERIES + 5) { i ->
            prefs.markWatched("c.com|剧 $i", 1)
        }
        assertThat(prefs.watchedSeries()).hasSize(PlaybackPreferences.MAX_WATCHED_SERIES)
    }

    /** 存储损坏时当没有记录，不能让选集面板崩掉。 */
    @Test fun corruptedWatchedListReadsAsEmpty() {
        context.getSharedPreferences("aurora_playback", Context.MODE_PRIVATE)
            .edit()
            .putString("watched_v1", "[ not json")
            .commit()
        val fresh = PlaybackPreferences.create(context)
        assertThat(fresh.watchedSeries()).isEmpty()
        assertThat(fresh.watched(show)).isEmpty()
    }

    /** 少了 key 的记录跳过，其余照常读出来。 */
    @Test fun watchedEntriesWithoutAKeyAreSkipped() {
        context.getSharedPreferences("aurora_playback", Context.MODE_PRIVATE)
            .edit()
            .putString(
                "watched_v1",
                """[{"eps":[1,2],"at":2},{"key":"$show","eps":[7],"at":1}]""",
            )
            .commit()
        assertThat(PlaybackPreferences.create(context).watchedSeries().map { it.key })
            .containsExactly(show)
    }

    /**
     * 剧集标识带上主机：两个站点上的同名剧不能互相污染「已看」——串了的话用户会看到
     * 「明明没看过却打着勾」。
     */
    @Test fun seriesKeyIncludesTheHost() {
        val a = PlaybackPreferences.seriesKey("功夫女足", "https://a.com/p/1")
        val b = PlaybackPreferences.seriesKey("功夫女足", "https://b.com/p/1")
        assertThat(a).isNotEqualTo(b)
        assertThat(a).isEqualTo("a.com|功夫女足")
        // 同一部剧的各集在同一个站上，键跨集稳定——这是它能当「已看」的键的前提。
        assertThat(PlaybackPreferences.seriesKey("功夫女足", "https://a.com/p/9"))
            .isEqualTo(a)
    }
}

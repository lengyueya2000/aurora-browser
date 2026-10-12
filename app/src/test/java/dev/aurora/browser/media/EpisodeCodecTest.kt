package dev.aurora.browser.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 选集判定：主键分簇、排序、当前集定位、剧名清洗。
 *
 * 这是追剧模式里最需要反复调的一块，所以判定全在 Kotlin 里、脚本只负责抓候选——这些用例
 * 就是「改判定规则时不用重装页面逻辑」的底气。
 *
 * 门槛取严（同簇 ≥ 3 条且主键与当前页相同）是有意的：把「相关推荐」误判成选集会让面板冒出
 * 一堆不相关的集，宁可漏不可错。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpisodeCodecTest {

    private fun candidates(vararg rows: Triple<String, String, Boolean>): String =
        rows.joinToString(prefix = "[", postfix = "]") { (href, text, inList) ->
            """{"href":"$href","text":"$text","inList":$inList}"""
        }

    // ── 主键 ───────────────────────────────────────────────────

    @Test fun mainKeyReplacesOnlyTheTrailingDigits() {
        assertThat(EpisodeCodec.mainKey("https://www.olevod.com/player/vod/1-82861-2.html"))
            .isEqualTo("1-82861-#")
        // 详情页没有尾部数字之前那一段，落到另一簇——不拿 82861 当集号。
        assertThat(EpisodeCodec.mainKey("https://www.olevod.com/voddetail/1-82861.html"))
            .isEqualTo("1-#")
        // 末段完全没有数字：主键就是它自己。
        assertThat(EpisodeCodec.mainKey("https://4kvm.net/play/ch4k8uf4p"))
            .isEqualTo("ch4k8uf4p")
        assertThat(EpisodeCodec.mainKey("https://a.com/e/ep-12")).isEqualTo("ep-#")
    }

    @Test fun episodeNumberComesFromTheLabelThenTheUrl() {
        assertThat(EpisodeCodec.episodeNumber("https://a.com/p/1-82861-7.html", "第 7 集")).isEqualTo(7)
        assertThat(EpisodeCodec.episodeNumber("https://a.com/play", "第 3 集")).isEqualTo(3)
        assertThat(EpisodeCodec.episodeNumber("https://a.com/play", "EP05")).isEqualTo(5)
        // 标签里的前缀写法：S01E03 是第 3 集，不是第 1 集。
        assertThat(EpisodeCodec.episodeNumber("https://a.com/play", "S01E03")).isEqualTo(3)
        // 「第2季 第3集」读 3 不读 2——带「集」的那个才是集号。
        assertThat(EpisodeCodec.episodeNumber("https://a.com/play", "第2季 第3集")).isEqualTo(3)
        // 标签优先：地址末尾的 55 是 ID 的一部分（4kvm 的 ch4k8uf55 是第 2 集）。
        assertThat(EpisodeCodec.episodeNumber("https://4kvm.net/play/ch4k8uf55", "2")).isEqualTo(2)
        // 标签读不出数字时才退回地址。
        assertThat(EpisodeCodec.episodeNumber("https://a.com/p/1-82861-7.html", "播放")).isEqualTo(7)
        assertThat(EpisodeCodec.episodeNumber("https://a.com/play", "简介")).isNull()
    }

    // ── 分簇与门槛 ─────────────────────────────────────────────

    @Test fun clustersEpisodesOfTheCurrentShow() {
        val raw = candidates(
            Triple("/player/vod/1-82861-3.html", "第 3 集", true),
            Triple("/player/vod/1-82861-1.html", "第 1 集", true),
            Triple("/player/vod/1-82861-2.html", "第 2 集", true),
        )
        val list = EpisodeCodec.parse(raw, "https://www.olevod.com/player/vod/1-82861-2.html", "功夫女足")
        assertThat(list).isNotNull()
        assertThat(list!!.episodes.map { it.label }).containsExactly("第 1 集", "第 2 集", "第 3 集").inOrder()
        assertThat(list.currentIndex).isEqualTo(1)
    }

    @Test fun relativeHrefsAreResolvedAgainstThePage() {
        val raw = candidates(
            Triple("1-82861-1.html", "1", true),
            Triple("1-82861-2.html", "2", true),
            Triple("1-82861-3.html", "3", true),
        )
        val list = EpisodeCodec.parse(raw, "https://www.olevod.com/player/vod/1-82861-2.html", "剧")
        assertThat(list!!.episodes.map { it.url }).containsExactly(
            "https://www.olevod.com/player/vod/1-82861-1.html",
            "https://www.olevod.com/player/vod/1-82861-2.html",
            "https://www.olevod.com/player/vod/1-82861-3.html",
        ).inOrder()
    }

    @Test fun twoEpisodesAreNotAShow() {
        // 2 条太容易是「上一集 / 下一集」两个按钮，不足以判定这是一部剧。
        val raw = candidates(
            Triple("/p/1-2-1.html", "上一集", true),
            Triple("/p/1-2-2.html", "下一集", true),
        )
        assertThat(EpisodeCodec.parse(raw, "https://a.com/p/1-2-2.html", "剧")).isNull()
    }

    @Test fun relatedVideosInAnotherClusterAreIgnored() {
        // 4 条「相关推荐」（另一部剧），主键与当前页不同——不能混进来。
        val raw = candidates(
            Triple("/p/1-82861-1.html", "第 1 集", true),
            Triple("/p/1-82861-2.html", "第 2 集", true),
            Triple("/p/1-82861-3.html", "第 3 集", true),
            Triple("/p/9-99999-1.html", "推荐 1", false),
            Triple("/p/9-99999-2.html", "推荐 2", false),
            Triple("/p/9-99999-3.html", "推荐 3", false),
        )
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-82861-2.html", "剧")
        assertThat(list!!.episodes).hasSize(3)
        assertThat(list.episodes.none { it.url.contains("99999") }).isTrue()
    }

    @Test fun aClusterOutsideTheContainerNeedsTheContainerSignal() {
        // 页面上有容器标记的候选时，只有落在容器里的那一簇算数。
        val raw = candidates(
            Triple("/p/1-1.html", "1", true),
            Triple("/p/1-2.html", "2", true),
            Triple("/p/1-3.html", "3", true),
            Triple("/q/2-1.html", "1", false),
            Triple("/q/2-2.html", "2", false),
            Triple("/q/2-3.html", "3", false),
            Triple("/q/2-4.html", "4", false),
        )
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-2.html", "剧")
        assertThat(list!!.episodes.map { it.url })
            .containsExactly("https://a.com/p/1-1.html", "https://a.com/p/1-2.html", "https://a.com/p/1-3.html")
            .inOrder()
    }

    @Test fun fallsBackToTheQueryParamRule() {
        val raw = candidates(
            Triple("/play?ep=1", "第 1 集", false),
            Triple("/play?ep=2", "第 2 集", false),
            Triple("/play?ep=3", "第 3 集", false),
        )
        val list = EpisodeCodec.parse(raw, "https://a.com/play?ep=2", "剧")
        assertThat(list).isNotNull()
        assertThat(list!!.episodes).hasSize(3)
        assertThat(list.currentIndex).isEqualTo(1)
    }

    @Test fun theQueryParamRuleRefusesOtherParameters() {
        // `?sort=` 不是集号类参数，同一路径下只有它不同，不算选集。
        val raw = candidates(
            Triple("/list?sort=a", "a", false),
            Triple("/list?sort=b", "b", false),
            Triple("/list?sort=c", "c", false),
        )
        assertThat(EpisodeCodec.parse(raw, "https://a.com/list?sort=b", "剧")).isNull()
    }

    @Test fun aSingleEpisodeShowIsNotAShow() {
        val raw = candidates(Triple("/p/ch4k8uf4p", "正片", true))
        assertThat(EpisodeCodec.parse(raw, "https://a.com/p/ch4k8uf4p", "电影")).isNull()
    }

    // ── 自我标明的选集（地址是不透明 ID 的站点）─────────────────

    /** 带 `ep` 标记的候选。`ep` 是脚本侧「这条链接自己标明了是第几集」的结论。 */
    private fun marked(vararg rows: Triple<String, String, Boolean>): String =
        rows.joinToString(prefix = "[", postfix = "]") { (href, text, ep) ->
            """{"href":"$href","text":"$text","inList":false,"ep":$ep}"""
        }

    @Test fun opaqueEpisodeIdsAreClusteredByTheSelfMarkedLinks() {
        // 4kvm.net 的样子：47 条 /play/<不透明串>，地址之间毫无共性，主键法只能分出一条条独苗。
        // 站点自己给每条链接打了 data-episode / class="episode-link"，这是唯一的证据。
        val raw = marked(
            Triple("/play/ch4k8uf4p", "1", true),
            Triple("/play/ch4k8uf55", "2", true),
            Triple("/play/ch4k8uf5l", "3", true),
            Triple("/play/ch4k8uf61", "4", true),
        )
        val list = EpisodeCodec.parse(raw, "https://4kvm.net/play/ch4k8uf4p", "兰香如故 - 第1集 -4k影视")
        assertThat(list).isNotNull()
        assertThat(list!!.episodes.map { it.label }).containsExactly("1", "2", "3", "4").inOrder()
        assertThat(list.currentIndex).isEqualTo(0)
        assertThat(list.seriesTitle).isEqualTo("兰香如故")
    }

    @Test fun selfMarkedLinksAreSortedByTheirNumbersNotTheirOrder() {
        // 页面把当前集渲染在最前（或乱序），排序仍按集号走。
        val raw = marked(
            Triple("/play/bbb", "第 2 集", true),
            Triple("/play/ccc", "第 3 集", true),
            Triple("/play/aaa", "第 1 集", true),
        )
        val list = EpisodeCodec.parse(raw, "https://a.com/play/bbb", "剧")
        assertThat(list!!.episodes.map { it.label }).containsExactly("第 1 集", "第 2 集", "第 3 集").inOrder()
        assertThat(list.currentIndex).isEqualTo(1)
    }

    @Test fun selfMarkedLinksNeedEnoughOfThem() {
        // 2 条带标记的链接仍然不够——太容易是「上一集 / 下一集」两个按钮。
        val raw = marked(
            Triple("/play/aaa", "1", true),
            Triple("/play/bbb", "2", true),
        )
        assertThat(EpisodeCodec.parse(raw, "https://a.com/play/aaa", "剧")).isNull()
    }

    @Test fun selfMarkedLinksWithoutEpisodeNumbersAreRefused() {
        // 排不出顺序的一堆格子还不如不给面板（规格 §2.2「不做猜测」）：
        // 地址没有尾数字、标签也不是数字时，只能认输。
        val raw = marked(
            Triple("/play/aaa", "播放", true),
            Triple("/play/bbb", "播放", true),
            Triple("/play/ccc", "播放", true),
        )
        assertThat(EpisodeCodec.parse(raw, "https://a.com/play/aaa", "剧")).isNull()
    }

    @Test fun selfMarkedLinksNeedDistinctNumbers() {
        // 三条都读成同一集（标签都是 1）——那不是选集，是同一个链接的三种写法。
        val raw = marked(
            Triple("/play/aaa", "1", true),
            Triple("/play/bbb", "1", true),
            Triple("/play/ccc", "1", true),
        )
        assertThat(EpisodeCodec.parse(raw, "https://a.com/play/aaa", "剧")).isNull()
    }

    @Test fun theMainKeyStillWinsOverTheSelfMarkedLinks() {
        // 两条路都能判出来时，地址本身有结构的那条（主键法）优先：
        // 记号是站点自己写的，我们只是照信；URL 的结构是我们自己算出来的。
        val raw = """[
            {"href":"/p/1-2-1.html","text":"第 1 集","inList":true,"ep":true},
            {"href":"/p/1-2-2.html","text":"第 2 集","inList":true,"ep":true},
            {"href":"/p/1-2-3.html","text":"第 3 集","inList":true,"ep":true},
            {"href":"/x/a1","text":"1","inList":false,"ep":true},
            {"href":"/x/a2","text":"2","inList":false,"ep":true},
            {"href":"/x/a3","text":"3","inList":false,"ep":true},
            {"href":"/x/a4","text":"4","inList":false,"ep":true}
        ]"""
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-2-2.html", "剧")
        assertThat(list!!.episodes.map { it.url }).containsExactly(
            "https://a.com/p/1-2-1.html",
            "https://a.com/p/1-2-2.html",
            "https://a.com/p/1-2-3.html",
        ).inOrder()
    }

    @Test fun anUnmarkedCandidateListIsStillNotAShow() {
        // 兜底那一层的候选（inList=false、ep=false）不会被误当成「自我标明」。
        val raw = marked(
            Triple("/play/aaa", "1", false),
            Triple("/play/bbb", "2", false),
            Triple("/play/ccc", "3", false),
        )
        assertThat(EpisodeCodec.parse(raw, "https://a.com/play/aaa", "剧")).isNull()
    }

    // ── 当前集定位 ─────────────────────────────────────────────

    @Test fun currentIndexToleratesTrailingSlashAndFragment() {
        val raw = candidates(
            Triple("/p/1-2-1.html", "1", true),
            Triple("/p/1-2-2.html", "2", true),
            Triple("/p/1-2-3.html", "3", true),
        )
        val withFragment = EpisodeCodec.parse(raw, "https://a.com/p/1-2-2.html#t=30", "剧")
        assertThat(withFragment!!.currentIndex).isEqualTo(1)
    }

    @Test fun currentIndexIsMinusOneWhenThePageIsNotInTheList() {
        // 正在播的那一集被渲染成 <span> 而不是 <a>，列表里没有它——面板照常列，只是没有高亮。
        val raw = candidates(
            Triple("/p/1-2-1.html", "1", true),
            Triple("/p/1-2-3.html", "3", true),
            Triple("/p/1-2-4.html", "4", true),
        )
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-2-2.html", "剧")
        assertThat(list).isNotNull()
        assertThat(list!!.currentIndex).isEqualTo(-1)
    }

    @Test fun nextIndexStopsAtTheLastEpisode() {
        val raw = candidates(
            Triple("/p/1-2-1.html", "1", true),
            Triple("/p/1-2-2.html", "2", true),
            Triple("/p/1-2-3.html", "3", true),
        )
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-2-1.html", "剧")!!
        assertThat(list.hasNext(0)).isTrue()
        assertThat(list.nextIndex(0)).isEqualTo(1)
        assertThat(list.hasNext(2)).isFalse()
        assertThat(list.nextIndex(2)).isNull()
        assertThat(list.hasNext(-1)).isFalse()
    }

    // ── 清洗与兜底 ─────────────────────────────────────────────

    @Test fun dropsNonHttpHrefsAndDuplicates() {
        val raw = candidates(
            Triple("/p/1-2-1.html", "1", true),
            Triple("/p/1-2-1.html", "1 重复", true),
            Triple("javascript:void(0)", "2", true),
            Triple("data:text/html,x", "3", true),
            Triple("/p/1-2-2.html", "2", true),
            Triple("/p/1-2-3.html", "3", true),
        )
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-2-2.html", "剧")
        assertThat(list!!.episodes.map { it.url }).containsExactly(
            "https://a.com/p/1-2-1.html",
            "https://a.com/p/1-2-2.html",
            "https://a.com/p/1-2-3.html",
        ).inOrder()
    }

    @Test fun labelFallsBackToTheFileName() {
        val raw = candidates(
            Triple("/p/1-2-1.html", "", true),
            Triple("/p/1-2-2.html", "   ", true),
            Triple("/p/1-2-3.html", "3", true),
        )
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-2-2.html", "剧")
        assertThat(list!!.episodes.map { it.label })
            .containsExactly("1-2-1", "1-2-2", "3").inOrder()
    }

    @Test fun seriesTitleStripsEpisodeNumbersAndSiteSuffixes() {
        assertThat(EpisodeCodec.seriesTitle("功夫女足 第 1 集 - 欧乐影院", "https://www.olevod.com/p/1"))
            .isEqualTo("功夫女足")
        assertThat(EpisodeCodec.seriesTitle("漫长的季节_在线观看", "https://a.com/p/1"))
            .isEqualTo("漫长的季节")
        assertThat(EpisodeCodec.seriesTitle("Show S01E03 高清完整版", "https://a.com/p/1"))
            .isEqualTo("Show")
        // 「影视」与「影院」是同一类站名后缀：4kvm.net 的标题就是「… - 第1集 -4k影视」。
        assertThat(EpisodeCodec.seriesTitle("兰香如故 - 第1集 -4k影视", "https://4kvm.net/play/x"))
            .isEqualTo("兰香如故")
    }

    @Test fun seriesTitleFallsBackToTheHost() {
        assertThat(EpisodeCodec.seriesTitle("第 1 集", "https://www.olevod.com/p/1"))
            .isEqualTo("www.olevod.com")
        assertThat(EpisodeCodec.seriesTitle("", "https://a.com/p/1")).isEqualTo("a.com")
    }

    @Test fun badInputIsNotAShow() {
        assertThat(EpisodeCodec.parse(null, "https://a.com/p/1", "剧")).isNull()
        assertThat(EpisodeCodec.parse("", "https://a.com/p/1", "剧")).isNull()
        assertThat(EpisodeCodec.parse("null", "https://a.com/p/1", "剧")).isNull()
        assertThat(EpisodeCodec.parse("[]", "https://a.com/p/1", "剧")).isNull()
        assertThat(EpisodeCodec.parse("{oops", "https://a.com/p/1", "剧")).isNull()
        assertThat(EpisodeCodec.parse("""[{"text":"没有 href"}]""", "https://a.com/p/1", "剧")).isNull()
        assertThat(EpisodeCodec.parse(candidates(Triple("/p/1-2-1.html", "1", true)), "not a url", "剧"))
            .isNull()
    }

    @Test fun tooManyEpisodesAreTruncated() {
        val raw = (1..400).joinToString(prefix = "[", postfix = "]") {
            """{"href":"/p/1-2-$it.html","text":"$it","inList":true}"""
        }
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-2-1.html", "剧")
        assertThat(list!!.episodes).hasSize(EpisodeCodec.MAX_EPISODES)
        // 截断的是尾部（第 300 集之后），不是开头。
        assertThat(list.episodes.first().label).isEqualTo("1")
        assertThat(list.episodes.last().label).isEqualTo("300")
    }

    @Test fun candidateListIsCappedBeforeResolving() {
        val raw = (1..1000).joinToString(prefix = "[", postfix = "]") {
            """{"href":"/p/1-2-$it.html","text":"$it","inList":true}"""
        }
        // 不崩、不超上限即可；上限内的条目仍然可用。
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-2-1.html", "剧")
        assertThat(list!!.episodes).hasSize(EpisodeCodec.MAX_EPISODES)
    }

    // ── Intent 编解码 ──────────────────────────────────────────

    @Test fun jsonRoundTripKeepsTheList() {
        val raw = candidates(
            Triple("/p/1-2-1.html", "第 1 集", true),
            Triple("/p/1-2-2.html", "第 2 集", true),
            Triple("/p/1-2-3.html", "第 3 集", true),
        )
        val list = EpisodeCodec.parse(raw, "https://a.com/p/1-2-2.html", "功夫女足 第 2 集")!!
        val back = EpisodeCodec.fromJson(EpisodeCodec.toJson(list))
        assertThat(back).isEqualTo(list)
    }

    @Test fun badJsonIsNotAList() {
        assertThat(EpisodeCodec.fromJson(null)).isNull()
        assertThat(EpisodeCodec.fromJson("")).isNull()
        assertThat(EpisodeCodec.fromJson("{oops")).isNull()
        assertThat(EpisodeCodec.fromJson("""{"episodes":[]}""")).isNull()
        assertThat(EpisodeCodec.fromJson("""{"episodes":[{"label":"1"}]}""")).isNull()
    }
}

package dev.aurora.browser.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 视频源提取：解析脚本返回值、区分可下载与流式地址。
 *
 * 为什么需要这套东西：`DownloadListener` 只在浏览器「决定不渲染、转交下载」时触发，
 * HTML5 的 `<video>` 是内联渲染的，点它不会触发任何下载回调——所以必须自己进页面里找源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoExtractorTest {

    @Test fun parseReadsUrlAndType() {
        val raw = """[{"url":"https://a.com/v.mp4","type":"video/mp4"}]"""
        val sources = VideoExtractor.parse(raw)
        assertThat(sources).hasSize(1)
        assertThat(sources[0].url).isEqualTo("https://a.com/v.mp4")
        assertThat(sources[0].type).isEqualTo("video/mp4")
        assertThat(sources[0].label).isEqualTo("v.mp4")
        assertThat(sources[0].isStream).isFalse()
    }

    @Test fun parseDropsDuplicatesAndEntriesWithoutAUrl() {
        val raw = """
            [
              {"url":"https://a.com/v.mp4","type":""},
              {"url":"https://a.com/v.mp4","type":"video/mp4"},
              {"type":"video/mp4"},
              {"url":"   "}
            ]
        """.trimIndent()
        assertThat(VideoExtractor.parse(raw)).hasSize(1)
    }

    /** 脚本抛错时 `evaluateJavascript` 会回传字面量 "null"，必须当空列表而不是崩掉。 */
    @Test fun parseToleratesNullAndGarbage() {
        assertThat(VideoExtractor.parse(null)).isEmpty()
        assertThat(VideoExtractor.parse("")).isEmpty()
        assertThat(VideoExtractor.parse("null")).isEmpty()
        assertThat(VideoExtractor.parse("not json at all")).isEmpty()
        assertThat(VideoExtractor.parse("{}")).isEmpty()
    }

    /**
     * blob: / data: 只在当前页面会话里有效：DownloadManager 下不了，投屏设备也播不了。
     * 站点用 MSE 播放时视频源就是 blob——必须标出来，界面才能给出「不支持」而不是
     * 一个点了没反应的按钮。
     */
    @Test fun streamSourcesAreMarked() {
        val raw = """[{"url":"blob:https://a.com/8f3c-1","type":""},
                      {"url":"data:video/mp4;base64,AAAA","type":""},
                      {"url":"https://a.com/real.mp4","type":"video/mp4"}]"""
        val sources = VideoExtractor.parse(raw)
        assertThat(sources.map { it.isStream }).containsExactly(true, true, false).inOrder()
        assertThat(sources[0].label).isEqualTo("流式视频")
        assertThat(sources[0].subtitle).isEqualTo("blob 地址 · 无法下载")
        assertThat(sources[1].subtitle).isEqualTo("data 地址 · 无法下载")
        assertThat(sources[2].subtitle).isEqualTo("a.com · video/mp4")
    }

    @Test fun labelFallsBackToHostThenToTheRawUrl() {
        assertThat(VideoExtractor.labelOf("https://a.com/dir/movie.mp4?x=1")).isEqualTo("movie.mp4")
        assertThat(VideoExtractor.labelOf("https://a.com/")).isEqualTo("a.com")
        // blob: 的「路径」是会话 UUID，显示出来会占满三行且没有信息量。
        assertThat(VideoExtractor.labelOf("blob:https://a.com/8f3c-1")).isEqualTo("流式视频")
    }

    /** 脚本要同时看 `<video>` 自身的地址和它的 `<source>` 子元素。 */
    @Test fun scriptLooksAtVideoElementsAndTheirSources() {
        val script = VideoExtractor.SCRIPT
        assertThat(script).contains("querySelectorAll('video')")
        assertThat(script).contains("querySelectorAll('source')")
        assertThat(script).contains("currentSrc")
        // 相对地址要解析成绝对地址，否则下载和投屏都拿不到东西。
        assertThat(script).contains("document.baseURI")
        // 返回数组而不是字符串：evaluateJavascript 会再编码一次，返回字符串得解析两层。
        assertThat(script).contains("return out;")
    }
}

package dev.aurora.browser.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 站点图标的解析。这里全是纯字节/字符串逻辑——真正容易出错的部分（ICO 容器、页面里
 * 声明的图标地址）都在这一层，所以单独测。
 */
class FaviconCodecTest {

    // ---------- ICO 容器 ----------

    /** 造一个 ICO：目录 + 若干张图，每张图的负载是给定的字节。 */
    private fun ico(vararg entries: Pair<Pair<Int, Int>, ByteArray>): ByteArray {
        val header = byteArrayOf(0, 0, 1, 0, entries.size.toByte(), 0)
        val directory = ByteArray(entries.size * 16)
        val payloads = mutableListOf<ByteArray>()
        var offset = 6 + directory.size
        entries.forEachIndexed { i, (size, payload) ->
            val at = i * 16
            val (width, height) = size
            directory[at] = width.toByte()
            directory[at + 1] = height.toByte()
            // 负载长度（小端）与偏移（小端）
            writeU32(directory, at + 8, payload.size)
            writeU32(directory, at + 12, offset)
            payloads += payload
            offset += payload.size
        }
        return header + directory + payloads.fold(ByteArray(0)) { acc, b -> acc + b }
    }

    private fun writeU32(target: ByteArray, at: Int, value: Int) {
        target[at] = value.toByte()
        target[at + 1] = (value shr 8).toByte()
        target[at + 2] = (value shr 16).toByte()
        target[at + 3] = (value shr 24).toByte()
    }

    private fun png(size: Int = 32): ByteArray {
        val magic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        return magic + ByteArray(size) { (it % 251).toByte() }
    }

    private val bmpPayload = byteArrayOf(0x28, 0, 0, 0, 1, 2, 3, 4)

    @Test fun icoIsRecognised() {
        assertThat(FaviconCodec.isIco(ico(16 to 16 to png()))).isTrue()
        assertThat(FaviconCodec.isIco(png())).isFalse()
        assertThat(FaviconCodec.isIco(byteArrayOf(0, 0))).isFalse()
    }

    /** 挑最大的那张：16×16 的图标放到 52dp 的方块上会糊。 */
    @Test fun theLargestEmbeddedPngWins() {
        val small = png(8)
        val large = png(64)
        val bytes = ico(16 to 16 to small, 64 to 64 to large)
        assertThat(FaviconCodec.pngFromIco(bytes)).isEqualTo(large)
    }

    /** 宽度/高度写 0 表示 256——最大的那张常常正是这么标的。 */
    @Test fun zeroDimensionsMean256() {
        val tiny = png(8)
        val big = png(64)
        val bytes = ico(16 to 16 to tiny, 0 to 0 to big)
        assertThat(FaviconCodec.pngFromIco(bytes)).isEqualTo(big)
    }

    /** 老式 ICO 内嵌 BMP，抠不出 PNG：回 null，让调用方去试下一个候选，而不是解出一堆乱码。 */
    @Test fun aBmpOnlyIcoYieldsNothing() {
        assertThat(FaviconCodec.pngFromIco(ico(16 to 16 to bmpPayload))).isNull()
    }

    /** 目录项指向文件外（截断/损坏的 ICO）时跳过，不要越界读。 */
    @Test fun anOutOfRangeEntryIsSkipped() {
        val bytes = ico(16 to 16 to png())
        // 把负载偏移改到文件末尾之外
        writeU32(bytes, 6 + 12, 1_000_000)
        assertThat(FaviconCodec.pngFromIco(bytes)).isNull()
    }

    @Test fun aTruncatedIcoYieldsNothing() {
        assertThat(FaviconCodec.pngFromIco(byteArrayOf(0, 0, 1, 0, 2, 0))).isNull()
        assertThat(FaviconCodec.pngFromIco(byteArrayOf(0, 0))).isNull()
    }

    /** 不是 ICO 就原样交出去，能解不能解由 decode 判断。 */
    @Test fun nonIcoBytesPassThrough() {
        val bytes = png()
        assertThat(FaviconCodec.normalizeIcon(bytes)).isEqualTo(bytes)
    }

    @Test fun anIcoIsNormalisedToItsPng() {
        val payload = png(64)
        assertThat(FaviconCodec.normalizeIcon(ico(64 to 64 to payload))).isEqualTo(payload)
    }

    @Test fun emptyBytesYieldNothing() {
        assertThat(FaviconCodec.normalizeIcon(ByteArray(0))).isNull()
    }

    // ---------- 页面里声明的图标 ----------

    private val page = "https://news.example.com/section/index.html"

    @Test fun plainIconLinkIsFound() {
        val html = """<head><link rel="icon" href="/static/favicon.png"></head>"""
        assertThat(FaviconCodec.iconLinks(html, page))
            .containsExactly("https://news.example.com/static/favicon.png")
    }

    /** 属性顺序不固定，`href` 在 `rel` 前面也得认。 */
    @Test fun attributeOrderDoesNotMatter() {
        val html = """<link href="//cdn.example.com/i.png" rel="shortcut icon">"""
        assertThat(FaviconCodec.iconLinks(html, page))
            .containsExactly("https://cdn.example.com/i.png")
    }

    /** 单引号、无引号都要认——手写 HTML 的站点两种都有。 */
    @Test fun quotesAreOptional() {
        val html = """<link rel=icon href='/a.png'><link rel="icon" href=/b.png>"""
        assertThat(FaviconCodec.iconLinks(html, page))
            .containsExactly("https://news.example.com/a.png", "https://news.example.com/b.png")
    }

    /** apple-touch-icon 排最前：它是给主屏用的 180×180 PNG，比 16×16 清楚得多。 */
    @Test fun appleTouchIconIsPreferred() {
        val html = """
            <link rel="icon" href="/small.png">
            <link rel="apple-touch-icon" href="/touch.png">
        """.trimIndent()
        assertThat(FaviconCodec.iconLinks(html, page).first())
            .isEqualTo("https://news.example.com/touch.png")
    }

    /** 相对地址按页面地址解析，不是按域名根。 */
    @Test fun relativeHrefsResolveAgainstThePage() {
        val html = """<link rel="icon" href="../img/i.png">"""
        assertThat(FaviconCodec.iconLinks(html, page))
            .containsExactly("https://news.example.com/img/i.png")
    }

    /** SVG 解不了，试了也白费一次请求，直接跳过。 */
    @Test fun svgIconsAreSkipped() {
        val html = """
            <link rel="icon" type="image/svg+xml" href="/i.svg">
            <link rel="icon" href="/i.png">
        """.trimIndent()
        assertThat(FaviconCodec.iconLinks(html, page))
            .containsExactly("https://news.example.com/i.png")
    }

    @Test fun maskIconAndDataUrlsAreSkipped() {
        val html = """
            <link rel="mask-icon" href="/mask.svg">
            <link rel="icon" href="data:image/png;base64,AAAA">
        """.trimIndent()
        assertThat(FaviconCodec.iconLinks(html, page)).isEmpty()
    }

    /** 与图标无关的 link（样式表、预加载）不能被当成图标。 */
    @Test fun unrelatedLinksAreIgnored() {
        val html = """
            <link rel="stylesheet" href="/a.css">
            <link rel="preconnect" href="https://cdn.example.com">
            <link rel="canonical" href="https://news.example.com/x">
        """.trimIndent()
        assertThat(FaviconCodec.iconLinks(html, page)).isEmpty()
    }

    @Test fun duplicateDeclarationsCollapse() {
        val html = """
            <link rel="icon" href="/i.png">
            <link rel="shortcut icon" href="/i.png">
        """.trimIndent()
        assertThat(FaviconCodec.iconLinks(html, page))
            .containsExactly("https://news.example.com/i.png")
    }

    @Test fun aPageWithoutIconsYieldsNothing() {
        assertThat(FaviconCodec.iconLinks("<html><body>hi</body></html>", page)).isEmpty()
    }

    // ---------- 取图流程 ----------

    /** 根目录的 favicon.ico 命中就不必再去读首页了。 */
    @Test fun theWellKnownPathIsTriedFirstAndStopsThere() {
        val payload = png()
        val requested = mutableListOf<String>()
        val bytes = FaviconCodec.fetchBytes("a.com") { url, _ ->
            requested += url
            if (url == "https://a.com/favicon.ico") payload else null
        }
        assertThat(bytes).isEqualTo(payload)
        assertThat(requested).containsExactly("https://a.com/favicon.ico")
    }

    /** 根目录没有（或不是能解的东西）时，去首页读 <link rel="icon">。 */
    @Test fun theDeclaredIconIsUsedWhenTheWellKnownPathFails() {
        val payload = png()
        val requested = mutableListOf<String>()
        val bytes = FaviconCodec.fetchBytes("a.com") { url, _ ->
            requested += url
            when (url) {
                "https://a.com/" -> """<link rel="icon" href="/i.png">""".toByteArray()
                "https://a.com/i.png" -> payload
                else -> null
            }
        }
        assertThat(bytes).isEqualTo(payload)
        assertThat(requested).containsExactly("https://a.com/favicon.ico", "https://a.com/", "https://a.com/i.png")
            .inOrder()
    }

    /** 声明了好几个时，前一个取不到就试下一个。 */
    @Test fun itFallsThroughToTheNextCandidate() {
        val payload = png()
        val html = """<link rel="apple-touch-icon" href="/missing.png"><link rel="icon" href="/ok.png">"""
            .toByteArray()
        val bytes = FaviconCodec.fetchBytes("a.com") { url, _ ->
            val body: ByteArray? = when (url) {
                "https://a.com/" -> html
                "https://a.com/ok.png" -> payload
                else -> null
            }
            body
        }
        assertThat(bytes).isEqualTo(payload)
    }

    /** 全都取不到就回 null，界面退回首字母块。 */
    @Test fun nothingAvailableYieldsNull() {
        assertThat(FaviconCodec.fetchBytes("a.com") { _, _ -> null }).isNull()
    }

    /** 首页返回的不是 HTML（比如整页是个 JS）时不该崩，也不该去请求奇怪的地址。 */
    @Test fun aNonHtmlHomepageYieldsNull() {
        assertThat(
            FaviconCodec.fetchBytes("a.com") { url, _ ->
                val body: ByteArray? = if (url.endsWith("/")) byteArrayOf(0, 1, 2, 3) else null
                body
            },
        ).isNull()
    }
}

package dev.aurora.browser.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * `rmtt4.com` 站点专用规则。
 *
 * 和 [AdBlockScriptTest] 一样，断言的是「脚本里到底做了什么」——这类代码没有编译期保护，
 * 选择器写歪、特征串拼错，Kotlin 侧一切正常，只有真机上劫持照跳。
 *
 * 样本来自实测：详情页底部 40 个 `opacity:0.01` 的透明方块，点一下就跳到
 * `qiq15od.com:8005` 再转到 `yjdh859.com` 的推广页。
 */
class SiteAdRulesTest {

    @Test fun onlyAppliesToTheTargetSite() {
        assertThat(SiteAdRules.appliesTo("https://rmtt4.com/")).isTrue()
        assertThat(SiteAdRules.appliesTo("https://rmtt4.com/comics-detail/1000123")).isTrue()
        assertThat(SiteAdRules.appliesTo("https://www.rmtt4.com/x")).isTrue()
        // 别的站一律不生效——这是「站点专用」的全部意义。
        assertThat(SiteAdRules.appliesTo("https://example.com/")).isFalse()
        assertThat(SiteAdRules.appliesTo("https://notrmtt4.com/")).isFalse()
        assertThat(SiteAdRules.appliesTo("https://rmtt4.com.evil.test/")).isFalse()
        assertThat(SiteAdRules.appliesTo("not a url")).isFalse()
    }

    @Test fun scriptForReturnsEmptyForOtherSites() {
        assertThat(SiteAdRules.scriptFor("https://rmtt4.com/")).isEqualTo(SiteAdRules.SCRIPT)
        // 空串而不是 null：调用方要能无条件拼接。
        assertThat(SiteAdRules.scriptFor("https://example.com/")).isEmpty()
    }

    @Test fun matchesTheTapjackOverlayClass() {
        assertThat(SiteAdRules.TAPJACK_CLASS).isEqualTo("fbheyuxg")
        val script = SiteAdRules.SCRIPT
        // 用「包含」匹配：前缀本身是随机串，站点换后缀也要命中。
        assertThat(script).contains("[class*=\"' + TAP + '\"]")
    }

    /**
     * 底部横幅广告是另一套东西，别跟劫持方块混在一起。
     *
     * 它把一张大图切成若干 `position:fixed` 小块拼出来，每个节点单独看都不像广告，
     * 所以必须按类名整组删。
     */
    @Test fun removesTheBottomBannerAd() {
        assertThat(SiteAdRules.BANNER_CLASS).isEqualTo("ouWshumi")
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("var BANNER = 'ouWshumi';")
        assertThat(script).contains("[class*=\"' + BANNER + '\"]")
    }

    /**
     * 章节按钮的文字要完整显示。
     *
     * 站点把按钮锁成单行 + 省略号，实测 202 个按钮里 101 个被截断。这里的断言盯的是
     * 三个必须同时成立的覆盖（少一个布局就会坏，见 CHAPTER_CSS 的注释）：
     * 容器 `ul` 的 `nowrap` 要重置、`li` 要改成 `inline-block`、按钮要能换行。
     */
    @Test fun makesChapterTitlesFullyVisible() {
        val css = SiteAdRules.CHAPTER_CSS
        assertThat(css).contains(".content_playlist")
        // 1. 容器的 nowrap 必须重置，否则 li 会被挤成一行。
        assertThat(css).contains("white-space: normal !important")
        // 2. li 用 float 会因高度不齐而错位，改成 inline-block。
        assertThat(css).contains("display: inline-block !important")
        assertThat(css).contains("float: none !important")
        // 3. 按钮本身要能换行、不能被省略号截断。
        assertThat(css).contains("text-overflow: clip !important")
        assertThat(css).contains("height: auto !important")
        // inline-block 之间的空白靠 font-size:0 消掉，否则三列会挤成两列。
        assertThat(css).contains("font-size: 0 !important")

        // 样式是通过 <style> 节点注入的，且幂等。
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("aurora-chapter-style")
        assertThat(script).contains("applyChapterStyle")
        assertThat(script).contains("document.getElementById(CHAPTER_STYLE_ID)) return")
    }

    /**
     * CSS 塞进 JS 字符串必须转义。
     *
     * CSS 里有换行和引号，直接拼进 `'...'` 会拼出语法错——而这类错在页面上只表现为
     * 「注入返回值是一句 SyntaxError」，广告照放、样式没生效，没有任何提示。
     */
    @Test fun chapterCssIsSafelyEscapedIntoTheScript() {
        val script = SiteAdRules.SCRIPT
        // 用 JSON 编码的字符串字面量（双引号开头），而不是裸拼单引号。
        assertThat(script).contains("var CHAPTER_CSS = \"")
        // 换行必须被转义成 \n，不能是真实的换行符——那会直接截断 JS 字符串。
        val cssLine = script.lines().first { it.contains("var CHAPTER_CSS = ") }
        assertThat(cssLine).doesNotContain("important !")
        assertThat(script).contains("\\n")
    }

    @Test fun removesTheAgeGate() {
        assertThat(SiteAdRules.AGE_GATE_SELECTOR).isEqualTo(".wrap-dialog-t")
        assertThat(SiteAdRules.SCRIPT).contains(".wrap-dialog-t")
    }

    @Test fun knowsTheAdScriptMarkers() {
        assertThat(SiteAdRules.SCRIPT_MARKERS).containsAtLeast("fbheyuxg", "qiq15od", "6448gc")
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("hasMarker")
        // 内联脚本看正文，外链脚本看地址——两种都要判。
        assertThat(script).contains("s.src")
        assertThat(script).contains("s.textContent")
    }

    @Test fun blocksEveryAdHost() {
        assertThat(SiteAdRules.AD_HOSTS).containsAtLeast(
            "qiq15od.com",
            "82gd88v.com",
            "yjdh859.com",
            "0v3xzl6.com",
            "1ut8hmb.com",
            "u5ztyuu.com",
        )
        assertThat(SiteAdRules.isAdHost("https://pzc6448gc.qiq15od.com:8005/cc/6448")).isTrue()
        assertThat(SiteAdRules.isAdHost("https://83065hvt.yjdh859.com:8851/tg.htm")).isTrue()
        assertThat(SiteAdRules.isAdHost("https://rmtt4.com/js/home.js")).isFalse()
        // 图片 CDN 不能误伤——正文图片就在这儿。
        assertThat(SiteAdRules.isAdHost("https://img.nnpic.xyz/a.webp")).isFalse()
    }

    /**
     * 只拦域名是不够的。
     *
     * 实测：把广告域名全拦掉，40 个透明方块照样在——它们是页面内联脚本用 `document.write`
     * 写出来的，跟外部请求无关。所以删节点这一层必须有，而且要在**写入**那一层就拦。
     */
    @Test fun interceptsDocumentWriteAndInsertAdjacentHtml() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("document.write")
        assertThat(script).contains("nativeWrite")
        assertThat(script).contains("insertAdjacentHTML")
        // 写入时命中特征就直接丢弃，方块根本不会出现（零闪烁）。
        assertThat(script).contains("if (hasMarker(String(markup))) return;")
    }

    /**
     * `document.write` 只能在解析期放行。
     *
     * 这是 1.19 的一个真实回归：包装函数把非广告的 write 无条件转给原生实现，而
     * `document.write` 在文档加载完成之后调用会隐式触发 `document.open()`，把整个文档
     * 清空再重新解析——页面表现为「一直刷新」。
     *
     * 实测：加载完后调一次 write，body 文字从 2944 字掉到 4 字、`readyState` 从
     * complete 退回 loading。从阅读页后退回详情页时正好走这条路径：站点脚本重跑、
     * 注入也重跑，而那时文档已经是 complete。
     */
    @Test fun onlyForwardsDocumentWriteWhileParsing() {
        val script = SiteAdRules.SCRIPT
        // 必须在转发前判 readyState，且判据是 'loading'。
        assertThat(script).contains("document.readyState !== 'loading'")
        // 这个判断必须出现在 nativeWrite 调用之前——顺序错了等于没修。
        val guardIdx = script.indexOf("document.readyState !== 'loading'")
        val nativeIdx = script.indexOf("nativeWrite.apply")
        assertThat(guardIdx).isAtLeast(0)
        assertThat(nativeIdx).isAtLeast(0)
        assertThat(guardIdx).isLessThan(nativeIdx)
    }

    /**
     * 删 `<script>` 节点同样只在解析期做。
     *
     * 文档加载完后站点脚本还会重跑，这时删脚本节点没有任何收益，反而会扰动页面。
     */
    @Test fun removesAdScriptNodesOnlyWhileParsing() {
        val script = SiteAdRules.SCRIPT
        val marker = "五、广告脚本节点本身"
        val idx = script.indexOf(marker)
        assertThat(idx).isAtLeast(0)
        // 这一段的前面必须紧跟 readyState 判断。
        val nearby = script.substring(idx, minOf(script.length, idx + 400))
        assertThat(nearby).contains("document.readyState === 'loading'")
    }

    /**
     * WebSocket 那一路拦不住内容，只能掐连接。
     *
     * 广告用 `wss://…0v3xzl6.com:20095` 拉 base64 代码再 `new Function` 执行，内容是服务端
     * 下发的。非广告的 WebSocket 必须原样放行，否则会把站点正常功能弄坏。
     */
    @Test fun cutsAdWebSocketButKeepsOthers() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("window.WebSocket")
        assertThat(script).contains("isAdUrl(url)")
        assertThat(script).contains("new NativeWS(url)")
    }

    @Test fun observesInsertionsAndSweepsPeriodically() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("MutationObserver")
        assertThat(script).contains("childList: true")
        assertThat(script).contains("subtree: true")
        // 第二段脚本在 1000ms / 1500ms 后还会追加东西，一次性清理兜不住。
        assertThat(script).contains("setInterval")
        assertThat(script).contains("document.hidden")
    }

    @Test fun isIdempotentAndReturnsOk() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("window.__auroraSiteAd")
        assertThat(script).contains("return 'ok'")
        assertThat(script.trimStart()).startsWith("(function ()")
        assertThat(script.trimEnd()).endsWith("})();")
    }

    /** 和 AdBlockScript 一样的护栏：字符串拼错不会有编译错误，只会在页面上静默变成语法错。 */
    @Test fun bracesAndParensAreBalanced() {
        val script = SiteAdRules.SCRIPT
        assertThat(script.count { it == '{' }).isEqualTo(script.count { it == '}' })
        assertThat(script.count { it == '(' }).isEqualTo(script.count { it == ')' })
        assertThat(script.count { it == '[' }).isEqualTo(script.count { it == ']' })
    }

    @Test fun placeholdersAreInterpolated() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).doesNotContain("\$TAPJACK_CLASS")
        assertThat(script).doesNotContain("\$AGE_GATE_SELECTOR")
        assertThat(script).doesNotContain("\$SCRIPT_MARKERS")
        assertThat(script).doesNotContain("\$AD_HOSTS")
        assertThat(script).doesNotContain("\$SWEEP_MS")
        assertThat(script).contains("var TAP = 'fbheyuxg';")
    }
}

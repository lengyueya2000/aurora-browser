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

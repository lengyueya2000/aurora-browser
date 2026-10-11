package dev.aurora.browser.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 去广告脚本。
 *
 * 这里断言的是**脚本里到底做了什么**，因为这类代码的错误没有编译期保护：字符串拼错、
 * 选择器写歪、属性前缀写宽了，Kotlin 侧一切正常，只有真机上广告照放、或者页面被弄坏。
 * 参考样本是 `4kvm.net`（ArtPlayer + 官方 `artplayer-plugin-ads`），它的广告配置长这样：
 *
 * ```
 * data-preroll-ad-type="image"  data-preroll-ad-duration="12"  data-preroll-ad-skip-after="6"
 * ```
 */
class AdBlockScriptTest {

    @Test fun stripsExactlyThePrerollAttributeFamily() {
        assertThat(AdBlockScript.PREROLL_ATTRIBUTE_PREFIX).isEqualTo("data-preroll-ad")
        // 实测站点用到的名字一个都不能漏，否则属性剥不掉，广告插件照装。
        assertThat(AdBlockScript.PREROLL_ATTRIBUTES).containsAtLeast(
            "data-preroll-ad-type",
            "data-preroll-ad-image",
            "data-preroll-ad-video",
            "data-preroll-ad-code",
            "data-preroll-ad-link",
            "data-preroll-ad-duration",
            "data-preroll-ad-skip-after",
        )
    }

    /**
     * 前缀必须是 `data-preroll-ad`，不能是 `data-ad`。
     *
     * `data-ad` 会连 `data-address`、`data-adapter`、`data-adaptive` 一起剥掉——那是把站点
     * 功能弄坏，比漏掉一条广告严重得多。这条测试就是防止有人「顺手放宽一点」。
     */
    @Test fun prefixDoesNotSwallowOrdinaryAttributes() {
        val script = AdBlockScript.SCRIPT
        assertThat(script).contains("data-preroll-ad")
        assertThat(script).doesNotContain("'data-ad-'")
        assertThat(script).doesNotContain("data-ad-")
        // 只认前缀本身和「前缀 + -」，不认 data-address 这种同开头不同名的。
        assertThat(script).contains("indexOf(AD_ATTR + '-') === 0")
    }

    @Test fun neutralisesThePlayerAdPluginGlobal() {
        assertThat(AdBlockScript.ART_PLAYER_ADS_GLOBAL).isEqualTo("artplayerPluginAds")
        val script = AdBlockScript.SCRIPT
        // 拦截而不是覆盖：脚本加载顺序控制不了，只有 setter 拦截才不看时序。
        assertThat(script).contains("Object.defineProperty(window, PLUGIN")
        assertThat(script).contains("set: function () {}")
        // 站点会先做 typeof 判断再调用，所以空壳必须是个函数。
        assertThat(script).contains("function stub()")
        assertThat(script).contains("skip: function () {}")
    }

    @Test fun removesTheOverlayAndResumesPlayback() {
        val script = AdBlockScript.SCRIPT
        assertThat(script).contains(AdBlockScript.OVERLAY_SELECTOR)
        assertThat(script).contains(AdBlockScript.CLOSE_SELECTOR)
        assertThat(script).contains(AdBlockScript.PLAYER_SELECTOR)
        // 插件是「暂停正片再盖广告」，所以只删浮层不够，必须把正片播回来。
        assertThat(script).contains("v.play()")
        // 只恢复同一个播放器里的 video，不碰页面上别的播放器。
        assertThat(script).contains("closest")
    }

    @Test fun sweepsPeriodicallyAndOnlyWhileVisible() {
        val script = AdBlockScript.SCRIPT
        // 广告浮层出现在「用户点播放之后」，可能离页面加载很久，一次性清理兜不住。
        assertThat(script).contains("setInterval")
        assertThat(script).contains("document.hidden")
    }

    @Test fun observesAttributeAndChildMutations() {
        val script = AdBlockScript.SCRIPT
        assertThat(script).contains("MutationObserver")
        assertThat(script).contains("attributeFilter")
        assertThat(script).contains("childList: true")
        assertThat(script).contains("subtree: true")
    }

    @Test fun isIdempotentAndReturnsOk() {
        val script = AdBlockScript.SCRIPT
        // 页面加载会注入两次（onPageStarted 抢早期、onPageFinished 兜底），第二次必须是空转。
        assertThat(script).contains("window.__auroraAdBlock")
        assertThat(script).contains("return 'ok'")
        assertThat(script.trimStart()).startsWith("(function ()")
        assertThat(script.trimEnd()).endsWith("})();")
    }

    /**
     * 括号必须配平。
     *
     * 这段 JS 是 Kotlin 原生字符串拼出来的，插值写错（比如少一个花括号）不会有编译错误，
     * 只会在页面上静默变成一句语法错误——注入的返回值就是错误文本，广告照放。数一遍是最
     * 便宜的护栏，前一轮就吃过「注释嵌套吞掉半个文件」的亏。
     */
    @Test fun bracesAndParensAreBalanced() {
        val script = AdBlockScript.SCRIPT
        assertThat(script.count { it == '{' }).isEqualTo(script.count { it == '}' })
        assertThat(script.count { it == '(' }).isEqualTo(script.count { it == ')' })
        assertThat(script.count { it == '[' }).isEqualTo(script.count { it == ']' })
    }

    /** 插值必须真的展开：脚本里不该再留下 Kotlin 的 `$名字` 占位。 */
    @Test fun placeholdersAreInterpolated() {
        val script = AdBlockScript.SCRIPT
        assertThat(script).doesNotContain("\$PREROLL_ATTRIBUTE_PREFIX")
        assertThat(script).doesNotContain("\$ART_PLAYER_ADS_GLOBAL")
        assertThat(script).doesNotContain("\$SWEEP_MS")
        assertThat(script).contains("var AD_ATTR = 'data-preroll-ad';")
    }

    @Test fun cleansUnambiguousAdSlots() {
        val script = AdBlockScript.SCRIPT
        assertThat(AdBlockScript.AD_CONTAINER_SELECTOR).contains("ins.adsbygoogle")
        assertThat(script).contains("ins.adsbygoogle")
    }
}

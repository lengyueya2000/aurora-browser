package dev.aurora.browser.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 视频浮层：注入脚本的内容约定 + 页面回调的准入校验。
 *
 * 脚本本身没法在单测里跑（要真 WebView），所以这里断言它的关键结构没被改坏；
 * 准入校验是纯函数，可以完整覆盖——注入的 JS 接口对页面里所有脚本都可见，
 * 被广告 iframe 乱调时全靠这一层挡住。
 */
class MediaOverlayTest {

    private val script = MediaOverlay.SCRIPT

    @Test fun scriptHangsButtonsOffEveryVideoElement() {
        assertThat(script).contains("querySelectorAll('video')")
        assertThat(script).contains("requestFullscreen")
        assertThat(script).contains("全屏")
        assertThat(script).contains("下载")
        assertThat(script).contains("投屏")
    }

    @Test fun scriptTalksBackThroughTheBridge() {
        assertThat(script).contains("window.${MediaOverlay.BRIDGE_NAME}.onMediaAction")
        assertThat(script).contains("'download'")
        assertThat(script).contains("'cast'")
    }

    /** 单页应用的播放器是后建的，没有统一事件可听，必须靠定时兜底。 */
    @Test fun scriptReScansForLateVideoElements() {
        assertThat(script).contains("setInterval(sync")
        assertThat(script).contains("addEventListener('scroll'")
    }

    /** 重复注入不能叠出第二组按钮。 */
    @Test fun scriptIsIdempotent() {
        assertThat(script).contains("if (window.__auroraMediaOverlay)")
        assertThat(script).contains("window.__auroraMediaOverlay = api")
    }

    /** blob/MSE 源既下不了也投不出去，不该给出按钮骗用户点。 */
    @Test fun scriptHidesDownloadAndCastForStreamSources() {
        assertThat(script).contains("STREAM.test(sourceOf(v))")
    }

    /**
     * 按钮和浮层不能共用同一个标记属性。
     *
     * 回收孤儿浮层时是按这个属性查的，而按钮身上没有 `__owner`——一旦共用，每个按钮
     * 都会被判成孤儿当场删掉，页面上只剩两个空壳（在模拟器上实测过：浮层存在、
     * 位置也对，但宽高都是 0）。这条断言就是钉住这个坑。
     */
    @Test fun barAndButtonsUseDistinctMarkerAttributes() {
        assertThat(script).contains("var BAR_ATTR = 'data-aurora-media'")
        assertThat(script).contains("var BTN_ATTR = 'data-aurora-media-btn'")
        assertThat(script).contains("b.setAttribute(BTN_ATTR, '1')")
        assertThat(script).contains("bar.setAttribute(BAR_ATTR, '1')")
        assertThat(script).doesNotContain("b.setAttribute(BAR_ATTR")
        assertThat(script).contains("querySelectorAll('[' + BAR_ATTR + '=\"1\"]')")
    }

    /** 严格 CSP 的站点会丢掉内联 style 属性，但拦不住 CSSOM 写入。 */
    @Test fun scriptStylesThroughCssomNotInlineAttributes() {
        assertThat(script).contains("b.style.cssText")
        assertThat(script).doesNotContain("style=\"")
    }

    @Test fun onlyDownloadAndCastAreAccepted() {
        assertThat(MediaAction.isValid("download", "https://a.com/v.mp4")).isTrue()
        assertThat(MediaAction.isValid("cast", "http://a.com/v.mp4")).isTrue()
        assertThat(MediaAction.isValid("fullscreen", "https://a.com/v.mp4")).isFalse()
        assertThat(MediaAction.isValid("", "https://a.com/v.mp4")).isFalse()
        assertThat(MediaAction.isValid("DOWNLOAD", "https://a.com/v.mp4")).isFalse()
    }

    /** 地址必须是 http/https：blob:/data:/javascript:/file: 一律挡掉。 */
    @Test fun onlyHttpUrlsAreAccepted() {
        assertThat(MediaAction.isValid("download", "blob:https://a.com/x")).isFalse()
        assertThat(MediaAction.isValid("download", "data:video/mp4;base64,AAAA")).isFalse()
        assertThat(MediaAction.isValid("download", "javascript:alert(1)")).isFalse()
        assertThat(MediaAction.isValid("download", "file:///etc/passwd")).isFalse()
        assertThat(MediaAction.isValid("download", "")).isFalse()
    }

    /** 桥自己也要挡一道，不能只靠调用方守规矩。 */
    @Test fun bridgeDropsInvalidCalls() {
        val seen = mutableListOf<String>()
        val bridge = MediaBridge { action, url, _ -> seen += "$action $url" }
        bridge.onMediaAction("download", "https://a.com/v.mp4", "")
        bridge.onMediaAction("evil", "https://a.com/v.mp4", "")
        bridge.onMediaAction("download", "blob:https://a.com/x", "")
        assertThat(seen).containsExactly("download https://a.com/v.mp4")
    }
}

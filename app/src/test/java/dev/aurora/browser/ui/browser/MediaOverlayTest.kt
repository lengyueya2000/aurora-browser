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
        assertThat(script).contains("全屏")
        assertThat(script).contains("下载")
        assertThat(script).contains("投屏")
    }

    @Test fun scriptTalksBackThroughTheBridge() {
        assertThat(script).contains("window.${MediaOverlay.BRIDGE_NAME}.onMediaAction")
        assertThat(script).contains("'play'")
        assertThat(script).contains("'download'")
        assertThat(script).contains("'cast'")
    }

    /**
     * 「全屏」不再自己调 `requestFullscreen()`。
     *
     * 站点普遍用 MSE，`<video>` 只是 `blob:` 壳子，WebView 把它交出来的自定义 View 挂上
     * 窗口之后画面是黑的（用户实测的「全屏之后是黑屏」）。现在一律回宿主，由内置的
     * Media3 播放器全屏播真实流。
     */
    @Test fun fullscreenButtonHandsOffToTheHostInsteadOfRequestFullscreen() {
        assertThat(script).doesNotContain("requestFullscreen")
        assertThat(script).contains("notify('play'")
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

    /**
     * 下载 / 投屏按钮必须常驻，不能因为源是 `blob:` 就不给。
     *
     * 以前正是这么做的，结果欧乐影院这类 MSE 站点（视频站里的大多数）上用户只看到
     * 一个「全屏」，反馈就是「没有下载按钮」。真实流地址由宿主从网络层嗅探。
     */
    @Test fun downloadAndCastButtonsAreAlwaysPresent() {
        assertThat(script).contains("button('下载'")
        assertThat(script).contains("button('投屏'")
        assertThat(script).doesNotContain("STREAM.test")
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

    /** 切到内置播放器时要带上当前进度，否则用户按个全屏就回到第 0 秒。 */
    @Test fun playCarriesTheCurrentPlaybackPosition() {
        assertThat(script).contains("v.currentTime")
        assertThat(script).contains("v.pause()")
    }

    @Test fun onlyTheThreeKnownActionsAreAccepted() {
        assertThat(MediaAction.isValid(MediaAction.PLAY, "https://a.com/v.mp4")).isTrue()
        assertThat(MediaAction.isValid(MediaAction.DOWNLOAD, "https://a.com/v.mp4")).isTrue()
        assertThat(MediaAction.isValid(MediaAction.CAST, "http://a.com/v.mp4")).isTrue()
        assertThat(MediaAction.isValid("fullscreen", "https://a.com/v.mp4")).isFalse()
        assertThat(MediaAction.isValid("", "https://a.com/v.mp4")).isFalse()
        assertThat(MediaAction.isValid("DOWNLOAD", "https://a.com/v.mp4")).isFalse()
    }

    /**
     * 地址必须是 http/https/blob/data，其余 scheme 一律挡掉。
     *
     * `blob:` 放行是必需的：MSE 站点的 `<video>` 上就只有它，宿主拿它去和当前页面提取到的
     * 视频源比对，对上了才继续。`javascript:` / `file:` 这类才是真正要挡的。
     */
    @Test fun urlSchemeIsAllowlisted() {
        assertThat(MediaAction.isValid("download", "blob:https://a.com/x")).isTrue()
        assertThat(MediaAction.isValid("play", "data:video/mp4;base64,AAAA")).isTrue()
        assertThat(MediaAction.isValid("download", "javascript:alert(1)")).isFalse()
        assertThat(MediaAction.isValid("download", "file:///etc/passwd")).isFalse()
        assertThat(MediaAction.isValid("download", "content://media/1")).isFalse()
        assertThat(MediaAction.isValid("download", "intent://x")).isFalse()
    }

    /** 桥自己也要挡一道，不能只靠调用方守规矩。 */
    @Test fun bridgeDropsInvalidCalls() {
        val seen = mutableListOf<String>()
        val bridge = MediaBridge { action, url, _ -> seen += "$action $url" }
        bridge.onMediaAction("download", "https://a.com/v.mp4", "")
        bridge.onMediaAction("evil", "https://a.com/v.mp4", "")
        bridge.onMediaAction("download", "javascript:alert(1)", "")
        assertThat(seen).containsExactly("download https://a.com/v.mp4")
    }

    /** 第三个参数（play 的当前秒数）要原样带给宿主。 */
    @Test fun bridgeForwardsTheExtraField() {
        var extra: String? = null
        val bridge = MediaBridge { _, _, e -> extra = e }
        bridge.onMediaAction("play", "blob:https://a.com/x", "123")
        assertThat(extra).isEqualTo("123")
    }

    /**
     * 参数声明成可空：页面里任意脚本（含跨域 iframe）都能直接传 `null` 进来。
     *
     * 声明成非空的话，`null` 会先撞在 Kotlin 生成的非空检查上抛异常（跑在 WebView 的 JS
     * 线程上），而不是被这里安静地挡掉。传 null 的动作/地址一律丢弃，extra 缺省成空串。
     */
    @Test fun bridgeToleratesNullArguments() {
        val seen = mutableListOf<String>()
        val bridge = MediaBridge { action, url, extra -> seen += "$action $url $extra" }
        bridge.onMediaAction(null, "https://a.com/v.mp4", null)
        bridge.onMediaAction("play", null, null)
        bridge.onMediaAction("play", "https://a.com/v.mp4", null)
        assertThat(seen).containsExactly("play https://a.com/v.mp4 ")
    }
}

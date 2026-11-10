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

    /**
     * `isReaderPage` 必须同时校验 host。
     *
     * 这是审查发现的安全问题：`BrowserScreen` 拿它的结果决定要不要隐藏地址栏和底部
     * 工具栏，而地址栏是用户判断「我在哪个站」的唯一依据。原来只看 path、注释里写
     * 「调用方已用 appliesTo 过滤」，但两处调用都没过滤——任意站点把页面放在
     * `/comics-read/` 路径下，就能让浏览器把地址栏藏起来，那正是钓鱼页想要的状态。
     *
     * 所以 host 门禁收进函数内部，不依赖调用方记得先过滤。
     */
    @Test fun readerPageRequiresTheTargetHostToo() {
        // 本站的阅读页：是。
        assertThat(SiteAdRules.isReaderPage("https://rmtt4.com/comics-read/1000123/abc")).isTrue()
        // 别的站即使路径一样：不是。这条就是安全边界。
        assertThat(SiteAdRules.isReaderPage("https://evil.example/comics-read/1000123/abc")).isFalse()
        assertThat(SiteAdRules.isReaderPage("https://notrmtt4.com/comics-read/x")).isFalse()
        assertThat(SiteAdRules.isReaderPage("https://rmtt4.com.evil.test/comics-read/x")).isFalse()
    }

    @Test fun scriptForReturnsEmptyForOtherSites() {
        assertThat(SiteAdRules.scriptFor("https://rmtt4.com/")).isEqualTo(SiteAdRules.SCRIPT)
        // 空串而不是 null：调用方要能无条件拼接。
        assertThat(SiteAdRules.scriptFor("https://example.com/")).isEmpty()
    }

    /**
     * 阅读页要认出来，别的页面不能误判。
     *
     * 阅读模式（隐藏站点顶栏/底栏、隐藏浏览器自己的地址栏与工具栏）只对阅读页生效：
     * 详情页和首页的导航是真要用的，藏起来等于把站点功能砍掉。
     */
    @Test fun recognisesReaderPagesOnly() {
        assertThat(SiteAdRules.READER_PATH_PREFIX).isEqualTo("/comics-read/")
        assertThat(SiteAdRules.isReaderPage("https://rmtt4.com/comics-read/1000123/QlsxOFjnOSZORMJHBscH"))
            .isTrue()
        // 详情页、首页、分类页都不是阅读页。
        assertThat(SiteAdRules.isReaderPage("https://rmtt4.com/comics-detail/1000123")).isFalse()
        assertThat(SiteAdRules.isReaderPage("https://rmtt4.com/")).isFalse()
        assertThat(SiteAdRules.isReaderPage("https://rmtt4.com/comics-cata")).isFalse()
        // 新标签页还没有地址（active.url 是 String?），不能崩。
        assertThat(SiteAdRules.isReaderPage(null)).isFalse()
        assertThat(SiteAdRules.isReaderPage("")).isFalse()
        assertThat(SiteAdRules.isReaderPage("not a url")).isFalse()
    }

    /**
     * 阅读页多注入一段隐藏样式，别的页面不多给。
     *
     * 这是「只在阅读页生效」的落点：`scriptFor` 按 URL 分流。
     */
    @Test fun onlyReaderPagesGetTheHideStyle() {
        val reader = SiteAdRules.scriptFor("https://rmtt4.com/comics-read/1000123/abc")
        val detail = SiteAdRules.scriptFor("https://rmtt4.com/comics-detail/1000123")

        assertThat(reader).contains("aurora-reader-style")
        assertThat(detail).doesNotContain("aurora-reader-style")
        // 两页都要有去广告脚本，区别只在多不多那一段。
        assertThat(reader).contains("window.__auroraSiteAd")
        assertThat(detail).contains("window.__auroraSiteAd")
    }

    /**
     * 隐藏站点顶栏与底栏，用 `display:none`。
     *
     * 不能用 `visibility:hidden`：那个元素仍然占位，阅读区照样被挤掉 103px + 57px。
     */
    @Test fun hidesSiteHeaderAndBottomBar() {
        val css = SiteAdRules.READER_HIDE_CSS
        // 顶部栏（含搜索框）——阅读页上是 103px 的 fixed 条。
        assertThat(css).contains("#topnav")
        assertThat(css).contains(".head_box")
        assertThat(css).contains(".head_search")
        assertThat(css).contains(".head_hot_search")
        // 底部「首页/分类/最新/排行/书架」固定导航栏——57px 的 fixed 条。
        assertThat(css).contains(".foot_mnav")
        // 必须是 display:none，否则仍然占位。
        assertThat(css).contains("display: none !important")
        assertThat(css).doesNotContain("visibility: hidden")
    }

    /**
     * 不能顺手把 `.foot_nav` 和翻页条一起藏了。
     *
     * 这是写这段时差点犯的错：`.foot_nav` 里装着**繁简切换、暗夜/白昼模式、返回顶部**
     * 三个功能，藏掉等于把站点功能砍了；`.positionFooter` 是阅读页真正的翻页条
     * （「章节目录 / 下一章」），是阅读流程的一部分。两者都不该出现在隐藏名单里。
     */
    @Test fun doesNotHideSiteFeaturesOrPageTurner() {
        val css = SiteAdRules.READER_HIDE_CSS
        assertThat(css).doesNotContain(".foot_nav")
        assertThat(css).doesNotContain("positionFooter")
        // 页面末尾的静态页脚本来也不占阅读视野，留着不影响观感。
        assertThat(css).doesNotContain("backtop")
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
     * 拦截器要能重新安装。
     *
     * 重复注入时不能简单「提前返回」：同一个 WebView 换文档后，`document.write` 会回到
     * 原生实现，只靠第一次的包装就漏了。所以装拦截器和跑清理必须分开——这条测试盯的是
     * 「提前返回的分支里也要调 install」。
     */
    @Test fun reinstallGuardsOnRepeatedInjection() {
        val script = SiteAdRules.SCRIPT
        // 提前返回分支里必须同时调 install 和 run。
        val anchor = script.indexOf("var existing = window.__auroraSiteAd")
        assertThat(anchor).isAtLeast(0)
        val earlyReturn = script.substring(anchor, minOf(script.length, anchor + 400))
        assertThat(earlyReturn).contains("existing.install()")
        assertThat(earlyReturn).contains("existing.run()")
        // 导出对象里要带 install，boot 也要调。
        assertThat(script).contains("install: installGuards")
        assertThat(script).contains("function installGuards()")
        // 幂等标记：重复安装不能套娃。
        assertThat(script).contains("__aurora")
    }

    /**
     * 替换 WebSocket 构造函数时，静态常量必须一起搬过去。
     *
     * 站点代码里 `WebSocket.OPEN` / `WebSocket.CONNECTING` 这种写法很常见，漏了会让
     * 正常功能坏掉。
     */
    @Test fun preservesWebSocketStaticConstants() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("Guarded.CONNECTING")
        assertThat(script).contains("Guarded.OPEN")
        assertThat(script).contains("Guarded.CLOSING")
        assertThat(script).contains("Guarded.CLOSED")
    }

    /**
     * 广告地址匹配必须先小写。
     *
     * 主机名不区分大小写，`wss://x.QIQ15OD.COM` 是合法写法。原来 `isAdUrl` 是大小写
     * 敏感的子串匹配，一行大小写就能绕过——而 WebSocket 升级请求**不经过**
     * `shouldInterceptRequest`，宿主层黑名单兜不住它，这个包装是唯一防线。
     */
    @Test fun adUrlMatchingIsCaseInsensitive() {
        val script = SiteAdRules.SCRIPT
        // 必须先把整串转小写再比。
        assertThat(script).contains("String(url).toLowerCase()")
        // 只认 host 段，不做整串子串匹配（否则 ?ref=xxx 会误伤）。
        assertThat(script).contains("var host = s.slice(0, end)")
        // 子域匹配：等于 或 .后缀。
        assertThat(script).contains("host === h")
    }

    /**
     * 全局 touchend 劫持必须挡住。
     *
     * 方块只是第一层：站点第二段脚本还在 `document` 上挂了一个 touchend 监听，
     * 「手指落在距底部 204×(屏宽/620) 像素以内」就跳转（实测该监听确实存在，
     * 函数体里写着 `top.location=...`）。方块删掉之后它照样在——只删方块，
     * 劫持面并没有真正归零。
     *
     * 做法是在 capture 阶段先注册自己的监听，命中底部窄带就 stopImmediatePropagation。
     */
    @Test fun blocksGlobalTouchendHijack() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("__auroraTouchGuard")
        // 必须用 capture 阶段，否则抢不到站点监听前面。
        assertThat(script).contains("'touchend'")
        assertThat(script).contains("true);")
        assertThat(script).contains("stopImmediatePropagation")
        // 阈值要跟站点一致：204 × (屏宽 / 620)。
        assertThat(script).contains("204 * (vw / 620)")
    }

    /**
     * `document.writeln` 也要拦。
     *
     * 它是独立方法，不走被覆盖的 `document.write`——不一起包装的话，广告换成
     * `writeln` 就绕过了写入层，方块照常出现（只能靠 observer 事后删，「闪一下」）。
     */
    @Test fun alsoInterceptsWriteln() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("document.writeln")
        assertThat(script).contains("nativeWriteln")
    }

    /**
     * 提前返回前必须校验全局对象类型。
     *
     * `window.__auroraSiteAd` 是页面可控的全局：站点或广告脚本随手写个 `{}` 就能让
     * `install()` 抛 TypeError，连带把清理一起废掉——而 Kotlin 侧的 `runCatching`
     * 会把这个异常吞掉，只剩一条 debug 日志。
     */
    @Test fun validatesTheGlobalBeforeEarlyReturn() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("typeof existing.install === 'function'")
        assertThat(script).contains("typeof existing.run === 'function'")
    }

    /** observer 要覆盖全部规则，不能只盯方块——否则横幅/弹窗会先渲染再被删（「闪一下」）。 */
    @Test fun mutationObserverCoversAllRules() {
        val script = SiteAdRules.SCRIPT
        val stripIdx = script.indexOf("function stripTree")
        assertThat(stripIdx).isAtLeast(0)
        val body = script.substring(stripIdx, minOf(script.length, stripIdx + 700))
        // stripTree 里要同时处理方块、横幅、弹窗。
        assertThat(body).contains("BANNER")
        assertThat(body).contains("GATE")
        assertThat(body).contains("isBannerNode")
        assertThat(body).contains("isGateNode")
    }

    /** 轮询要能退避，不能永远按固定频率全文档扫描。 */
    @Test fun sweepBacksOffWhenIdle() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("idleRounds")
        assertThat(script).contains("slow")
        assertThat(script).contains("restartSweep")
        // 后台标签页跳过。
        assertThat(script).contains("document.hidden")
    }

    /** 被丢弃的 write 要有记录，否则「某功能莫名坏掉」无从排查。 */
    @Test fun recordsDroppedWrites() {
        val script = SiteAdRules.SCRIPT
        assertThat(script).contains("countDropped")
        assertThat(script).contains("dropped")
        assertThat(script).contains("samples")
    }

    /** `CHAPTER_LIST_SELECTOR` 不能是死代码——CSS 要由它插值生成。 */
    @Test fun chapterCssUsesTheSelectorConstant() {
        val css = SiteAdRules.CHAPTER_CSS
        assertThat(css).contains(SiteAdRules.CHAPTER_LIST_SELECTOR)
        // 字号用相对单位，不写死像素（否则会覆盖用户的无障碍字号偏好）。
        assertThat(css).contains("0.9rem")
        assertThat(css).doesNotContain("14.42px")
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
        val marker = "广告脚本节点本身"
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

package dev.aurora.browser.ui.browser

/**
 * `rmtt4.com`（肉漫天堂）站点专用的广告与劫持规则。
 *
 * ## 为什么需要「站点专用」这一层
 *
 * [AdBlockScript] 治的是**视频站的前贴片广告**（广告配置写在播放器容器的 `data-*` 上）。
 * 这个站是漫画站，没有播放器，用的是另一套东西：**点击劫持（tapjacking）**。
 *
 * 详情页（`/comics-detail/` 开头的路径）的 HTML 里内嵌了两段混淆脚本，在页面底部铺
 * 40 个透明方块：
 *
 * ```
 * position:fixed; bottom:<n>vw; left:<j>vw; z-index:100;
 * width:9.6vw; height:7.96875vw; background:#000; opacity:0.01;
 * ```
 *
 * 4 行 × 10 列，盖住视口底部约 15%。用户往下滚、想点「下一话」或封面时，手指落在这些
 * 几乎不可见的方块上，`touchend` 就把整页跳走：
 *
 * ```
 * https://<日期>6448gc.qiq15od.com:8005/cc/6448?...   ← 一跳
 * https://83065hvt.yjdh859.com:8851/tg.htm?channel=… ← 二跳，落地推广页
 * ```
 *
 * 第二段脚本还有两条更隐蔽的路子：
 *
 * - 在 `document` 上挂一个**全局 `touchend` 监听**，判据是「手指落在距底部
 *   `204 × (屏宽 / 620)` 像素以内」——就算方块被删掉，这个监听照样会跳。
 * - 如果 UA 命中 `baidu|HuaweiBrowser|VivoBrowser|UCBrowser|Quark…`，它不开 WebSocket
 *   而改插一个 `82gd88v.com:8005/sc/6448` 的外链脚本；命中则连
 *   `wss://…0v3xzl6.com:20095`，从服务端拉 base64 代码用 `new Function` 执行——
 *   **广告内容是服务端下发的，静态规则拦不住**，所以这里选择直接掐断连接。
 *
 * ## 为什么不能只靠域名拦截
 *
 * 实测过：只把广告域名加进黑名单，40 个透明方块**照样在**（它们是页面自己的内联脚本
 * 用 `document.write` 写出来的，跟外部请求无关）。必须把节点删掉，劫持面才归零。
 * 域名拦截仍然保留，因为它是唯一能挡住「WebSocket 拉代码」和「外链脚本」的一层。
 *
 * ## 四层防御（从最干净到兜底）
 *
 * 1. **拦 `document.write` / `insertAdjacentHTML`**：方块是解析期写出来的，拦在写入这
 *    一层，方块根本不会出现——零闪烁。第一段脚本还会因为拿不到方块而自己抛错停掉。
 * 2. **删节点**：MutationObserver 盯着新增子树，配合 800ms 轮询兜住「注入晚于页面脚本」
 *    的情况（实测 `readyState: loading` 阶段就能抓到插入）。
 * 3. **掐 WebSocket**：广告用 wss 从服务端拉可执行代码，这条通道必须断。
 * 4. **宿主层拦跳转**：见 [WebViewPool] 的 `shouldOverrideUrlLoading`——前几层都没赶上时，
 *    最后一道是 Kotlin 侧直接拒绝导航到广告域名。这条不看 JS 时序，是真正的保证。
 *
 * ## 顺带处理的东西
 *
 * - **年龄确认弹窗**（`.wrap-dialog-t`）：进站必弹、挡住正文。按产品要求一并移除。
 * - 广告脚本会插一个 `#fbheyuxg_style_id` 的 `<style>`，把 `body` 改成
 *   `padding-bottom:100px`，留着会让页面底部凭空多一块空白，一并删掉。
 *
 * ## 边界
 *
 * 规则只对 `rmtt4.com`（含子域）生效，见 [appliesTo]。别的站不受任何影响——广告联盟
 * 的域名会轮换，把这一串塞进全局黑名单等于替所有站点做决定，误伤面不可控。
 */
object SiteAdRules {

    /** 规则生效的主机。含子域匹配（`www.rmtt4.com` 等）。 */
    const val HOST = "rmtt4.com"

    /**
     * 点击劫持方块的类名前缀。
     *
     * 两段脚本用的是同一个前缀 `fbheyuxg`，方块类名是 `fbheyuxg_b`。用「包含」而不是
     * 「等于」匹配：前缀本身就是随机串，站点换后缀也照样命中。
     */
    const val TAPJACK_CLASS = "fbheyuxg"

    /**
     * 底部横幅广告的类名。
     *
     * 和点击劫持不是同一套：这是一组 `position:fixed` 的 `<div class="ouWshumi">`，
     * 靠 `background-position` 把一张大图切成小块拼出横幅，所以每个节点都看不出是广告，
     * 只有整组拼起来才是。投放是**随机/低频**的（多次加载才碰到一次），但注入它的脚本
     * 是固定的：`qiq15od.com:8005/d/6448`，和劫持那一路同源。
     */
    const val BANNER_CLASS = "ouWshumi"

    /** 年龄确认弹窗。整个节点删掉即可，站点脚本对不存在的节点做 `removeClass` 是空转。 */
    const val AGE_GATE_SELECTOR = ".wrap-dialog-t"

    /** 广告脚本插进来的 `<style>`，会把 `body` 底部撑出 100px 空白。 */
    const val STYLE_ID = "fbheyuxg_style_id"

    /**
     * 章节列表的容器。
     *
     * 站点把按钮设成 `height:1rem; overflow:hidden; text-overflow:ellipsis; white-space:nowrap`，
     * 三列固定宽度装不下「第N话-完整标题」，一律被截成「第1话-不打…」。这里把它改回可换行，
     * 并让容器高度自适应。见 [CHAPTER_CSS]。
     */
    const val CHAPTER_LIST_SELECTOR = ".content_playlist"

    /**
     * 阅读页的路径前缀。
     *
     * 只有这个前缀下的页面进入「阅读模式」：隐藏站点自己的顶部栏（含搜索框）与底部导航栏。
     * 详情页/首页不隐藏——那些页面的导航是真需要的，藏起来等于把站点功能砍掉。
     */
    const val READER_PATH_PREFIX = "/comics-read/"

    /**
     * 阅读模式下要隐藏的站点元素。
     *
     * - `#topnav` / `.head_box`：顶部栏，103px 高，装着 logo、搜索框、菜单。
     * - `.head_search` / `.head_hot_search`：搜索框本身（详情页上它在顶部栏里是展开的，
     *   阅读页上虽然被折叠成 0 高度，但一起写上，站点改版也不会漏）。
     * - `.foot_mnav`：底部「首页 / 分类 / 最新 / 排行 / 书架」固定导航栏，57px 高。
     *
     * ## 刻意**不**隐藏的东西
     *
     * - `.foot_nav`：这块静态页脚里装着**繁简切换、暗夜/白昼模式、返回顶部**三个功能，
     *   藏掉等于把站点功能砍了。它本来也在页面最末尾，不占阅读视野。
     * - `.positionFooter`：阅读页真正的翻页条（「章节目录 / 下一章」），在页面末尾，
     *   是阅读流程的一部分，必须留着。
     *
     * 用 `display:none` 而不是 `visibility:hidden`：后者元素仍然占位，阅读区还是被挤掉
     * 103px + 57px 的高度。
     */
    val READER_HIDE_CSS: String = """
        #topnav,
        .head_box,
        .head_search,
        .head_hot_search,
        .foot_mnav {
          display: none !important;
        }
    """.trimIndent()

    /**
     * 是不是「本站的阅读页」——**host 和 path 都要满足**。
     *
     * 只看 path 是不够的：`BrowserScreen` 拿这个结果决定要不要隐藏地址栏和底部工具栏，
     * 而地址栏是用户判断「我在哪个站」的唯一依据。任意站点只要把页面放在 `/comics-read/`
     * 路径下，就能让浏览器把地址栏藏起来——那正好是钓鱼页想要的状态。所以 host 门禁
     * 收进函数内部，不依赖调用方记得先过滤。
     *
     * 参数可空是因为调用方手上的 `active.url` 是 `String?`（新标签页还没有地址），
     * 空值一律当作「不是阅读页」。
     */
    fun isReaderPage(url: String?): Boolean {
        if (url.isNullOrBlank() || !appliesTo(url)) return false
        return runCatching { java.net.URI(url).path.orEmpty() }.getOrDefault("")
            .startsWith(READER_PATH_PREFIX)
    }

    /**
     * 识别广告脚本的字符串特征。
     *
     * 用来判断一个 `<script>` 节点是不是广告：内联脚本看正文，外链脚本看地址。
     * 三个串来自实测——`fbheyuxg` 是方块类名前缀，`qiq15od` 是跳转域名，
     * `6448gc` 是跳转域名里的固定片段。
     */
    val SCRIPT_MARKERS: List<String> = listOf("fbheyuxg", "qiq15od", "6448gc")

    /**
     * 广告域名。这些域名只用于投放与跳转，站点自身功能不依赖它们。
     *
     * 收的都是实测到的：`qiq15od.com` 是点击跳转的一跳，`yjdh859.com` 是二跳落地页，
     * `82gd88v.com` 是 UA 分支的外链脚本，`0v3xzl6.com` / `1ut8hmb.com` / `u5ztyuu.com`
     * 是 WebSocket 那一路的候选（脚本里有个数组按日期轮换）。
     */
    val AD_HOSTS: Set<String> = setOf(
        "qiq15od.com",
        "82gd88v.com",
        "yjdh859.com",
        "0v3xzl6.com",
        "1ut8hmb.com",
        "u5ztyuu.com",
    )

    /**
     * 轮询间隔。方块出现在解析期，但第二段脚本还会在 1000ms / 1500ms 后追加东西，
     * 所以不能只靠一次清理。
     */
    private const val SWEEP_MS = 800

    /**
     * 让章节按钮的文字完整显示的样式覆盖。
     *
     * ## 为什么要动样式
     *
     * 详情页的「播放列表」是 3 列固定宽度网格，每个按钮的样式是
     *
     * ```
     * height: 1rem; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
     * ```
     *
     * 装不下「第N话-完整标题」这种长文本，一律截成「第1话-不打…」。实测 202 个按钮里
     * 101 个被截断，最长的一条需要 206px 而可用宽度只有 109px。
     *
     * ## 三个坑（都是实测踩过的）
     *
     * 1. **只放开 `white-space` 不行**：容器 `ul` 自己是 `white-space:nowrap`，
     *    按钮虽然能换行了，`li` 却被挤成一行（实测 12 个一行、容器宽度涨到 16285px）。
     *    所以 `ul` 的 `nowrap` 必须一起重置。
     * 2. **`li` 用 `float` 会错位**：原来的 `li` 是 `float:left; width:16.6667%`。
     *    按钮高度不一（一行标题 vs 三行标题）时，float 布局会出现「第4话」和「第9话」
     *    并排这种参差。改用 `inline-block` + `vertical-align:top` 才能保证逐行对齐。
     * 3. **`inline-block` 之间的空白**：`li` 之间的换行符会渲染成空格，三列会挤成两列。
     *    把容器的 `font-size` 设成 0 消掉这个空白，再在 `li` 上把字号设回来。
     *
     * 这套改动的净效果：202 个按钮全部完整显示、保持 3 列、章节顺序正确、无横向溢出。
     *
     * 字号用 `rem` 而不是实测出来的 `14.42px`：那是个具体设备的像素值，换屏宽或用户
     * 调大系统字号后就不对了，而且写死像素会覆盖用户的无障碍字号偏好。
     */
    val CHAPTER_CSS: String = """
        $CHAPTER_LIST_SELECTOR {
          white-space: normal !important;
          height: auto !important;
          max-height: none !important;
          overflow: visible !important;
          font-size: 0 !important;
        }
        $CHAPTER_LIST_SELECTOR li {
          float: none !important;
          display: inline-block !important;
          vertical-align: top !important;
          width: 33.3333% !important;
          padding-bottom: 0.25rem !important;
          padding-right: 0.25rem !important;
          box-sizing: border-box !important;
          font-size: 0.9rem !important;
        }
        $CHAPTER_LIST_SELECTOR li a {
          overflow: visible !important;
          text-overflow: clip !important;
          white-space: normal !important;
          height: auto !important;
          min-height: 1rem !important;
          padding-top: 0.15rem !important;
          padding-bottom: 0.15rem !important;
          word-break: break-word !important;
          line-height: 1.3 !important;
        }
    """.trimIndent()

    /**
     * 把一段文本转成 JS 字符串字面量（含引号）。
     *
     * CSS 里有引号、换行、反斜杠，直接塞进 `'...'` 会拼出语法错——而这类错在页面上
     * 只表现为「注入返回值是一句 SyntaxError」，广告照放、样式没生效，没有任何提示。
     *
     * 手写而不是用 `org.json.JSONObject.quote`：那个类在 JVM 单元测试里是 Android stub，
     * 一调用就抛 `RuntimeException: Stub!`，整个 object 的静态初始化会跟着失败。
     */
    internal fun jsString(text: String): String {
        val sb = StringBuilder(text.length + 16)
        sb.append('"')
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                // 控制字符一律转成 \uXXXX，免得把 JS 字符串弄断。
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** 这个地址是不是本站（含子域）。 */
    fun appliesTo(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == HOST || host.endsWith(".$HOST")
    }

    /** 这个地址是不是广告域名（含子域）。 */
    fun isAdHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return AD_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 该给这个地址注入哪段脚本：本站返回 [SCRIPT]，别的一律空串。
     *
     * 空串而不是 `null`，是为了让调用方可以无条件拼接（`a + "\n" + b`）。
     *
     * 阅读页会多注入一段「隐藏站点顶栏/底栏」的样式，见 [readerScript]。
     */
    fun scriptFor(url: String): String = when {
        !appliesTo(url) -> ""
        isReaderPage(url) -> SCRIPT + "\n" + readerScript()
        else -> SCRIPT
    }

    /**
     * 阅读页专用的样式注入。
     *
     * 单独拼在 [SCRIPT] 后面而不是塞进 SCRIPT 里，是因为这段只在阅读页需要：详情页/首页
     * 的顶栏和底栏是真要用的，藏起来等于把站点功能砍掉。
     *
     * ## 为什么要等 DOM
     *
     * 这段脚本的注入时机是 `onPageStarted`，那时 `document.head` **可能还不存在**——
     * 直接 `return` 会让样式永远注不进去（实测：注入后顶栏仍是 103px）。所以三路兜底：
     * 立刻试一次、DOMContentLoaded 再来、load 再来，再加一个短轮询兜住前两者都没赶上的
     * 情况。`apply()` 自身幂等（按 id 查重），重复调用不会堆节点。
     */
    private fun readerScript(): String = """
        (function () {
          var ID = 'aurora-reader-style';
          var CSS = ${jsString(READER_HIDE_CSS)};

          function apply() {
            if (document.getElementById(ID)) return true;
            if (!document.head) return false;
            var el = document.createElement('style');
            el.id = ID;
            el.textContent = CSS;
            document.head.appendChild(el);
            return true;
          }

          if (apply()) return 'ok';
          document.addEventListener('DOMContentLoaded', apply);
          window.addEventListener('load', apply);
          // 兜底：万一 DOMContentLoaded 已经错过了（注入晚于它），短轮询补上。
          var tries = 0;
          var timer = setInterval(function () {
            if (apply() || ++tries > 40) clearInterval(timer);
          }, 100);
          return 'ok';
        })();
    """.trimIndent()

    val SCRIPT: String = """
        (function () {
          // 重复注入（onPageStarted + onPageFinished + 返回时）只跑一遍清理，但**不能**直接
          // 返回：同一个 WebView 里换文档时，`document.write` 等原型方法会回到原生实现，
          // 只靠第一次的包装就漏了。所以这里把「装拦截器」和「跑清理」分开——拦截器每次
          // 都重新装（幂等，见 installGuards），清理照旧跑。
          //
          // **必须校验类型**：`window.__auroraSiteAd` 是页面可控的全局，站点（或广告脚本）
          // 随手写个 `{}` 就能让 `install()` 抛 TypeError，连带把清理一起废掉——而 Kotlin
          // 侧 `runCatching` 会把这个异常吞掉，只剩一条 debug 日志。类型不对就当没装过，
          // 往下走完整初始化（重装一遍是幂等的）。
          var existing = window.__auroraSiteAd;
          if (existing && typeof existing.install === 'function' && typeof existing.run === 'function') {
            existing.install();
            existing.run();
            return 'ok';
          }

          var TAP = '$TAPJACK_CLASS';
          var BANNER = '$BANNER_CLASS';
          var GATE = '$AGE_GATE_SELECTOR';
          var STYLE_ID = '$STYLE_ID';
          var CHAPTER_STYLE_ID = 'aurora-chapter-style';
          var CHAPTER_CSS = ${jsString(CHAPTER_CSS)};
          var MARKERS = ${SCRIPT_MARKERS.joinToString(", ") { "'$it'" }.let { "[$it]" }};
          var AD_HOSTS = ${AD_HOSTS.joinToString(", ") { "'$it'" }.let { "[$it]" }};
          var SWEEP_MS = $SWEEP_MS;

          function hasMarker(text) {
            if (!text) return false;
            for (var i = 0; i < MARKERS.length; i++) {
              if (text.indexOf(MARKERS[i]) >= 0) return true;
            }
            return false;
          }

          /**
           * 记一笔被丢弃的 `document.write`。
           *
           * 丢弃策略是必要的（加载后 write 会清空文档，见 installGuards 的注释），但它
           * **静默**——站点若在加载完后靠 write 造节点，丢掉之后会 `null.xxx` 抛 TypeError，
           * 表现为「某功能莫名坏掉」，而且注入的返回值只有 `'ok'`，没有任何线索。
           *
           * 这里只记计数与前几条样本，供排查用；不改变行为。
           */
          function countDropped(markup) {
            try {
              var st = window.__auroraSiteAd;
              if (!st || !st.dropped) return;
              st.dropped.count++;
              if (st.dropped.samples.length < 5) {
                st.dropped.samples.push(String(markup).slice(0, 200));
              }
            } catch (e) {}
          }

          /**
           * 这个地址是不是广告域名。
           *
           * **必须小写后再比。** 主机名不区分大小写，`wss://x.QIQ15OD.COM` 是合法写法；
           * 而这里是大小写敏感的子串匹配，不做归一化就会被一行大小写绕过。Kotlin 侧的
           * [isAdHost] 是 lowercase 过的，两边判据必须一致。
           *
           * 这条尤其要紧：WebSocket 的升级请求**不经过** `shouldInterceptRequest`，
           * 宿主层的域名黑名单兜不住它——这个包装是唯一能挡住「服务端下发代码」的防线。
           *
           * 另外只认「协议 + 主机名」那一段，不做整串子串匹配：`?ref=qiq15od.com` 这种
           * 参数里的偶然出现不该判定成广告地址。
           */
          function isAdUrl(url) {
            if (!url) return false;
            var s = String(url).toLowerCase();
            // 取出 host 段：// 之后、第一个 / ? # 之前
            var i = s.indexOf('//');
            if (i >= 0) s = s.slice(i + 2);
            var end = s.length;
            var stops = ['/', '?', '#'];
            for (var k = 0; k < stops.length; k++) {
              var p = s.indexOf(stops[k]);
              if (p >= 0 && p < end) end = p;
            }
            var host = s.slice(0, end);
            // 去掉 userinfo 和端口
            var at = host.lastIndexOf('@');
            if (at >= 0) host = host.slice(at + 1);
            var colon = host.lastIndexOf(':');
            if (colon >= 0) host = host.slice(0, colon);

            for (var j = 0; j < AD_HOSTS.length; j++) {
              var h = AD_HOSTS[j];
              if (host === h || host.slice(-(h.length + 1)) === '.' + h) return true;
            }
            return false;
          }

          function drop(el) {
            if (el && el.parentNode) el.parentNode.removeChild(el);
          }

          /** 元素的 class 里是否含某个特征串。 */
          function hasClass(el, token) {
            if (!el || el.nodeType !== 1) return false;
            var cls = el.getAttribute ? el.getAttribute('class') : '';
            return !!cls && cls.indexOf(token) >= 0;
          }

          function isTapNode(el) { return hasClass(el, TAP); }
          function isBannerNode(el) { return hasClass(el, BANNER); }

          /** 年龄弹窗按选择器判定（可能是 `.wrap-dialog-t` 这种完整选择器串）。 */
          function isGateNode(el) {
            if (!el || el.nodeType !== 1 || !el.matches) return false;
            try { return el.matches(GATE); } catch (e) { return false; }
          }

          /**
           * 删掉一棵新插入子树里的广告节点。
           *
           * 覆盖**全部**规则，不只是方块：横幅和年龄弹窗如果是动态插进来的，只靠 800ms
           * 轮询会先渲染出来再被删（「闪一下」），年龄弹窗闪一下再消失体感尤其差。
           *
           * 一次 `querySelectorAll` 拿齐两类类名，再按类名分流——比扫两遍便宜。
           */
          function stripTree(root) {
            if (!root || root.nodeType !== 1) return;
            if (isTapNode(root) || isBannerNode(root) || isGateNode(root)) { drop(root); return; }
            if (!root.querySelectorAll) return;

            var hits = root.querySelectorAll(
              '[class*="' + TAP + '"], [class*="' + BANNER + '"], ' + GATE
            );
            for (var i = 0; i < hits.length; i++) drop(hits[i]);

            var scripts = root.querySelectorAll('script');
            for (var j = 0; j < scripts.length; j++) {
              var s = scripts[j];
              if (s.src) { if (isAdUrl(s.src)) drop(s); continue; }
              if (hasMarker(s.textContent)) drop(s);
            }
          }

          function run() {
            // 一、点击劫持方块 + 底部横幅广告，**一次查询拿齐**。
            //     `[class*=...]` 走不了样式索引，必须逐元素读 class，所以两次全文档扫
            //     和一次差别明显（长页面 1–2 万节点）。横幅是把一张大图切成若干 fixed
            //     小块拼出来的，每个节点单独看都不像广告，只能按类名整组删。
            var hits = document.querySelectorAll(
              '[class*="' + TAP + '"], [class*="' + BANNER + '"]'
            );
            for (var i = 0; i < hits.length; i++) drop(hits[i]);

            // 二、广告插进来的样式：不删的话 body 底部会多出 100px 空白。
            drop(document.getElementById(STYLE_ID));

            // 三、年龄确认弹窗：整个节点删掉。
            var gates = document.querySelectorAll(GATE);
            for (var j = 0; j < gates.length; j++) drop(gates[j]);

            // 四、广告脚本节点本身。**只在解析期做**：文档加载完后站点脚本还会重跑，
            //     这时删 <script> 节点没有任何收益，反而会扰动页面重新解析（和
            //     document.write 同一个坑）。另外要清楚：parser-inserted 的脚本一旦
            //     开始准备执行，移出 DOM 并不阻止它执行——这里的作用是「防重入」，
            //     不是「防执行」。
            if (document.readyState === 'loading') {
              var scripts = document.querySelectorAll('script');
              for (var k = 0; k < scripts.length; k++) {
                var s = scripts[k];
                if (s.src) { if (isAdUrl(s.src)) drop(s); continue; }
                if (hasMarker(s.textContent)) drop(s);
              }
            }
          }

          /**
           * 让章节按钮的文字完整显示。
           *
           * 站点的样式把按钮锁成单行 + 省略号，长标题一律截断。覆盖规则见 [CHAPTER_CSS]。
           * 幂等：先删旧节点再加，SPA 反复触发也不会堆。
           */
          function applyChapterStyle() {
            if (document.getElementById(CHAPTER_STYLE_ID)) return;
            if (!document.head) return;
            var el = document.createElement('style');
            el.id = CHAPTER_STYLE_ID;
            el.textContent = CHAPTER_CSS;
            document.head.appendChild(el);
          }

          /**
           * 装各处拦截器。**幂等**：已装过的用标记跳过，不会套娃。
           *
           * 单独抽出来是因为换文档时这些包装会丢（`document.write` 回到原生实现），
           * 而重复注入时清理逻辑没必要重跑——两者分开，各按各的时机。
           */
          function installGuards() {
            // 方块是解析期用 document.write 写出来的。拦在写入这一层，方块根本不会出现，
            // 也就没有「闪一下再消失」。顺带让第一段脚本拿不到方块、自己抛错停掉。
            //
            // **只放行解析期。** `document.write` 在文档加载完成（readyState 不是 loading）
            // 之后调用会隐式触发 `document.open()`，把整个文档清空再重新解析——页面表现为
            // 「一直刷新」。实测：加载完后调一次 write，body 文字从 2944 字掉到 4 字、
            // readyState 从 complete 退回 loading。
            //
            // 从阅读页后退回详情页时正好会走到这条路径：站点脚本重跑、我们的注入也重跑，
            // 而那时文档已经是 complete。所以这里必须判 readyState，不能无条件转发给原生。
            // 丢掉的 write 只可能是广告或页面加载完之后的迟到写入，两者都不该执行。
            if (!document.write.__aurora) {
              var nativeWrite = document.write;
              var guarded = function (markup) {
                if (document.readyState !== 'loading') {
                  countDropped(markup);
                  return;
                }
                if (hasMarker(String(markup))) { countDropped(markup); return; }
                return nativeWrite.apply(document, arguments);
              };
              guarded.__aurora = true;
              document.write = guarded;
            }

            // `writeln` 是独立方法，不走被覆盖的 `document.write`——不一起包装的话，
            // 广告换成 writeln 就绕过了写入层，方块照常出现（只能靠 observer 事后删，
            // 回到「闪一下」）。它自动补换行，判定用原样参数即可。
            if (document.writeln && !document.writeln.__aurora) {
              var nativeWriteln = document.writeln;
              var guardedLn = function (markup) {
                if (document.readyState !== 'loading') {
                  countDropped(markup);
                  return;
                }
                if (hasMarker(String(markup))) { countDropped(markup); return; }
                return nativeWriteln.apply(document, arguments);
              };
              guardedLn.__aurora = true;
              document.writeln = guardedLn;
            }

            // 第二段脚本在 1000ms 后用 insertAdjacentHTML 追加方块。
            // 这个没有 document.write 那种「清空文档」的副作用，正常转发即可。
            if (window.Element && Element.prototype.insertAdjacentHTML &&
                !Element.prototype.insertAdjacentHTML.__aurora) {
              var nativeInsert = Element.prototype.insertAdjacentHTML;
              var guardedInsert = function (position, markup) {
                if (hasMarker(String(markup))) return;
                return nativeInsert.apply(this, arguments);
              };
              guardedInsert.__aurora = true;
              Element.prototype.insertAdjacentHTML = guardedInsert;
            }

            // 广告用 WebSocket 从服务端拉 base64 代码再 new Function 执行，内容是下发的，
            // 静态规则拦不住——直接掐掉到广告域名的连接。别的 WebSocket 原样放行。
            //
            // 静态常量（CONNECTING/OPEN/CLOSING/CLOSED）要一起搬过去：站点代码里
            // `WebSocket.OPEN` 这种写法很常见，漏了会让正常功能坏掉。
            if (window.WebSocket && !window.WebSocket.__aurora) {
              var NativeWS = window.WebSocket;
              var Guarded = function (url, protocols) {
                if (isAdUrl(url)) throw new Error('aurora: blocked');
                if (protocols === undefined) return new NativeWS(url);
                return new NativeWS(url, protocols);
              };
              Guarded.prototype = NativeWS.prototype;
              Guarded.CONNECTING = NativeWS.CONNECTING;
              Guarded.OPEN = NativeWS.OPEN;
              Guarded.CLOSING = NativeWS.CLOSING;
              Guarded.CLOSED = NativeWS.CLOSED;
              Guarded.__aurora = true;
              try { window.WebSocket = Guarded; } catch (e) {}
            }

            // **全局 touchend 劫持。** 方块只是第一层：站点第二段脚本还在 `document` 上挂了
            // 一个 touchend 监听，判据是「手指落在距底部 204×(屏宽/620) 像素以内」就跳转
            // （实测 document 级监听存在，函数体里就写着 `top.location=...`）。方块删掉之后
            // 这个监听照样在——之前只删方块，劫持面并没有真正归零。
            //
            // 做法：在 **capture 阶段**先注册一个自己的 touchend，命中底部区域就
            // `stopImmediatePropagation()`，让站点的监听收不到事件。capture 先于冒泡，
            // 且同一阶段按注册顺序执行——我们在页面脚本之前注入，所以一定排在它前面。
            //
            // 只拦「底部那条窄带」，不是整个页面的触摸：用户正常点翻页条/正文区域不受影响。
            if (!document.__auroraTouchGuard) {
              document.__auroraTouchGuard = true;
              document.addEventListener('touchend', function (e) {
                try {
                  var t = e.changedTouches && e.changedTouches[0];
                  if (!t) return;
                  var y = typeof t.clientY === 'number' ? t.clientY : t.screenY;
                  if (typeof y !== 'number') return;
                  var vh = window.innerHeight || 0;
                  var vw = window.screen ? window.screen.availWidth : 0;
                  if (!vh || !vw) return;
                  // 站点用的阈值：204 × (屏宽 / 620)，向下取整。
                  var band = 204 * (vw / 620);
                  if (y > vh - band) {
                    e.stopImmediatePropagation();
                    e.preventDefault();
                  }
                } catch (err) {}
              }, true);
            }
          }

          var observer = null;
          function observe() {
            if (observer || !document.documentElement) return;
            observer = new MutationObserver(function (records) {
              for (var i = 0; i < records.length; i++) {
                var r = records[i];
                for (var j = 0; j < r.addedNodes.length; j++) stripTree(r.addedNodes[j]);
              }
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });
          }

          window.__auroraSiteAd = {
            run: run,
            observe: observe,
            strip: stripTree,
            install: installGuards,
            // 被丢弃的 document.write 记录，供排查「某功能莫名坏掉」时定位。
            dropped: { count: 0, samples: [] }
          };

          function boot() { installGuards(); observe(); run(); applyChapterStyle(); }
          boot();
          document.addEventListener('DOMContentLoaded', boot);
          window.addEventListener('load', boot);

          // 有节奏地扫。广告浮层出现在「点播放之后」、横幅是随机投放的，一次性清理
          // 兜不住。但**不能**一直按固定频率扫：长页面每轮都是 1–2 万节点的属性子串
          // 扫描，永不退避就是持续的帧时间抖动。
          //
          // 退避策略：连续 5 轮没有命中就把间隔拉到 5 秒；一旦又扫到东西（说明广告
          // 又开始活动了），立刻回到快扫。后台标签页直接跳过。
          var idleRounds = 0;
          var slow = false;
          function sweep() {
            if (document.hidden) return;
            var found = false;
            try {
              // 先探一下有没有要处理的，避免每轮都跑完整 run()。
              found = !!document.querySelector('[class*="' + TAP + '"], [class*="' + BANNER + '"]');
            } catch (e) {}
            if (found) {
              idleRounds = 0;
              if (slow) { slow = false; restartSweep(); }
              run();
            } else {
              idleRounds++;
              if (!slow && idleRounds >= 5) { slow = true; restartSweep(); }
            }
            applyChapterStyle();
          }

          var sweepTimer = null;
          function restartSweep() {
            if (sweepTimer) clearInterval(sweepTimer);
            sweepTimer = setInterval(sweep, slow ? SWEEP_MS * 6 : SWEEP_MS);
          }
          restartSweep();
        })();
    """.trimIndent()
}

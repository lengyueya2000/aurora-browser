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
     * 章节列表的容器与按钮。
     *
     * 站点把按钮设成 `height:1rem; overflow:hidden; text-overflow:ellipsis; white-space:nowrap`，
     * 三列固定宽度装不下「第N话-完整标题」，一律被截成「第1话-不打…」。这里把它改回可换行，
     * 并让容器高度自适应。见 [CHAPTER_CSS]。
     */
    const val CHAPTER_LIST_SELECTOR = ".content_playlist"

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
     */
    val CHAPTER_CSS: String = """
        .content_playlist {
          white-space: normal !important;
          height: auto !important;
          max-height: none !important;
          overflow: visible !important;
          font-size: 0 !important;
        }
        .content_playlist li {
          float: none !important;
          display: inline-block !important;
          vertical-align: top !important;
          width: 33.3333% !important;
          padding-bottom: 0.25rem !important;
          padding-right: 0.25rem !important;
          box-sizing: border-box !important;
          font-size: 14.42px !important;
        }
        .content_playlist li a {
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
     */
    fun scriptFor(url: String): String = if (appliesTo(url)) SCRIPT else ""

    val SCRIPT: String = """
        (function () {
          if (window.__auroraSiteAd) { window.__auroraSiteAd.run(); return 'ok'; }

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

          function isAdUrl(url) {
            if (!url) return false;
            var s = String(url);
            for (var i = 0; i < AD_HOSTS.length; i++) {
              if (s.indexOf(AD_HOSTS[i]) >= 0) return true;
            }
            return false;
          }

          function drop(el) {
            if (el && el.parentNode) el.parentNode.removeChild(el);
          }

          function isTapNode(el) {
            if (!el || el.nodeType !== 1) return false;
            var cls = el.getAttribute ? el.getAttribute('class') : '';
            return !!cls && cls.indexOf(TAP) >= 0;
          }

          /** 删掉一棵新插入子树里的劫持方块与广告脚本。 */
          function stripTree(root) {
            if (!root || root.nodeType !== 1) return;
            if (isTapNode(root)) { drop(root); return; }
            if (!root.querySelectorAll) return;
            var taps = root.querySelectorAll('[class*="' + TAP + '"]');
            for (var i = 0; i < taps.length; i++) drop(taps[i]);
            var scripts = root.querySelectorAll('script');
            for (var j = 0; j < scripts.length; j++) {
              var s = scripts[j];
              if (s.src) { if (isAdUrl(s.src)) drop(s); continue; }
              if (hasMarker(s.textContent)) drop(s);
            }
          }

          function run() {
            // 一、点击劫持方块：这是唯一真正挡住手指的东西，先删它。
            var taps = document.querySelectorAll('[class*="' + TAP + '"]');
            for (var i = 0; i < taps.length; i++) drop(taps[i]);

            // 二、底部横幅广告。它把一张大图切成若干 fixed 小块拼出来，所以每个节点都
            //     看不出是广告，只有整组拼起来才是——按类名整组删。
            var banners = document.querySelectorAll('[class*="' + BANNER + '"]');
            for (var b = 0; b < banners.length; b++) drop(banners[b]);

            // 三、广告插进来的样式：不删的话 body 底部会多出 100px 空白。
            drop(document.getElementById(STYLE_ID));

            // 四、年龄确认弹窗：整个节点删掉。
            var gates = document.querySelectorAll(GATE);
            for (var j = 0; j < gates.length; j++) drop(gates[j]);

            // 五、广告脚本节点本身。已经执行过的删掉不会「撤销」，但能防止它被再次触发。
            var scripts = document.querySelectorAll('script');
            for (var k = 0; k < scripts.length; k++) {
              var s = scripts[k];
              if (s.src) { if (isAdUrl(s.src)) drop(s); continue; }
              if (hasMarker(s.textContent)) drop(s);
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

          // 方块是解析期用 document.write 写出来的。拦在写入这一层，方块根本不会出现，
          // 也就没有「闪一下再消失」。顺带让第一段脚本拿不到方块、自己抛错停掉。
          var nativeWrite = document.write;
          document.write = function (markup) {
            if (hasMarker(String(markup))) return;
            return nativeWrite.apply(document, arguments);
          };

          // 第二段脚本在 1000ms 后用 insertAdjacentHTML 追加方块。
          if (window.Element && Element.prototype.insertAdjacentHTML) {
            var nativeInsert = Element.prototype.insertAdjacentHTML;
            Element.prototype.insertAdjacentHTML = function (position, markup) {
              if (hasMarker(String(markup))) return;
              return nativeInsert.apply(this, arguments);
            };
          }

          // 广告用 WebSocket 从服务端拉 base64 代码再 new Function 执行，内容是下发的，
          // 静态规则拦不住——直接掐掉到广告域名的连接。别的 WebSocket 原样放行。
          if (window.WebSocket) {
            var NativeWS = window.WebSocket;
            var Guarded = function (url, protocols) {
              if (isAdUrl(url)) throw new Error('aurora: blocked');
              if (protocols === undefined) return new NativeWS(url);
              return new NativeWS(url, protocols);
            };
            Guarded.prototype = NativeWS.prototype;
            try { window.WebSocket = Guarded; } catch (e) {}
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

          window.__auroraSiteAd = { run: run, observe: observe, strip: stripTree };

          function boot() { observe(); run(); applyChapterStyle(); }
          boot();
          document.addEventListener('DOMContentLoaded', boot);
          window.addEventListener('load', boot);
          // 第二段脚本在 1000ms / 1500ms 后还会动作，所以要有节奏地扫。
          setInterval(function () { if (!document.hidden) { run(); applyChapterStyle(); } }, SWEEP_MS);
        })();
    """.trimIndent()
}

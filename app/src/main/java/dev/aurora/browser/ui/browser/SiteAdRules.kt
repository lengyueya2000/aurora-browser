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

    /** 年龄确认弹窗。整个节点删掉即可，站点脚本对不存在的节点做 `removeClass` 是空转。 */
    const val AGE_GATE_SELECTOR = ".wrap-dialog-t"

    /** 广告脚本插进来的 `<style>`，会把 `body` 底部撑出 100px 空白。 */
    const val STYLE_ID = "fbheyuxg_style_id"

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
          var GATE = '$AGE_GATE_SELECTOR';
          var STYLE_ID = '$STYLE_ID';
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

            // 二、广告插进来的样式：不删的话 body 底部会多出 100px 空白。
            drop(document.getElementById(STYLE_ID));

            // 三、年龄确认弹窗：整个节点删掉。
            var gates = document.querySelectorAll(GATE);
            for (var j = 0; j < gates.length; j++) drop(gates[j]);

            // 四、广告脚本节点本身。已经执行过的删掉不会「撤销」，但能防止它被再次触发。
            var scripts = document.querySelectorAll('script');
            for (var k = 0; k < scripts.length; k++) {
              var s = scripts[k];
              if (s.src) { if (isAdUrl(s.src)) drop(s); continue; }
              if (hasMarker(s.textContent)) drop(s);
            }
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

          function boot() { observe(); run(); }
          boot();
          document.addEventListener('DOMContentLoaded', boot);
          window.addEventListener('load', boot);
          // 第二段脚本在 1000ms / 1500ms 后还会动作，所以要有节奏地扫。
          setInterval(function () { if (!document.hidden) run(); }, SWEEP_MS);
        })();
    """.trimIndent()
}

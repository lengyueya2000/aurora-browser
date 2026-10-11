package dev.aurora.browser.ui.browser

/**
 * 注入页面的去广告脚本。
 *
 * ## 为什么不能只靠「拦域名」
 *
 * 前贴片广告有两种放法，域名拦截只能治其中一种：
 *
 * 1. **广告是另一个域名上的资源**（VAST/IMA 那种，广告本身从广告网络拉）——拦域名就行，
 *    这也是 [dev.aurora.browser.domain.TrackerBlocklist] 在做的。
 * 2. **广告是站点自己的页面元素**：站点把广告配置写在播放器容器的 `data-*` 上，由播放器
 *    插件读出来自己渲染。这类广告的图片可能就在站点的图片 CDN 上，和正文图片同域，
 *    拦域名等于把正文图片一起拦掉，而广告本身还会照放——只是变成一个加载失败的破图。
 *
 * 这里治的是第二种。实测样本是 `4kvm.net`：ArtPlayer + 官方 `artplayer-plugin-ads`，
 * 播放器容器上写着
 *
 * ```
 * data-preroll-ad-type="image"  data-preroll-ad-duration="12"  data-preroll-ad-skip-after="6"
 * ```
 *
 * 也就是「图片广告放 12 秒，6 秒后才允许关」。三件事叠加起来处理，从最干净到兜底：
 *
 * 1. **剥掉 `data-preroll-ad*` 属性**。属性没了，站点自己那段代码拿不到广告配置，插件
 *    根本不会装——一秒广告都不会放，播放器也不会被暂停。这是唯一「零延迟」的办法，
 *    所以脚本必须在页面自己的脚本之前跑（见 `WebViewPool.onPageStarted`）。
 * 2. **把插件的全局名换成空壳**。`artplayer-ads.js` 会 `window.artplayerPluginAds = 插件`，
 *    我们用 setter 拦下来：站点读到的是空壳，插件装上也不会做任何事。这一层不看时序——
 *    就算我们注入晚了，只要站点还没读这个全局，它就拿到空壳。
 * 3. **清掉已经出现的广告浮层并恢复播放**。前两层都赶不上时（注入太晚、站点自己塞的
 *    广告节点），把 `.artplayer-plugin-ads` 摘掉。注意插件的做法是**暂停正片**再盖广告，
 *    所以只删浮层不够，还得把正片播回来，否则用户看到的是「广告没了，画面也不动了」。
 *
 * ## 拿不到的东西
 *
 * 广告和正片在服务端拼成一条流的（同一份 m3u8，前几个分片是广告）在这里是拦不住的——
 * 那要能识别分片内容，属于另一个量级的事。README 的「明确没做的」里写明了。
 */
object AdBlockScript {

    /**
     * 前贴片广告的配置属性前缀。
     *
     * 只匹配 `data-preroll-ad` 本身和 `data-preroll-ad-xxx`，不碰 `data-ad-*`——那个前缀
     * 会误伤 `data-address`、`data-adapter` 这类正常属性，把站点功能弄坏。
     */
    const val PREROLL_ATTRIBUTE_PREFIX = "data-preroll-ad"

    /** ArtPlayer 官方广告插件的全局名（`/static/js/artplayer-ads.*.js` 赋值）。 */
    const val ART_PLAYER_ADS_GLOBAL = "artplayerPluginAds"

    /** 广告浮层。插件把广告塞进这个节点，它下面才是广告的图片或 `<video>`。 */
    const val OVERLAY_SELECTOR = ".artplayer-plugin-ads"

    /**
     * 「关闭广告 / N 秒后可关闭广告」按钮。
     *
     * 插件在倒计时走完之前点了也不生效（内部有个标志位），所以只能反复点，不能指望点一次。
     */
    const val CLOSE_SELECTOR = ".artplayer-plugin-ads-close"

    /** 播放器根节点。清掉浮层后要在**同一个播放器里**恢复播放，不能碰页面上的其他 `<video>`。 */
    const val PLAYER_SELECTOR = ".artplayer"

    /**
     * 无争议的广告位容器。这些都是广告专用标签，删掉不会影响正文：
     * AdSense 的 `<ins>`、广告联盟的 iframe、Google 的广告位 iframe。
     */
    val AD_CONTAINER_SELECTOR: String = listOf(
        "ins.adsbygoogle",
        "iframe[src*=\"doubleclick.net\"]",
        "iframe[src*=\"googlesyndication.com\"]",
        "[id^=\"google_ads_iframe\"]",
    ).joinToString(",")

    /**
     * 需要盯着属性变化的那几个名字。
     *
     * `MutationObserver` 的 `attributeFilter` 只认完整属性名，给不了前缀匹配，所以这里
     * 把实测到的名字列全。只监听这几个（而不是整个文档的所有属性）是因为后者在 SPA 上
     * 会被属性写入刷屏。
     */
    val PREROLL_ATTRIBUTES: List<String> = listOf(
        "type", "image", "video", "code", "link", "duration", "skip-after", "url",
    ).map { "$PREROLL_ATTRIBUTE_PREFIX-$it" }

    /**
     * 轮询间隔。广告浮层是在**用户点播放之后**才出现的，可能离页面加载很久，所以不能
     * 只靠 `MutationObserver` 之外的一次性清理——得有节奏地扫。一次 `querySelectorAll`
     * 的代价可以忽略，页面不可见时直接跳过。
     */
    private const val SWEEP_MS = 800

    val SCRIPT: String = """
        (function () {
          if (window.__auroraAdBlock) { window.__auroraAdBlock.run(); return 'ok'; }

          var AD_ATTR = '$PREROLL_ATTRIBUTE_PREFIX';
          var PLUGIN = '$ART_PLAYER_ADS_GLOBAL';
          var OVERLAY = '$OVERLAY_SELECTOR';
          var CLOSE = '$CLOSE_SELECTOR';
          var PLAYER = '$PLAYER_SELECTOR';
          var CONTAINERS = '$AD_CONTAINER_SELECTOR';
          var AD_ATTRIBUTES = ${PREROLL_ATTRIBUTES.joinToString(", ") { "'$it'" }.let { "[$it]" }};

          function isAdAttribute(name) {
            return name === AD_ATTR || name.indexOf(AD_ATTR + '-') === 0;
          }

          /** 剥掉一个元素上的广告属性。 */
          function stripElement(el) {
            if (!el || el.nodeType !== 1 || !el.attributes) return;
            var names = [];
            for (var i = 0; i < el.attributes.length; i++) {
              var n = el.attributes[i].name;
              if (isAdAttribute(n)) names.push(n);
            }
            for (var j = 0; j < names.length; j++) el.removeAttribute(names[j]);
          }

          /** 剥掉一棵子树上的广告属性。只扫新加进来的节点，代价跟这棵子树的大小成正比。 */
          function stripTree(root) {
            if (!root || root.nodeType !== 1) return;
            stripElement(root);
            if (!root.querySelectorAll) return;
            var all = root.querySelectorAll('*');
            for (var i = 0; i < all.length; i++) stripElement(all[i]);
          }

          /** 把正片播回来。插件的做法是暂停正片再盖广告，删掉浮层不等于恢复播放。 */
          function resumeIn(player) {
            var videos = player.querySelectorAll('video');
            for (var i = 0; i < videos.length; i++) {
              var v = videos[i];
              if (!v.paused) continue;
              try {
                var p = v.play();
                if (p && p.catch) p.catch(function () {});
              } catch (e) {}
            }
          }

          function run() {
            // 一、广告浮层：先点「关闭」走站点自己的跳过路径（倒计时已结束时它真的会生效），
            //      再把节点摘掉，最后把正片播回来。
            var overlays = document.querySelectorAll(OVERLAY);
            for (var i = 0; i < overlays.length; i++) {
              var node = overlays[i];
              var close = node.querySelector(CLOSE);
              if (close) { try { close.click(); } catch (e) {} }
              var player = node.closest ? node.closest(PLAYER) : null;
              if (node.parentNode) node.parentNode.removeChild(node);
              if (player) resumeIn(player);
            }

            // 二、无争议的广告位容器，直接清掉。
            var boxes = document.querySelectorAll(CONTAINERS);
            for (var j = 0; j < boxes.length; j++) {
              var box = boxes[j];
              if (box.parentNode) box.parentNode.removeChild(box);
            }
          }

          // 属性拦截：站点读的是 window.artplayerPluginAds，我们让读到的永远是空壳。
          // 用 defineProperty 而不是「先赋值再覆盖」——脚本加载顺序我们控制不了，
          // 只有拦截才不看时序。setter 留着（不是只留 getter）是因为插件的赋值语句
          // 在严格模式下会给只读属性赋值而抛错，留着 setter 它就能安静地失败。
          function stub() {
            return { name: PLUGIN, skip: function () {}, pause: function () {}, play: function () {} };
          }
          try {
            Object.defineProperty(window, PLUGIN, {
              configurable: true,
              get: function () { return stub; },
              set: function () {}
            });
          } catch (e) {}

          var observer = null;
          function observe() {
            if (observer || !document.documentElement) return;
            observer = new MutationObserver(function (records) {
              for (var i = 0; i < records.length; i++) {
                var r = records[i];
                if (r.type === 'attributes') { stripElement(r.target); continue; }
                for (var j = 0; j < r.addedNodes.length; j++) stripTree(r.addedNodes[j]);
              }
              run();
            });
            observer.observe(document.documentElement, {
              childList: true,
              subtree: true,
              attributes: true,
              attributeFilter: AD_ATTRIBUTES
            });
          }

          window.__auroraAdBlock = { run: run, observe: observe, strip: stripTree };

          function boot() { observe(); run(); }
          boot();
          document.addEventListener('DOMContentLoaded', boot);
          window.addEventListener('load', boot);
          // 广告浮层出现在「点播放之后」，可能离加载很久，所以要有节奏地扫。
          setInterval(function () { if (!document.hidden) run(); }, $SWEEP_MS);
        })();
    """.trimIndent()
}

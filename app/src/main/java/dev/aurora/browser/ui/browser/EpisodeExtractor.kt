package dev.aurora.browser.ui.browser

import dev.aurora.browser.media.EpisodeCodec
import dev.aurora.browser.media.EpisodeList

/**
 * 从页面里抓「选集」的候选链接。
 *
 * **这个脚本只抓候选，不做判定。** 是不是选集、怎么排序、当前是哪一集，全在
 * [EpisodeCodec] 那个纯函数里判——判定规则是这个功能里最需要反复调的部分，放在 Kotlin 里
 * 能写单测、改一次不用重装页面逻辑；脚本越简单越不容易被站点的 CSP 或 DOM 重建搞坏。
 *
 * 抓法两层，命中第一层就不再走第二层：
 *
 * 1. **疑似选集容器**：`class` / `id` 里带 `episod` / `playlist` / `select` / `集` 这些词的
 *    元素，以及「`ul` / `ol` 里至少 3 个 `li > a`」的列表。容器里的链接标 `inList: true`
 *    ——容器信号比「URL 长得像」可靠得多。
 * 2. **兜底**：整页的 `a[href]`，`inList` 全 `false`，交给 Kotlin 的主键法兜底。
 *
 * 脚本是纯读函数、不碰 DOM，所以天然幂等，不需要 `window.__xxx` 守卫；也正因为如此，
 * 它**按需求值**（用户要开面板时、进播放器前），不为每个加载完的页面白付一次代价。
 */
object EpisodeExtractor {

    /** 脚本最多回这么多条候选，超出的丢掉——上限与 [EpisodeCodec.MAX_CANDIDATES] 一致。 */
    internal const val MAX_CANDIDATES = EpisodeCodec.MAX_CANDIDATES

    /**
     * 抓候选的脚本，返回 `[{href, text, inList}, …]`。
     *
     * `href` **原样**返回、不在脚本里绝对化：相对地址怎么解析由 Kotlin 侧统一处理，
     * 那样才有单测可写。空 `href`、`#` 开头的锚点、以及 `javascript:` / `mailto:` / `tel:`
     * 这类不可导航的地址直接在脚本里丢掉，省得它们占满 400 条的上限。
     */
    val SCRIPT: String = """
        (function () {
          var MAX = $MAX_CANDIDATES;
          var out = [], seen = {};
          function push(a, inList) {
            if (out.length >= MAX) return;
            var href = (a.getAttribute('href') || '').trim();
            if (!href || href.charAt(0) === '#') return;
            var low = href.toLowerCase();
            if (low.indexOf('javascript:') === 0 || low.indexOf('mailto:') === 0 ||
                low.indexOf('tel:') === 0 || low.indexOf('data:') === 0) return;
            var text = (a.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 24);
            var key = href + '|' + text;
            if (seen[key]) return;
            seen[key] = 1;
            out.push({ href: href, text: text, inList: !!inList });
          }

          // ① 关键词容器 + 结构像选集的列表。
          var KW = /(episod|playlist|play-list|play_list|select|juji|jishu|选集|剧集|分集|集)/i;
          var containers = [];
          var nodes = document.querySelectorAll('[class],[id]');
          for (var i = 0; i < nodes.length && containers.length < 40; i++) {
            var n = nodes[i];
            var cn = n.className;
            var tag = (typeof cn === 'string' ? cn : '') + ' ' + (n.id || '');
            if (!KW.test(tag)) continue;
            if (n.getElementsByTagName('a').length < 3) continue;
            containers.push(n);
          }
          var lists = document.querySelectorAll('ul,ol');
          for (var j = 0; j < lists.length && containers.length < 80; j++) {
            var lis = lists[j].children, hits = 0;
            for (var k = 0; k < lis.length; k++) {
              if (lis[k].tagName === 'LI' && lis[k].getElementsByTagName('a').length) hits++;
            }
            if (hits >= 3) containers.push(lists[j]);
          }

          var inListHits = 0;
          for (var c = 0; c < containers.length && out.length < MAX; c++) {
            var as = containers[c].getElementsByTagName('a');
            for (var d = 0; d < as.length && out.length < MAX; d++) {
              push(as[d], true);
              inListHits++;
            }
          }

          // ② 兜底：整页链接，标 inList=false。
          if (inListHits === 0) {
            var all = document.getElementsByTagName('a');
            for (var e = 0; e < all.length && out.length < MAX; e++) push(all[e], false);
          }
          return out;
        })()
    """.trimIndent()

    /** 解析脚本返回值。判不出剧集时返回 `null`，调用方据此当成单集视频。 */
    fun parse(raw: String?, pageUrl: String, pageTitle: String): EpisodeList? =
        EpisodeCodec.parse(raw, pageUrl, pageTitle)
}

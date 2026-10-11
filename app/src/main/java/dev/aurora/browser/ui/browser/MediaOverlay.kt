package dev.aurora.browser.ui.browser

import android.webkit.JavascriptInterface

/**
 * 注入页面的「全屏 / 下载 / 投屏」浮层。
 *
 * 为什么不是只放在主菜单里：用户在视频页面上看视频时，菜单藏在工具栏后面，
 * 要点两下才能到「下载视频」。真正好用的位置是视频自己身上——所以把三个按钮
 * 贴到每个 `<video>` 的右上角。
 *
 * 三个按钮**都要回宿主**，没有一个是纯前端动作：
 * - 「全屏」交给内置的 Media3 播放器（[dev.aurora.browser.media.PlayerActivity]）。曾经
 *   直接调 `video.requestFullscreen()`，但站点普遍用 MSE，`<video>` 只是个 `blob:` 壳子，
 *   WebView 把它的自定义 View 搬上窗口之后画面就是黑的——用户实测的「全屏之后是黑屏」。
 * - 「下载」「投屏」需要真实流地址，而页面里只有 `blob:`，得由宿主从网络层嗅探（见
 *   [dev.aurora.browser.media.MediaSniffer]）。
 *
 * 因此三个按钮一律走 `addJavascriptInterface` 注册的 [MediaBridge]，由宿主决定地址与
 * 动作是否成立。
 */
object MediaOverlay {

    /** 注入到页面里的全局对象名（同时也是 JS 接口名）。 */
    const val BRIDGE_NAME = "AuroraMedia"

    /**
     * 脚本可重复执行：已经注入过就只做一次 sync，不会叠出第二组按钮。
     *
     * 定位用 `position:fixed` + `getBoundingClientRect()`，而不是插进视频的父节点：
     * 播放器的父节点十有八九是 `position:static`，插进去会被裁掉或跟着播放器一起被重建。
     * 样式一律走 CSSOM（`el.style.xxx`）而不是 `style="..."` 属性——严格 CSP 的站点会
     * 丢掉内联 style 属性，但拦不住 CSSOM 写入。
     */
    val SCRIPT: String = """
        (function () {
          if (window.__auroraMediaOverlay) { window.__auroraMediaOverlay.sync(); return 'ok'; }

          var BAR_ATTR = 'data-aurora-media';
          // 按钮必须用另一个标记。回收孤儿按钮时按 BAR_ATTR 查，而按钮没有 __owner，
          // 用同一个属性的话每个按钮都会被当成孤儿当场删掉——浮层就只剩个空壳。
          var BTN_ATTR = 'data-aurora-media-btn';

          function abs(u) {
            try { return new URL(u, document.baseURI).href; } catch (e) { return ''; }
          }

          function sourceOf(v) {
            if (v.currentSrc) return v.currentSrc;
            var s = v.getAttribute('src');
            if (s) return abs(s);
            var c = v.querySelector('source');
            return c ? abs(c.getAttribute('src')) : '';
          }

          function button(text, onClick) {
            var b = document.createElement('button');
            b.type = 'button';
            b.setAttribute(BTN_ATTR, '1');
            b.textContent = text;
            // `all:unset` 先把站点自己的 button 样式（宽度、背景、内边距）全部清掉，
            // 后面的声明才不会被页面的 `button{...}` 规则牵着走；display 显式写回
            // inline-flex，否则 unset 之后按钮是 inline，height 根本不生效。
            b.style.cssText = 'all:unset;display:inline-flex;align-items:center;' +
              'justify-content:center;box-sizing:border-box;cursor:pointer;padding:0 10px;' +
              'height:30px;border-radius:15px;color:#e5e7eb;' +
              'background:rgba(11,17,32,.82);border:1px solid rgba(148,163,184,.35);' +
              'font:12px/30px -apple-system,BlinkMacSystemFont,sans-serif;white-space:nowrap;';
            // 捕获阶段拦下来：播放器常在冒泡阶段或 document 上抢点击，晚一步就点不动。
            b.addEventListener('click', function (e) {
              e.preventDefault();
              e.stopPropagation();
              onClick();
            }, true);
            return b;
          }

          // 第三个参数是「附加信息」，宿主按动作解释：play 传的是当前播放到第几秒，
          // 换到内置播放器接着播，而不是从头开始。另外两个用不上，传空串。
          function notify(action, v, extra) {
            try {
              window.$BRIDGE_NAME.onMediaAction(action, sourceOf(v), extra || '');
            } catch (e) {}
          }

          function buildBar(v) {
            var bar = document.createElement('div');
            bar.setAttribute(BAR_ATTR, '1');
            bar.__owner = v;
            bar.style.cssText = 'position:fixed;z-index:2147483647;display:flex;gap:6px;' +
              'pointer-events:auto;';
            bar.appendChild(button('全屏', function () {
              var at = Math.floor(v.currentTime || 0);
              // 页面里这个还在放，不暂停的话内置播放器一开就是两路声音叠在一起。
              try { v.pause(); } catch (e) {}
              notify('play', v, String(at));
            }));
            // 三个按钮都常驻。以前 blob:/MSE 时不显示下载/投屏，可那正是视频站最常见的
            // 情形（欧乐影院就是），用户看到的就是「没有下载按钮」。现在交给宿主去网络层
            // 找真实流，找不到再如实说明，而不是干脆不给按钮。
            bar.appendChild(button('下载', function () { notify('download', v, ''); }));
            bar.appendChild(button('投屏', function () { notify('cast', v, ''); }));
            return bar;
          }

          function place(bar, v) {
            var r = v.getBoundingClientRect();
            if (r.width < 96 || r.height < 48 || r.bottom < 32 || r.top > window.innerHeight - 32) {
              bar.style.display = 'none';
              return;
            }
            bar.style.display = 'flex';
            bar.style.top = Math.max(6, r.top + 8) + 'px';
            bar.style.left = Math.max(6, r.right - bar.offsetWidth - 8) + 'px';
          }

          function sync() {
            var videos = document.querySelectorAll('video');
            var live = [];
            for (var i = 0; i < videos.length; i++) {
              var v = videos[i];
              live.push(v);
              if (!v.__auroraBar || !v.__auroraBar.isConnected) {
                v.__auroraBar = buildBar(v);
                document.documentElement.appendChild(v.__auroraBar);
              }
              place(v.__auroraBar, v);
            }
            // 视频没了（换页、播放器被重建）就把浮层一起收掉。按 BAR_ATTR 查只命中
            // 浮层本身；按钮是它的子节点，跟着一起走。
            var bars = document.querySelectorAll('[' + BAR_ATTR + '="1"]');
            for (var j = 0; j < bars.length; j++) {
              var b = bars[j];
              if (live.indexOf(b.__owner) < 0) b.remove();
            }
          }

          var api = { sync: sync };
          window.__auroraMediaOverlay = api;

          window.addEventListener('scroll', sync, true);
          window.addEventListener('resize', sync, true);
          // 单页应用的播放器是后建的，也没有统一的事件可听，定时兜底最省事。
          setInterval(sync, 700);
          sync();
          return 'ok';
        })()
    """.trimIndent()
}

/**
 * 页面上的按钮能触发的动作。
 *
 * 注入的 JS 接口对页面里所有脚本（含跨域 iframe）都是可见的，所以宿主不能照单全收：
 * 动作名必须在这三个里，地址必须是 http/https/blob/data（页面里的 `<video>` 只有
 * `blob:`，那是 MSE 的壳子，真实地址由宿主自己从网络层嗅探）。
 *
 * 宿主拿到之后还会再跟当前页面提取出来的视频源比对一次，对不上就丢弃——被广告 iframe
 * 乱调也只会被忽略。空地址是允许的：视频源取不到时仍要让宿主有机会用嗅探结果兜底。
 */
object MediaAction {
    /** 用内置播放器全屏播放。 */
    const val PLAY = "play"
    const val DOWNLOAD = "download"
    const val CAST = "cast"

    private val ALLOWED = setOf(PLAY, DOWNLOAD, CAST)

    fun isValid(action: String, url: String): Boolean =
        action in ALLOWED && (
            url.isEmpty() ||
                url.startsWith("http://") || url.startsWith("https://") ||
                url.startsWith("blob:") || url.startsWith("data:")
            )
}

/**
 * 页面 → 宿主的回调桥。
 *
 * `@JavascriptInterface` 的方法跑在 WebView 的 JS 线程上，**不是**主线程，
 * 所以这里只负责转交，由调用方（[WebViewPool]）切回主线程再碰 UI 和系统服务。
 *
 * @param extra 附加信息，按动作解释：`play` 是当前播放到第几秒（换到内置播放器接着播），
 *   其余动作为空串。
 */
class MediaBridge(private val onAction: (action: String, url: String, extra: String) -> Unit) {
    @JavascriptInterface
    fun onMediaAction(action: String, url: String, extra: String) {
        if (!MediaAction.isValid(action, url)) return
        onAction(action, url, extra.orEmpty())
    }
}

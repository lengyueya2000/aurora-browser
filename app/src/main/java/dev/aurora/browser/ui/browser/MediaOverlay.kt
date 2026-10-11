package dev.aurora.browser.ui.browser

import android.webkit.JavascriptInterface

/**
 * 注入页面的「全屏 / 下载 / 投屏」浮层。
 *
 * 为什么不是只放在主菜单里：用户在视频页面上看视频时，菜单藏在工具栏后面，
 * 要点两下才能到「下载视频」。真正好用的位置是视频自己身上——所以把三个按钮
 * 贴到每个 `<video>` 的右上角。
 *
 * 三个按钮里只有「全屏」是纯前端动作（直接调 `requestFullscreen()`，由
 * `WebChromeClient.onShowCustomView` 接管），另外两个要回宿主，走
 * `addJavascriptInterface` 注册的 [MediaBridge]。
 *
 * 为什么不用 `onJsPrompt` 那套：`prompt` 是同步阻塞的，而下载/投屏都要先异步
 * 重新提取一遍页面里的视频源做校验，同步接口表达不了。桥只暴露一个方法，
 * 且宿主会校验动作名与地址，见 [MediaAction]。
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

          var STREAM = /^(blob|data):/i;
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

          function notify(action, v) {
            var url = sourceOf(v);
            if (!url) return;
            try { window.$BRIDGE_NAME.onMediaAction(action, url, ''); } catch (e) {}
          }

          function buildBar(v) {
            var bar = document.createElement('div');
            bar.setAttribute(BAR_ATTR, '1');
            bar.__owner = v;
            bar.style.cssText = 'position:fixed;z-index:2147483647;display:flex;gap:6px;' +
              'pointer-events:auto;';
            bar.appendChild(button('全屏', function () {
              var fn = v.requestFullscreen || v.webkitRequestFullscreen || v.webkitEnterFullscreen;
              if (fn) { try { fn.call(v); } catch (e) {} }
            }));
            // blob:/data:（MSE 流式）既下不了也投不出去，干脆不给按钮，
            // 免得点了只弹一句「不支持」——那等于骗用户点一次。
            if (!STREAM.test(sourceOf(v))) {
              bar.appendChild(button('下载', function () { notify('download', v); }));
              bar.appendChild(button('投屏', function () { notify('cast', v); }));
            }
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
 * 动作名必须在这两个里，地址必须是 http/https。宿主拿到之后还会再跟当前页面提取出来的
 * 视频源比对一次，对不上就丢弃——被广告 iframe 乱调也只会被忽略。
 */
object MediaAction {
    const val DOWNLOAD = "download"
    const val CAST = "cast"

    fun isValid(action: String, url: String): Boolean =
        (action == DOWNLOAD || action == CAST) &&
            (url.startsWith("http://") || url.startsWith("https://"))
}

/**
 * 页面 → 宿主的回调桥。
 *
 * `@JavascriptInterface` 的方法跑在 WebView 的 JS 线程上，**不是**主线程，
 * 所以这里只负责转交，由调用方（[WebViewPool]）切回主线程再碰 UI 和系统服务。
 */
class MediaBridge(private val onAction: (action: String, url: String, type: String) -> Unit) {
    @JavascriptInterface
    fun onMediaAction(action: String, url: String, type: String) {
        if (!MediaAction.isValid(action, url)) return
        onAction(action, url, type.orEmpty())
    }
}

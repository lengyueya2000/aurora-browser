package dev.aurora.browser.ui.reader

/**
 * 阅读模式的正文抽取脚本。
 *
 * 返回 `[标题, 正文]` 数组而不是对象或字符串：`WebView.evaluateJavascript` 会把返回值
 * JSON 编码后回调，数组一次就能解析到位。取 `innerText` 而非 `textContent`——
 * 前者不含 `<script>`/`<style>` 与隐藏元素里的文字。
 */
object ReaderExtractor {
    val SCRIPT: String = """
        (function () {
          var pick = document.querySelector('article')
                  || document.querySelector('main')
                  || document.body;
          var text = pick ? (pick.innerText || '') : '';
          text = text.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
          return [document.title || '', text];
        })();
    """.trimIndent()
}

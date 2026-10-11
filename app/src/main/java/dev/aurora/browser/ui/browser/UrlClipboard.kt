package dev.aurora.browser.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 把网页地址写进系统剪贴板。
 *
 * 为什么需要它：网页里没有「复制链接」这个入口——桌面浏览器靠右键菜单，移动端长按链接
 * 复制的是链接本身，而「把当前这一页的地址拿出来」在网页里根本没有对应的操作。所以这件
 * 事只能由浏览器提供，位置就放在地址栏上：那里本来就是「跟地址有关」的地方，点一下顺手
 * 就拿到了，不必再进菜单翻一遍。
 *
 * 独立成对象而不是塞在 `BrowserScreen` 里，是为了能单测：剪贴板内容对不对（尤其带签名
 * 参数的地址有没有被截断）是这一层唯一的职责，不该靠界面测试去间接验证。
 */
object UrlClipboard {

    /** 剪贴板条目的标签。部分机型会把它显示在复制预览里。 */
    private const val LABEL = "网页地址"

    /**
     * 复制成功返回 true。
     *
     * 地址为空（主页、无痕首页）或系统不给剪贴板时返回 false——**这两种情况下不动已有
     * 的剪贴板内容**：用户剪贴板里可能正放着他要粘的东西，一个空地址把它冲掉是最烦人的
     * 那种「顺手帮倒忙」。
     */
    fun copy(context: Context, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return false
        return runCatching { clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, url)) }.isSuccess
    }
}

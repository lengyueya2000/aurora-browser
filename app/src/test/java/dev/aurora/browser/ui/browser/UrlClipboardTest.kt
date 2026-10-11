package dev.aurora.browser.ui.browser

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 点地址栏复制当前网址。
 *
 * 真机反馈：「打开一个网页之后，点击输入框可以复制当前网页地址」。网页里没有「复制链接」
 * 这个入口，所以只能由浏览器给。这里钉住两件事：**复制的是完整地址**（带签名参数的影视站
 * 地址被截断就等于复制了一个打不开的链接），以及**地址为空时不要动已有剪贴板内容**。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UrlClipboardTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    private val clipboard: ClipboardManager
        get() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    private fun currentText(): String? =
        clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()

    @Test fun copiesTheCurrentUrl() {
        assertThat(UrlClipboard.copy(context, "https://4kvm.net/play/ch4k8uf4p")).isTrue()

        assertThat(currentText()).isEqualTo("https://4kvm.net/play/ch4k8uf4p")
    }

    /** 带查询串的地址必须原样复制：影视站的地址靠签名参数才打得开。 */
    @Test fun keepsQueryStringIntact() {
        val signed = "https://oss.douyinbit.com/m3u8/a54f4c74d9a560d6e86f17c28ddd24cd.m3u8?auth=abc&t=1"

        UrlClipboard.copy(context, signed)

        assertThat(currentText()).isEqualTo(signed)
    }

    /** 主页 / 无痕首页没有地址，这时不能把用户剪贴板里正放着的文本冲掉。 */
    @Test fun blankUrlLeavesTheClipboardAlone() {
        clipboard.setPrimaryClip(ClipData.newPlainText("已有内容", "用户要粘的东西"))

        assertThat(UrlClipboard.copy(context, "")).isFalse()
        assertThat(UrlClipboard.copy(context, "   ")).isFalse()
        assertThat(UrlClipboard.copy(context, null)).isFalse()

        assertThat(currentText()).isEqualTo("用户要粘的东西")
    }

    /** 中文与查询串以外的字符也要原样落盘，别被当成富文本处理。 */
    @Test fun copiesAsPlainText() {
        UrlClipboard.copy(context, "https://例子.测试/路径?q=中文")

        assertThat(clipboard.primaryClip?.description?.getMimeType(0)).isEqualTo("text/plain")
        assertThat(currentText()).isEqualTo("https://例子.测试/路径?q=中文")
    }
}

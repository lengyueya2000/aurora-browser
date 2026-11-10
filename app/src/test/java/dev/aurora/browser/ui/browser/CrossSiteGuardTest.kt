package dev.aurora.browser.ui.browser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 无手势跨站跳转的判定。
 *
 * 这层是为了对付**会轮换的广告域名**：点击劫持的落地域名实测见过
 * `yjdh859.com` / `yjdh868.com` / `mmea167.com` / `hm5119t.vip` / `3376xiu.cc` /
 * `hm4724t.vip` —— 静态表追不上。但行为特征不变：用户没点链接，页面自己把主框架
 * 带去了别的站。所以判据从「域名是谁」换成「这次跳转像不像劫持」。
 *
 * 核心约束：**只拦没有手势的跳转**。用户真点了链接一律放行，正常浏览不受影响。
 */
class CrossSiteGuardTest {

    private fun cross(current: String, target: String) =
        WebViewPool.isCrossSiteUnguarded(current, target)

    @Test fun sameHostIsAllowed() {
        assertThat(cross("https://rmtt4.com/comics-detail/1", "https://rmtt4.com/comics-read/2")).isFalse()
        assertThat(cross("https://rmtt4.com/a", "https://rmtt4.com/b?x=1")).isFalse()
    }

    @Test fun sameRegistrableDomainIsAllowed() {
        // 跨子域是常规做法，不能拦。
        assertThat(cross("https://rmtt4.com/a", "https://www.rmtt4.com/b")).isFalse()
        assertThat(cross("https://www.rmtt4.com/a", "https://rmtt4.com/b")).isFalse()
        assertThat(cross("https://a.example.com/x", "https://b.example.com/y")).isFalse()
    }

    @Test fun crossSiteIsBlocked() {
        // 实测见过的劫持落地页，全部该拦。
        assertThat(cross("https://rmtt4.com/comics-detail/1", "https://www.hm5119t.vip:9527/")).isTrue()
        assertThat(cross("https://rmtt4.com/comics-detail/1", "https://83070hvt.yjdh868.com:8851/tg.htm")).isTrue()
        assertThat(cross("https://rmtt4.com/comics-detail/1", "https://83070tbi.mmea167.com:8861/tg.htm")).isTrue()
        assertThat(cross("https://rmtt4.com/comics-detail/1", "https://1.3376xiu.cc:8888/")).isTrue()
        assertThat(cross("https://rmtt4.com/comics-detail/1", "https://www.hm4724t.vip:9527/")).isTrue()
    }

    /**
     * 解析不出来就**不拦**。
     *
     * 宁可漏一次广告，也不要因为解析异常把正常导航拦掉——用户看到的是「点了没反应」，
     * 比偶尔跳一次广告更让人困惑。
     */
    @Test fun unparseableUrlsAreAllowed() {
        assertThat(cross("", "https://example.com/")).isFalse()
        assertThat(cross("not a url", "https://example.com/")).isFalse()
        assertThat(cross("https://rmtt4.com/", "not a url")).isFalse()
        // about:blank 这类没有 host。
        assertThat(cross("about:blank", "https://example.com/")).isFalse()
    }

    @Test fun registrableDomainTakesLastTwoLabels() {
        assertThat(WebViewPool.registrableDomain("rmtt4.com")).isEqualTo("rmtt4.com")
        assertThat(WebViewPool.registrableDomain("www.rmtt4.com")).isEqualTo("rmtt4.com")
        assertThat(WebViewPool.registrableDomain("a.b.c.example.com")).isEqualTo("example.com")
        // 单段 host 没有注册域。
        assertThat(WebViewPool.registrableDomain("localhost")).isNull()
    }
}

package dev.aurora.browser.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TrackerBlocklistTest {

    @Test fun blocksListedHosts() {
        assertThat(TrackerBlocklist.isBlocked("https://www.google-analytics.com/collect")).isTrue()
        assertThat(TrackerBlocklist.isBlocked("https://doubleclick.net/pixel")).isTrue()
        assertThat(TrackerBlocklist.isBlocked("https://sensorsdata.cn/sa.gif")).isTrue()
    }

    /** 子域也要拦：追踪器几乎总是挂在 `www.` / 地区子域上。 */
    @Test fun blocksSubdomainsOfListedHosts() {
        assertThat(TrackerBlocklist.isBlocked("https://cdn.hotjar.com/x.js")).isTrue()
        assertThat(TrackerBlocklist.isBlocked("https://a.b.criteo.com/x")).isTrue()
    }

    /** 后缀匹配不能误伤「以名单域名结尾但其实是另一个域名」的站点。 */
    @Test fun doesNotBlockLookalikeDomains() {
        assertThat(TrackerBlocklist.isBlocked("https://notdoubleclick.net/x")).isFalse()
        assertThat(TrackerBlocklist.isBlocked("https://myhotjar.com/x")).isFalse()
    }

    @Test fun doesNotBlockOrdinarySites() {
        assertThat(TrackerBlocklist.isBlocked("https://developer.android.com/guide")).isFalse()
        assertThat(TrackerBlocklist.isBlocked("https://www.bing.com/search?q=a")).isFalse()
    }

    @Test fun hostMatchingIsCaseInsensitive() {
        assertThat(TrackerBlocklist.isBlocked("https://WWW.Google-Analytics.COM/collect")).isTrue()
    }

    @Test fun malformedUrlsAreNotBlocked() {
        assertThat(TrackerBlocklist.isBlocked("not a url")).isFalse()
        assertThat(TrackerBlocklist.isBlocked("")).isFalse()
    }

    /** 名单不为空，且「隐私与安全」面板展示的条数与实际规则一致。 */
    @Test fun entriesExposeTheRuleSet() {
        assertThat(TrackerBlocklist.entries).isNotEmpty()
        assertThat(TrackerBlocklist.entries).contains("doubleclick.net")
        assertThat(TrackerBlocklist.entries.all { it.isNotBlank() }).isTrue()
    }
}

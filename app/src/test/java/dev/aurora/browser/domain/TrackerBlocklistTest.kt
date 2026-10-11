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

    /** 视频站的前贴片广告多半从这些广告网络拉（VAST/IMA），拦掉它们等于拦掉贴片本身。 */
    @Test fun blocksVideoAdNetworks() {
        assertThat(TrackerBlocklist.isBlocked("https://2975c.v.fwmrm.net/ad/g/1")).isTrue()
        assertThat(TrackerBlocklist.isBlocked("https://search.spotxchange.com/v1/ads")).isTrue()
        assertThat(TrackerBlocklist.isBlocked("https://a.teads.tv/vast")).isTrue()
        assertThat(TrackerBlocklist.isBlocked("https://pos.baidu.com/wh/o.htm")).isTrue()
    }

    /**
     * `imasdk.googleapis.com` 是**故意不拦**的。
     *
     * 它是 Google IMA 的播放器 SDK，不是一个广告资源地址。拦掉它会让用 IMA 的播放器整个
     * 起不来——用户看到的是「视频放不了」，比多看几秒广告糟糕得多。这条测试把这个取舍钉住，
     * 免得以后有人「顺手补全」名单。
     */
    @Test fun doesNotBlockTheImaPlayerSdk() {
        assertThat(TrackerBlocklist.isBlocked("https://imasdk.googleapis.com/js/sdkloader/ima3.js"))
            .isFalse()
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

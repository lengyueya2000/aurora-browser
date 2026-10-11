package dev.aurora.browser.domain

/**
 * 追踪器拦截名单：按主机名（含子域）后缀匹配。
 *
 * 刻意保持小而可读——只收录最常见的第三方统计/广告域名。误杀的代价（页面功能坏掉）
 * 远高于漏掉一个追踪器，所以不做启发式、不做通配。
 */
object TrackerBlocklist {

    private val HOSTS = setOf(
        // 广告
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "criteo.com", "criteo.net", "taboola.com",
        "outbrain.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
        "casalemedia.com", "openx.net", "smartadserver.com", "adnxs.com",
        // 统计 / 分析
        "google-analytics.com", "googletagmanager.com", "scorecardresearch.com",
        "hotjar.com", "mixpanel.com", "segment.io", "amplitude.com",
        "newrelic.com", "bugsnag.com", "matomo.cloud",
        // 归因 / 社交
        "connect.facebook.net", "branch.io", "adjust.com", "appsflyer.com",
        // 国内统计
        "umeng.com", "cnzz.com", "mmstat.com", "talkingdata.com",
        "growingio.com", "sensorsdata.cn",
    )

    /** 名单条目，供「隐私与安全」面板展示与测试断言。 */
    val entries: Set<String> get() = HOSTS

    fun isBlocked(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return HOSTS.any { host == it || host.endsWith(".$it") }
    }
}

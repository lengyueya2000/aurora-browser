package dev.aurora.browser.domain

/**
 * 追踪器 / 广告拦截名单：按主机名（含子域）后缀匹配。
 *
 * 刻意保持小而可读——只收录最常见的第三方统计/广告域名。误杀的代价（页面功能坏掉）
 * 远高于漏掉一个追踪器，所以不做启发式、不做通配。
 *
 * 名单只覆盖「广告是别的域名上的资源」这一类。前贴片广告还有另一种放法——站点把广告
 * 配置写在播放器容器上、由播放器插件自己渲染——那类拦域名没用（图片可能和正文图片同域），
 * 由注入脚本处理，见 [dev.aurora.browser.ui.browser.AdBlockScript]。
 */
object TrackerBlocklist {

    private val HOSTS = setOf(
        // 广告
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "criteo.com", "criteo.net", "taboola.com",
        "outbrain.com", "adsrvr.org", "rubiconproject.com", "pubmatic.com",
        "casalemedia.com", "openx.net", "smartadserver.com", "adnxs.com",
        // 视频贴片广告网络。视频站的前贴片多半从这里拉广告（VAST/IMA），
        // 拦掉之后播放器要么直接放正片，要么走「广告加载失败」的兜底分支。
        // 刻意不收 imasdk.googleapis.com：它是 Google IMA 的**播放器 SDK**，
        // 拦掉会让用它的播放器整个起不来，代价大于收益。
        "fwmrm.net", "spotxchange.com", "spotx.tv", "springserve.com",
        "aniview.com", "primis.tech", "connatix.com", "unrulymedia.com",
        "teads.tv", "smartclip.net", "yieldmo.com", "innovid.com",
        "tremorhub.com", "telaria.com", "2mdn.net", "serving-sys.com",
        // 国内广告联盟 / 投放
        "pos.baidu.com", "cpro.baidu.com", "union.baidu.com", "mobads.baidu.com",
        "gdt.qq.com", "e.qq.com", "tanx.com", "alimama.com",
        "admaster.com.cn", "miaozhen.com", "ipinyou.com", "allyes.com",
        "mediav.com", "adview.cn", "chanet.com.cn", "irs01.com",
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

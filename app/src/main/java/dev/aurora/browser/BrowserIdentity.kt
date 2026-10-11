package dev.aurora.browser

/**
 * 应用自己发 HTTP 请求时对外自称的身份。
 *
 * 单独抽出来是因为这样的地方不止一处：媒体下载与播放（Media3 的 `DataSource`）、
 * 站点图标抓取。两边都要带一个**像浏览器**的 UA——不少站点对空 UA、或者对
 * `ExoPlayer` / `okhttp` 这类默认 UA 直接回 403。
 *
 * 注意与 [dev.aurora.browser.ui.browser.DesktopMode] 里的桌面版 UA 区分：那个是让**网页**
 * 以为自己在桌面浏览器里（改变站点发哪套页面），这个只是我们请求资源时的自称。
 */
internal object BrowserIdentity {

    /** 手机版 Chrome 的 UA。 */
    const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
}

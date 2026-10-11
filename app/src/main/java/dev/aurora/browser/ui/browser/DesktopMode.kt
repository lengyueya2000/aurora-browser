package dev.aurora.browser.ui.browser

/**
 * 「请求桌面版网站」要换的那一个字符串。
 *
 * 站点是靠 UA 决定发哪套页面的，所以手机上打不开的网页（只给桌面端排版的）基本都靠它。
 * 视口相关的两个开关（`useWideViewPort` / `loadWithOverviewMode`）一直是开着的，
 * 这里不用再动——否则光换 UA 会让站点按桌面宽度排版、却按手机宽度显示，比不换还难看。
 */
internal object DesktopMode {

    /**
     * 桌面 UA 用 Windows 上的 Chrome。
     *
     * 站点认的是「桌面」这个信号，不是具体版本号；但版本号太老会被一部分站点判成
     * 「不支持的浏览器」而直接拦掉，所以给一个近期的版本，而不是某个经典老 UA。
     */
    const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /**
     * 这个 WebView 该用哪个 UA；返回 `null` 表示「不改」。
     *
     * 关掉桌面版时要换回**这台设备 WebView 自己的默认 UA**（由调用方事先记下来），
     * 而不是我们写死的某个手机 UA：WebView 版本一变，写死的那个就过期了。
     * 拿不到默认值（`null`）时宁可不动，也不要给一个错的。
     */
    fun userAgentFor(enabled: Boolean, mobileUserAgent: String?): String? = when {
        enabled -> DESKTOP_USER_AGENT
        mobileUserAgent.isNullOrBlank() -> null
        else -> mobileUserAgent
    }
}

package dev.aurora.browser.ui.browser

/**
 * 浏览界面只有一个「面」，避免路由栈与浏览器手势语义打架。
 * 菜单/面板是叠加层，用独立布尔量表达，不占用 surface。
 */
sealed interface BrowserSurface {
    data object Page : BrowserSurface
    data object Omnibox : BrowserSurface
    data object TabSwitcher : BrowserSurface

    /** 书签与历史（规格 5.9）。 */
    data object Library : BrowserSurface

    /** 下载管理（规格 5.8）。 */
    data object Downloads : BrowserSurface

    /** 隐私与安全面板（规格 5.6）。 */
    data object Privacy : BrowserSurface

    /** 阅读模式（规格 5.7）。 */
    data object Reader : BrowserSurface

    /** 设置（规格 5.11）。 */
    data object Settings : BrowserSurface
}

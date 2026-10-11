package dev.aurora.browser.ui.browser

/**
 * 浏览界面只有一个「面」，避免路由栈与浏览器手势语义打架。
 * 菜单/面板是叠加层，用独立布尔量表达，不占用 surface。
 */
sealed interface BrowserSurface {
    data object Page : BrowserSurface
    data object Omnibox : BrowserSurface
    data object TabSwitcher : BrowserSurface
}

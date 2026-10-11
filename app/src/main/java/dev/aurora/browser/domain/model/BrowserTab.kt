package dev.aurora.browser.domain.model

/**
 * 单个浏览器标签的不可变状态。
 *
 * [url] 为 `null` 表示「新标签页」（尚未导航）；[isNewTab] 是它的派生视图，
 * 让 UI 无需重复判断 `url == null`。[groupId] 预留给 M3 的标签分组，M2 中恒为 `null`。
 */
data class BrowserTab(
    val id: String,
    val url: String? = null,
    val title: String = "新标签页",
    val isIncognito: Boolean = false,
    val groupId: String? = null,
) {
    val isNewTab: Boolean get() = url == null
}

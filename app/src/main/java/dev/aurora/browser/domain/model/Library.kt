package dev.aurora.browser.domain.model

/** 一条书签。`url` 唯一。 */
data class Bookmark(
    val title: String,
    val url: String,
    val createdAt: Long,
)

/** 一条浏览历史。`url` 唯一：同一地址重复访问只保留最新一条并移到最前（见 `PrefsBrowserStore.recordVisit`）。 */
data class HistoryEntry(
    val title: String,
    val url: String,
    val visitedAt: Long,
)

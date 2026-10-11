package dev.aurora.browser.domain.model

/** 一条书签。`url` 唯一。 */
data class Bookmark(
    val title: String,
    val url: String,
    val createdAt: Long,
)

/** 一条浏览历史。`url` 可以重复（同一页多次访问会有多条）。 */
data class HistoryEntry(
    val title: String,
    val url: String,
    val visitedAt: Long,
)

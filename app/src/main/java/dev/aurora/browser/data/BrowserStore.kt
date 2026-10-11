package dev.aurora.browser.data

import dev.aurora.browser.domain.SearchEngine
import dev.aurora.browser.domain.model.Bookmark
import dev.aurora.browser.domain.model.HistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 需要跨进程重启保留的用户数据与偏好。
 *
 * 抽成接口是为了让 `BrowserViewModel` 的测试跑在纯 JVM 上（用 [InMemoryBrowserStore]），
 * 而不必拖进 Android 的 `SharedPreferences`。
 */
interface BrowserStore {
    val bookmarks: StateFlow<List<Bookmark>>
    val history: StateFlow<List<HistoryEntry>>
    val searchEngine: StateFlow<SearchEngine>
    val blockTrackers: StateFlow<Boolean>

    fun addBookmark(title: String, url: String)
    fun removeBookmark(url: String)
    fun recordVisit(title: String, url: String)
    fun clearHistory()

    fun setSearchEngine(engine: SearchEngine)
    fun setBlockTrackers(enabled: Boolean)

    companion object {
        /** 历史上限：只保留最近这么多条，避免持久化文件无限增长。 */
        const val MAX_HISTORY = 500
    }
}

/** 纯内存实现：单测与预览用。 */
class InMemoryBrowserStore(
    bookmarks: List<Bookmark> = emptyList(),
    history: List<HistoryEntry> = emptyList(),
    engine: SearchEngine = SearchEngine.Default,
    block: Boolean = true,
) : BrowserStore {

    private val _bookmarks = MutableStateFlow(bookmarks)
    override val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow(history)
    override val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _engine = MutableStateFlow(engine)
    override val searchEngine: StateFlow<SearchEngine> = _engine.asStateFlow()

    private val _block = MutableStateFlow(block)
    override val blockTrackers: StateFlow<Boolean> = _block.asStateFlow()

    override fun addBookmark(title: String, url: String) {
        if (url.isBlank()) return
        _bookmarks.value = listOf(Bookmark(title.ifBlank { url }, url, now())) +
            _bookmarks.value.filterNot { it.url == url }
    }

    override fun removeBookmark(url: String) {
        _bookmarks.value = _bookmarks.value.filterNot { it.url == url }
    }

    override fun recordVisit(title: String, url: String) {
        if (url.isBlank()) return
        val entry = HistoryEntry(title.ifBlank { url }, url, now())
        // 同一地址只留最新的一条并移到最前（与 PrefsBrowserStore 保持一致）。
        val rest = _history.value.filterNot { it.url == url }
        _history.value = (listOf(entry) + rest).take(BrowserStore.MAX_HISTORY)
    }

    override fun clearHistory() { _history.value = emptyList() }

    override fun setSearchEngine(engine: SearchEngine) { _engine.value = engine }
    override fun setBlockTrackers(enabled: Boolean) { _block.value = enabled }

    private var clock: Long = 0
    private fun now(): Long = clock++
}

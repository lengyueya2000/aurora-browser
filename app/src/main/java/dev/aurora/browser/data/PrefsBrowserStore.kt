package dev.aurora.browser.data

import android.content.Context
import android.content.SharedPreferences
import dev.aurora.browser.domain.SearchEngine
import dev.aurora.browser.domain.model.Bookmark
import dev.aurora.browser.domain.model.HistoryEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * `SharedPreferences` 落地的用户数据。用 `org.json`（Android 自带）序列化，
 * 不引入 Room / DataStore —— 书签与历史都是几百条量级的扁平列表，键值存储足够，
 * 且省掉 KSP 注解处理器的构建成本。数据量或查询需求上来时再换 Room。
 */
class PrefsBrowserStore(context: Context) : BrowserStore {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _bookmarks = MutableStateFlow(readBookmarks())
    override val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _history = MutableStateFlow(readHistory())
    override val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _engine = MutableStateFlow(SearchEngine.fromName(prefs.getString(KEY_ENGINE, null)))
    override val searchEngine: StateFlow<SearchEngine> = _engine.asStateFlow()

    private val _block = MutableStateFlow(prefs.getBoolean(KEY_BLOCK, true))
    override val blockTrackers: StateFlow<Boolean> = _block.asStateFlow()

    override fun addBookmark(title: String, url: String) {
        if (url.isBlank()) return
        val next = listOf(Bookmark(title.ifBlank { url }, url, System.currentTimeMillis())) +
            _bookmarks.value.filterNot { it.url == url }
        _bookmarks.value = next
        writeBookmarks(next)
    }

    override fun removeBookmark(url: String) {
        val next = _bookmarks.value.filterNot { it.url == url }
        _bookmarks.value = next
        writeBookmarks(next)
    }

    override fun recordVisit(title: String, url: String) {
        if (url.isBlank()) return
        val entry = HistoryEntry(title.ifBlank { url }, url, System.currentTimeMillis())
        // 同一次导航会触发两次（navigate 时一次，onPageFinished/onReceivedTitle 时又一次，
        // 刷新也会再触发）：地址与上一条相同时就替换掉它，顺便把「新标签页」这种占位标题
        // 换成真实标题，而不是在历史里堆两条一样的记录。
        val head = _history.value.firstOrNull()
        val next = if (head?.url == url) listOf(entry) + _history.value.drop(1)
        else (listOf(entry) + _history.value).take(BrowserStore.MAX_HISTORY)
        _history.value = next
        writeHistory(next)
    }

    override fun clearHistory() {
        _history.value = emptyList()
        writeHistory(emptyList())
    }

    override fun setSearchEngine(engine: SearchEngine) {
        _engine.value = engine
        prefs.edit().putString(KEY_ENGINE, engine.name).apply()
    }

    override fun setBlockTrackers(enabled: Boolean) {
        _block.value = enabled
        prefs.edit().putBoolean(KEY_BLOCK, enabled).apply()
    }

    private fun readBookmarks(): List<Bookmark> = readArray(KEY_BOOKMARKS).mapNotNull { o ->
        val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Bookmark(o.optString("title").ifBlank { url }, url, o.optLong("createdAt"))
    }

    private fun readHistory(): List<HistoryEntry> = readArray(KEY_HISTORY).mapNotNull { o ->
        val url = o.optString("url").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        HistoryEntry(o.optString("title").ifBlank { url }, url, o.optLong("visitedAt"))
    }

    private fun readArray(key: String): List<JSONObject> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        // 存储损坏时宁可当空列表，也不要让应用在启动路径上崩。
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun writeBookmarks(list: List<Bookmark>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("title", it.title).put("url", it.url).put("createdAt", it.createdAt))
        }
        prefs.edit().putString(KEY_BOOKMARKS, array.toString()).apply()
    }

    private fun writeHistory(list: List<HistoryEntry>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("title", it.title).put("url", it.url).put("visitedAt", it.visitedAt))
        }
        prefs.edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private companion object {
        const val FILE = "aurora_store"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_HISTORY = "history"
        const val KEY_ENGINE = "search_engine"
        const val KEY_BLOCK = "block_trackers"
    }
}

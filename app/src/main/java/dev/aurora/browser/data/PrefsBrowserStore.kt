package dev.aurora.browser.data

import android.content.Context
import android.content.SharedPreferences
import dev.aurora.browser.domain.SearchEngine
import dev.aurora.browser.domain.ThemeMode
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

    private val _engine = MutableStateFlow(SearchEngine.fromName(prefs.readString(KEY_ENGINE)))
    override val searchEngine: StateFlow<SearchEngine> = _engine.asStateFlow()

    private val _block = MutableStateFlow(prefs.readBoolean(KEY_BLOCK, true))
    override val blockTrackers: StateFlow<Boolean> = _block.asStateFlow()

    private val _desktop = MutableStateFlow(prefs.readBoolean(KEY_DESKTOP, false))
    override val desktopMode: StateFlow<Boolean> = _desktop.asStateFlow()

    private val _theme = MutableStateFlow(ThemeMode.fromName(prefs.readString(KEY_THEME)))
    override val themeMode: StateFlow<ThemeMode> = _theme.asStateFlow()

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
        // 刷新还会再来），同一个页面本来也会被反复访问：同一地址只留最新的一条并移到最前。
        //
        // 只跟上一条比是不够的——A→B→A 之后历史里就有两条 A，列表里会出现两行一模一样的
        // 记录，Omnibox 的历史建议也跟着重复。
        val rest = _history.value.filterNot { it.url == url }
        val next = (listOf(entry) + rest).take(BrowserStore.MAX_HISTORY)
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

    override fun setDesktopMode(enabled: Boolean) {
        _desktop.value = enabled
        prefs.edit().putBoolean(KEY_DESKTOP, enabled).apply()
    }

    override fun setThemeMode(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
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
        val raw = prefs.readString(key) ?: return emptyList()
        // 存储损坏时宁可当空列表，也不要让应用在启动路径上崩。
        return runCatching {
            val array = JSONArray(raw)
            // 逐元素取：`getJSONObject` 碰到一个坏元素就抛，整张列表会被一起丢掉（书签/历史
            // 全没了）。`optJSONObject` 对非对象返回 null，跳过坏的那条，其余照常读出来。
            (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * 标量读取的兜底。
     *
     * 上面那句「不要在启动路径上崩」对数组做了，标量却漏了：键存过别的类型时
     * （旧版本改过口径、备份还原回来一份别家的 prefs 文件）`getString`/`getBoolean`
     * 会抛 `ClassCastException`，而这几行都在构造函数的属性初始化里——一崩就是启动即崩。
     */
    private fun SharedPreferences.readString(key: String): String? =
        runCatching { getString(key, null) }.getOrNull()

    private fun SharedPreferences.readBoolean(key: String, fallback: Boolean): Boolean =
        runCatching { getBoolean(key, fallback) }.getOrDefault(fallback)

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
        const val KEY_DESKTOP = "desktop_mode"
        const val KEY_THEME = "theme_mode"
    }
}

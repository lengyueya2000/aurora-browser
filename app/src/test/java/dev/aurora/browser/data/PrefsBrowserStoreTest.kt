package dev.aurora.browser.data

import android.content.Context
import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.domain.SearchEngine
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `SharedPreferences` 落地：跨实例读回、坏数据不崩。
 * 用 Robolectric 是因为 `SharedPreferences` 是 Android API。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsBrowserStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs() = context.getSharedPreferences("aurora_store", Context.MODE_PRIVATE)

    @Test fun bookmarksSurviveANewStoreInstance() {
        PrefsBrowserStore(context).apply {
            addBookmark("A 站", "https://a.com")
            addBookmark("B 站", "https://b.com")
        }
        val reopened = PrefsBrowserStore(context)
        assertThat(reopened.bookmarks.value.map { it.title }).containsExactly("B 站", "A 站").inOrder()
    }

    @Test fun historySurvivesANewStoreInstance() {
        PrefsBrowserStore(context).recordVisit("A 站", "https://a.com")
        assertThat(PrefsBrowserStore(context).history.value.map { it.url })
            .containsExactly("https://a.com")
    }

    @Test fun preferencesSurviveANewStoreInstance() {
        PrefsBrowserStore(context).apply {
            setSearchEngine(SearchEngine.BAIDU)
            setBlockTrackers(false)
            setDesktopMode(true)
        }
        val reopened = PrefsBrowserStore(context)
        assertThat(reopened.searchEngine.value).isEqualTo(SearchEngine.BAIDU)
        assertThat(reopened.blockTrackers.value).isFalse()
        assertThat(reopened.desktopMode.value).isTrue()
    }

    @Test fun defaultsAreUsedOnFirstLaunch() {
        val store = PrefsBrowserStore(context)
        assertThat(store.searchEngine.value).isEqualTo(SearchEngine.Default)
        assertThat(store.blockTrackers.value).isTrue()
        // 桌面版默认关着：绝大多数站点在手机版下更好用，不该让用户先去关掉它。
        assertThat(store.desktopMode.value).isFalse()
        assertThat(store.bookmarks.value).isEmpty()
        assertThat(store.history.value).isEmpty()
    }

    @Test fun removingABookmarkPersists() {
        val store = PrefsBrowserStore(context)
        store.addBookmark("A 站", "https://a.com")
        store.removeBookmark("https://a.com")
        assertThat(PrefsBrowserStore(context).bookmarks.value).isEmpty()
    }

    @Test fun clearHistoryPersists() {
        val store = PrefsBrowserStore(context)
        store.recordVisit("A 站", "https://a.com")
        store.clearHistory()
        assertThat(PrefsBrowserStore(context).history.value).isEmpty()
    }

    /** 同一次导航记两次只留一条（与内存实现一致）。 */
    @Test fun consecutiveSameUrlIsRecordedOnce() {
        val store = PrefsBrowserStore(context)
        store.recordVisit("新标签页", "https://a.com")
        store.recordVisit("A 站", "https://a.com")
        assertThat(PrefsBrowserStore(context).history.value).hasSize(1)
        assertThat(store.history.value.first().title).isEqualTo("A 站")
    }

    /** 回到访问过的页面：只留一条并移到最前（与内存实现一致）。 */
    @Test fun revisitingAnEarlierUrlDoesNotDuplicate() {
        val store = PrefsBrowserStore(context)
        store.recordVisit("A 站", "https://a.com")
        store.recordVisit("B 站", "https://b.com")
        store.recordVisit("A 站再来", "https://a.com")
        val reopened = PrefsBrowserStore(context).history.value
        assertThat(reopened.map { it.url })
            .containsExactly("https://a.com", "https://b.com").inOrder()
        assertThat(reopened.first().title).isEqualTo("A 站再来")
    }

    /** 存储损坏（外部改过文件、旧版本格式）时宁可当空列表，也不能在启动路径上崩。 */
    @Test fun corruptStoredJsonIsTreatedAsEmpty() {
        prefs().edit().putString("bookmarks", "{not json").putString("history", "[]]").commit()
        val store = PrefsBrowserStore(context)
        assertThat(store.bookmarks.value).isEmpty()
        assertThat(store.history.value).isEmpty()
    }

    /** 缺少 url 字段的条目直接丢掉，不产生半残记录。 */
    @Test fun entriesWithoutUrlAreSkipped() {
        prefs().edit().putString("bookmarks", """[{"title":"无地址"},{"title":"A","url":"https://a.com"}]""")
            .commit()
        val store = PrefsBrowserStore(context)
        assertThat(store.bookmarks.value.map { it.url }).containsExactly("https://a.com")
    }

    /**
     * 数组里混进一个坏元素，只丢那一条，其余照常读出来。
     *
     * 以前用 `getJSONObject`：碰到一个非对象就抛，`runCatching` 把整张列表兜成空——
     * 一条坏数据等于把全部书签清空。现在逐元素 `optJSONObject`，坏的跳过、好的留下。
     */
    @Test fun oneMalformedElementDoesNotDiscardTheWholeList() {
        prefs().edit().putString(
            "bookmarks",
            """[{"title":"A","url":"https://a.com"}, 42, {"title":"B","url":"https://b.com"}]""",
        ).commit()
        val store = PrefsBrowserStore(context)
        assertThat(store.bookmarks.value.map { it.url })
            .containsExactly("https://a.com", "https://b.com").inOrder()
    }

    /** 未知的搜索引擎名（降级安装、手工改过）回落到默认引擎。 */
    @Test fun unknownSearchEngineFallsBackToDefault() {
        prefs().edit().putString("search_engine", "YAHOO").commit()
        assertThat(PrefsBrowserStore(context).searchEngine.value).isEqualTo(SearchEngine.Default)
    }

    /**
     * 键存成了别的类型（旧版本改过口径、备份还原回来一份别家的文件）时也不能崩。
     *
     * `getBoolean` 遇到 String 会抛 `ClassCastException`，而这几行在构造函数里——
     * 一崩就是启动即崩。兜底之后按「没存过」处理，退回默认值。
     */
    @Test fun typeMismatchedPreferencesFallBackToDefaults() {
        prefs().edit()
            .putString("block_trackers", "true")   // 本该是 Boolean
            .putBoolean("search_engine", true)     // 本该是 String
            .putInt("desktop_mode", 1)             // 本该是 Boolean
            .commit()

        val store = PrefsBrowserStore(context)
        assertThat(store.blockTrackers.value).isTrue()
        assertThat(store.searchEngine.value).isEqualTo(SearchEngine.Default)
        assertThat(store.desktopMode.value).isFalse()
    }
}

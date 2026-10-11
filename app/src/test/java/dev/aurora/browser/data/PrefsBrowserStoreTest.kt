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
        }
        val reopened = PrefsBrowserStore(context)
        assertThat(reopened.searchEngine.value).isEqualTo(SearchEngine.BAIDU)
        assertThat(reopened.blockTrackers.value).isFalse()
    }

    @Test fun defaultsAreUsedOnFirstLaunch() {
        val store = PrefsBrowserStore(context)
        assertThat(store.searchEngine.value).isEqualTo(SearchEngine.Default)
        assertThat(store.blockTrackers.value).isTrue()
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

    /** 未知的搜索引擎名（降级安装、手工改过）回落到默认引擎。 */
    @Test fun unknownSearchEngineFallsBackToDefault() {
        prefs().edit().putString("search_engine", "YAHOO").commit()
        assertThat(PrefsBrowserStore(context).searchEngine.value).isEqualTo(SearchEngine.Default)
    }
}

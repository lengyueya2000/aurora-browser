package dev.aurora.browser.data

import com.google.common.truth.Truth.assertThat
import dev.aurora.browser.domain.SearchEngine
import dev.aurora.browser.domain.model.Bookmark
import dev.aurora.browser.domain.model.HistoryEntry
import org.junit.Test

class BrowserStoreTest {

    @Test fun newBookmarkGoesToTheHead() {
        val store = InMemoryBrowserStore(bookmarks = listOf(Bookmark("旧", "https://old.com", 0)))
        store.addBookmark("新", "https://new.com")
        assertThat(store.bookmarks.value.map { it.url })
            .containsExactly("https://new.com", "https://old.com").inOrder()
    }

    /** 同一地址重复收藏只保留一条，否则书签列表会出现重复项。 */
    @Test fun bookmarkingTheSameUrlTwiceKeepsOneEntry() {
        val store = InMemoryBrowserStore()
        store.addBookmark("第一次", "https://a.com")
        store.addBookmark("第二次", "https://a.com")
        assertThat(store.bookmarks.value).hasSize(1)
        assertThat(store.bookmarks.value.first().title).isEqualTo("第二次")
    }

    @Test fun blankUrlIsNotBookmarkedOrRecorded() {
        val store = InMemoryBrowserStore()
        store.addBookmark("x", "")
        store.recordVisit("x", "   ")
        assertThat(store.bookmarks.value).isEmpty()
        assertThat(store.history.value).isEmpty()
    }

    @Test fun titleFallsBackToUrlWhenBlank() {
        val store = InMemoryBrowserStore()
        store.addBookmark("", "https://a.com")
        store.recordVisit("", "https://b.com")
        assertThat(store.bookmarks.value.first().title).isEqualTo("https://a.com")
        assertThat(store.history.value.first().title).isEqualTo("https://b.com")
    }

    @Test fun removingBookmarkDropsIt() {
        val store = InMemoryBrowserStore(bookmarks = listOf(Bookmark("A", "https://a.com", 0)))
        store.removeBookmark("https://a.com")
        assertThat(store.bookmarks.value).isEmpty()
    }

    /**
     * 同一次导航会记两次（navigate 时一次，onPageFinished 时一次）：地址相同就替换上一条，
     * 顺带把占位标题换成真实标题，而不是在历史里堆两条。
     */
    @Test fun consecutiveVisitToTheSameUrlUpdatesInsteadOfAppending() {
        val store = InMemoryBrowserStore()
        store.recordVisit("新标签页", "https://a.com")
        store.recordVisit("A 站", "https://a.com")
        assertThat(store.history.value).hasSize(1)
        assertThat(store.history.value.first().title).isEqualTo("A 站")
    }

    @Test fun visitingDifferentUrlsAppends() {
        val store = InMemoryBrowserStore()
        store.recordVisit("A", "https://a.com")
        store.recordVisit("B", "https://b.com")
        store.recordVisit("A", "https://a.com")
        assertThat(store.history.value.map { it.url })
            .containsExactly("https://a.com", "https://b.com", "https://a.com").inOrder()
    }

    @Test fun historyIsCappedAtTheMaximum() {
        val store = InMemoryBrowserStore()
        repeat(BrowserStore.MAX_HISTORY + 25) { store.recordVisit("T$it", "https://a.com/$it") }
        assertThat(store.history.value).hasSize(BrowserStore.MAX_HISTORY)
        // 保留的是最近的：最新的在最前
        assertThat(store.history.value.first().url)
            .isEqualTo("https://a.com/${BrowserStore.MAX_HISTORY + 24}")
    }

    @Test fun clearHistoryEmptiesOnlyHistory() {
        val store = InMemoryBrowserStore(bookmarks = listOf(Bookmark("A", "https://a.com", 0)))
        store.recordVisit("A", "https://a.com")
        store.clearHistory()
        assertThat(store.history.value).isEmpty()
        assertThat(store.bookmarks.value).hasSize(1)
    }

    @Test fun preferenceSettersUpdateTheirFlows() {
        val store = InMemoryBrowserStore()
        store.setSearchEngine(SearchEngine.GOOGLE)
        store.setShowFeed(false)
        store.setBlockTrackers(false)
        assertThat(store.searchEngine.value).isEqualTo(SearchEngine.GOOGLE)
        assertThat(store.showFeed.value).isFalse()
        assertThat(store.blockTrackers.value).isFalse()
    }

    @Test fun historyEntriesKeepTheirTimestamps() {
        val store = InMemoryBrowserStore(
            history = listOf(HistoryEntry("A", "https://a.com", 1234L)),
        )
        assertThat(store.history.value.first().visitedAt).isEqualTo(1234L)
    }
}

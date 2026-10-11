package dev.aurora.browser.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TabRepositoryTest {
    @Test fun startsWithExactlyOneActiveBlankTab() {
        val repo = TabRepository()
        assertThat(repo.tabs.value).hasSize(1)
        assertThat(repo.active.isNewTab).isTrue()
        assertThat(repo.activeId.value).isEqualTo(repo.tabs.value.first().id)
    }

    @Test fun newTabBecomesActiveAndIsAppended() {
        val repo = TabRepository()
        val first = repo.tabs.value.first().id
        val second = repo.newTab("https://a.com")
        assertThat(repo.tabs.value).hasSize(2)
        assertThat(repo.tabs.value.last().id).isEqualTo(second.id)
        assertThat(repo.activeId.value).isEqualTo(second.id)
        assertThat(repo.tabs.value.first().id).isEqualTo(first)
    }

    @Test fun closingActiveTabSelectsNeighbour() {
        val repo = TabRepository()
        val a = repo.tabs.value.first().id
        val b = repo.newTab("https://b.com").id
        repo.select(a)
        repo.close(a)
        assertThat(repo.tabs.value.map { it.id }).containsExactly(b)
        assertThat(repo.activeId.value).isEqualTo(b)
    }

    @Test fun closingLastTabCreatesBlankTab() {
        val repo = TabRepository()
        repo.close(repo.activeId.value)
        assertThat(repo.tabs.value).hasSize(1)
        assertThat(repo.active.isNewTab).isTrue()
    }

    @Test fun closingInactiveTabKeepsActive() {
        val repo = TabRepository()
        val a = repo.tabs.value.first().id
        val b = repo.newTab("https://b.com").id
        repo.select(b)
        repo.close(a)
        assertThat(repo.activeId.value).isEqualTo(b)
        assertThat(repo.tabs.value).hasSize(1)
    }

    @Test fun updateSetsUrlAndTitle() {
        val repo = TabRepository()
        val id = repo.activeId.value
        repo.update(id, url = "https://c.com", title = "C")
        assertThat(repo.active.url).isEqualTo("https://c.com")
        assertThat(repo.active.title).isEqualTo("C")
        assertThat(repo.active.isNewTab).isFalse()
    }

    @Test fun moveReordersTabs() {
        val repo = TabRepository()
        val a = repo.tabs.value.first().id
        val b = repo.newTab("https://b.com").id
        val c = repo.newTab("https://c.com").id
        repo.move(from = 0, to = 2)
        assertThat(repo.tabs.value.map { it.id }).containsExactly(b, c, a).inOrder()
    }

    @Test fun tabsListIsImmutableSnapshot() {
        val repo = TabRepository()
        val snapshot = repo.tabs.value
        repo.newTab("https://d.com")
        assertThat(snapshot).hasSize(1)              // 旧快照不被就地修改
        assertThat(repo.tabs.value).hasSize(2)
    }

    @Test fun incognitoTabsAreFlagged() {
        val repo = TabRepository()
        val t = repo.newTab("https://e.com", incognito = true)
        assertThat(repo.tabs.value.first { it.id == t.id }.isIncognito).isTrue()
    }
}

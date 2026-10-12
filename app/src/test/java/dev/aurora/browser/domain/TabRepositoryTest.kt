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

    /**
     * 拖动排序的语义是「把元素插到目标下标处」，因此两个方向的结果不对称：
     * 0→2 得 [b,c,a]，2→0 得 [c,a,b]。只写一个方向会掩盖另一半的错误实现
     * （例如先移除再按下标插入的经典 off-by-one）。
     */
    @Test fun moveIsDirectionSensitive() {
        val repo = TabRepository()
        repo.newTab("https://b.com")
        repo.newTab("https://c.com")
        val ids = repo.tabs.value.map { it.id }

        repo.move(from = 2, to = 0)
        assertThat(repo.tabs.value.map { it.id })
            .containsExactly(ids[2], ids[0], ids[1]).inOrder()
    }

    @Test fun moveOutOfRangeIsANoOp() {
        val repo = TabRepository()
        repo.newTab("https://b.com")
        val before = repo.tabs.value.map { it.id }
        repo.move(from = -1, to = 0)
        repo.move(from = 0, to = 5)
        repo.move(from = 9, to = 0)
        assertThat(repo.tabs.value.map { it.id }).containsExactlyElementsIn(before).inOrder()
    }

    @Test fun closingUnknownTabIsANoOp() {
        val repo = TabRepository()
        val before = repo.tabs.value.map { it.id }
        repo.close("does-not-exist")
        assertThat(repo.tabs.value.map { it.id }).containsExactlyElementsIn(before).inOrder()
        assertThat(repo.activeId.value).isEqualTo(before.first())
    }

    @Test fun selectingUnknownTabKeepsActive() {
        val repo = TabRepository()
        val active = repo.activeId.value
        repo.select("does-not-exist")
        assertThat(repo.activeId.value).isEqualTo(active)
    }

    /** 无痕标记只影响该标签，不能渗到别的标签上。 */
    @Test fun incognitoFlagDoesNotLeakToOtherTabs() {
        val repo = TabRepository()
        val normal = repo.tabs.value.first().id
        val incognito = repo.newTab("https://e.com", incognito = true).id
        assertThat(repo.tabs.value.first { it.id == incognito }.isIncognito).isTrue()
        assertThat(repo.tabs.value.first { it.id == normal }.isIncognito).isFalse()
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

    /**
     * 退回首页要把地址和标题一起清掉。
     *
     * 标题尤其容易漏：只清地址的话，首页会顶着上一个网页的标题（标签网格里那一行）。
     * 也正因为 `update` 的 `url ?: tab.url` 语义清不掉地址，这里才单开一个方法。
     */
    @Test fun goHomeClearsUrlAndTitle() {
        val repo = TabRepository()
        val t = repo.newTab("https://a.com")
        repo.update(t.id, title = "某个网页")
        repo.goHome(t.id)
        val tab = repo.tabs.value.first { it.id == t.id }
        assertThat(tab.isNewTab).isTrue()
        assertThat(tab.title).isEqualTo("新标签页")
    }

    /** 退首页不该把标签变成另一种标签：无痕属性与 id 都留着。 */
    @Test fun goHomeKeepsTheTabsIdentity() {
        val repo = TabRepository()
        val t = repo.newTab("https://a.com", incognito = true)
        repo.goHome(t.id)
        val tab = repo.tabs.value.first { it.id == t.id }
        assertThat(tab.id).isEqualTo(t.id)
        assertThat(tab.isIncognito).isTrue()
    }

    /** 只动指定的那个标签，别的标签的页面不该被牵连。 */
    @Test fun goHomeLeavesOtherTabsAlone() {
        val repo = TabRepository()
        val a = repo.newTab("https://a.com").id
        val b = repo.newTab("https://b.com").id
        repo.goHome(b)
        assertThat(repo.tabs.value.first { it.id == a }.url).isEqualTo("https://a.com")
        assertThat(repo.tabs.value.first { it.id == b }.isNewTab).isTrue()
    }
}

package dev.aurora.browser.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 版本更新检测的核心逻辑。
 *
 * 版本比较是最容易写错的地方——字符串比较会把 `1.10` 判成比 `1.9` 旧，
 * 这类错误在真机上表现为「有更新却不提示」或「没更新天天弹」。所以这里全部
 * 用纯函数，测试直接驱动。
 */
class UpdateCheckerTest {

    // ---------- parseVersion ----------

    @Test fun parsesVTaggedVersions() {
        assertThat(UpdateChecker.parseVersion("v1.22")?.toList()).isEqualTo(listOf(1, 22, 0))
        assertThat(UpdateChecker.parseVersion("V1.2.3")?.toList()).isEqualTo(listOf(1, 2, 3))
        assertThat(UpdateChecker.parseVersion("  v10.20.30  ")?.toList()).isEqualTo(listOf(10, 20, 30))
    }

    @Test fun parsesBareVersions() {
        assertThat(UpdateChecker.parseVersion("1.22")?.toList()).isEqualTo(listOf(1, 22, 0))
        assertThat(UpdateChecker.parseVersion("2.0.1")?.toList()).isEqualTo(listOf(2, 0, 1))
    }

    @Test fun extraSegmentsKeepFirstThree() {
        assertThat(UpdateChecker.parseVersion("1.2.3.4")?.toList()).isEqualTo(listOf(1, 2, 3))
    }

    @Test fun rejectsMalformedVersions() {
        // 注意 1.2 / v1.2 是合法的（当前版本 1.22 就是两段），不能在这里。
        for (bad in listOf("", "v", "v1", "1", "v1.2.x", "abc", "1.2.-3", "  ", "v.1.2")) {
            assertThat(UpdateChecker.parseVersion(bad)).isNull()
        }
    }

    // ---------- isNewer ----------

    @Test fun comparesNumericallyNotLexically() {
        // 字符串比较会判 "1.10" < "1.9"，整数比较不会。
        assertThat(UpdateChecker.isNewer("v1.10", "v1.9")).isTrue()
        assertThat(UpdateChecker.isNewer("1.2.10", "1.2.9")).isTrue()
        assertThat(UpdateChecker.isNewer("v2.0.0", "v1.99.99")).isTrue()
    }

    @Test fun equalAndOlderVersionsAreNotNewer() {
        assertThat(UpdateChecker.isNewer("v1.22", "v1.22")).isFalse()
        assertThat(UpdateChecker.isNewer("1.21.9", "1.22.0")).isFalse()
        assertThat(UpdateChecker.isNewer("v1.2", "v1.10")).isFalse()
        // 两段 vs 三段：patch 补 0，1.22 == 1.22.0。
        assertThat(UpdateChecker.isNewer("1.22", "v1.22.0")).isFalse()
        assertThat(UpdateChecker.isNewer("1.22.1", "1.22")).isTrue()
    }

    @Test fun malformedVersionNeverReportsUpdate() {
        // 宁可不提示，也不能因为一个奇怪的 tag 误报。
        assertThat(UpdateChecker.isNewer("garbage", "1.22")).isFalse()
        assertThat(UpdateChecker.isNewer("v1.22", "garbage")).isFalse()
        assertThat(UpdateChecker.isNewer("v1", "v1.22")).isFalse()
    }

    // ---------- parseLatestRelease ----------

    @Test fun parsesFirstNonDraftRelease() {
        val json = """
            [
              {"tag_name":"v9.9.9","draft":true,"html_url":"https://x/draft","body":"草稿"},
              {"tag_name":"v1.23","draft":false,"html_url":"https://x/v123","body":"新功能"},
              {"tag_name":"v1.24","draft":false,"html_url":"https://x/v124","body":"更新的"}
            ]
        """.trimIndent()
        val info = UpdateChecker.parseLatestRelease(json)
        assertThat(info).isNotNull()
        assertThat(info!!.version).isEqualTo("v1.23")
        assertThat(info.notes).isEqualTo("新功能")
        assertThat(info.pageUrl).isEqualTo("https://x/v123")
    }

    @Test fun doesNotSkipPreReleases() {
        // 本项目当前所有发布都是 pre-release，不能按这个字段过滤。
        val json = """
            [{"tag_name":"v1.23","prerelease":true,"draft":false,"html_url":"https://x/v123"}]
        """.trimIndent()
        assertThat(UpdateChecker.parseLatestRelease(json)?.version).isEqualTo("v1.23")
    }

    @Test fun skipsEntriesWithUnparseableTags() {
        val json = """
            [
              {"tag_name":"nightly","draft":false,"html_url":"https://x/n"},
              {"tag_name":"v1.23","draft":false,"html_url":"https://x/v123","body":"ok"}
            ]
        """.trimIndent()
        assertThat(UpdateChecker.parseLatestRelease(json)?.version).isEqualTo("v1.23")
    }

    @Test fun malformedJsonReturnsNull() {
        assertThat(UpdateChecker.parseLatestRelease("not json")).isNull()
        assertThat(UpdateChecker.parseLatestRelease("{}")).isNull()
        assertThat(UpdateChecker.parseLatestRelease("[]")).isNull()
    }

    // ---------- check ----------

    @Test fun checkReportsNewVersion() {
        val json = """[{"tag_name":"v1.23","html_url":"https://x/v123","body":"修复"}]"""
        val result = UpdateChecker.check("1.22", null) { json }
        assertThat(result).isInstanceOf(UpdateChecker.CheckResult.NewVersion::class.java)
        val info = (result as UpdateChecker.CheckResult.NewVersion).info
        assertThat(info.version).isEqualTo("v1.23")
    }

    @Test fun checkHonoursIgnoredVersion() {
        val json = """[{"tag_name":"v1.23","html_url":"https://x/v123"}]"""
        val result = UpdateChecker.check("1.22", "v1.23") { json }
        assertThat(result).isEqualTo(UpdateChecker.CheckResult.UpToDate)
    }

    @Test fun checkReportsUpToDateAndFailure() {
        val old = """[{"tag_name":"v1.21","html_url":"https://x/v121"}]"""
        assertThat(UpdateChecker.check("1.22", null) { old })
            .isEqualTo(UpdateChecker.CheckResult.UpToDate)
        assertThat(UpdateChecker.check("1.22", null) { null })
            .isEqualTo(UpdateChecker.CheckResult.Failed)
    }

    /** 用仓库真实的 API 样本验证：至少能解析出当前已发布的最高版本。 */
    @Test fun parsesRealRepositoryFormat() {
        val json = """
            [{"url":"https://api.github.com/repos/lengyueya2000/aurora-browser/releases/1",
              "html_url":"https://github.com/lengyueya2000/aurora-browser/releases/tag/v1.22",
              "tag_name":"v1.22","name":"v1.22 — 按代码审查修掉 9 处问题",
              "draft":false,"prerelease":true,
              "body":"## 1.22 — 按代码审查修掉 9 处问题"}]
        """.trimIndent()
        val info = UpdateChecker.parseLatestRelease(json)
        assertThat(info).isNotNull()
        assertThat(info!!.version).isEqualTo("v1.22")
        assertThat(info.notes).contains("代码审查")
        // 当前就是 1.22，不该提示自己。
        assertThat(UpdateChecker.isNewer(info.version, "1.22")).isFalse()
    }
}

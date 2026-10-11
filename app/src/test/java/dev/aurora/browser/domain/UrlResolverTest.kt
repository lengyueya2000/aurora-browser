package dev.aurora.browser.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UrlResolverTest {

    @Test fun keepsFullyQualifiedUrl() {
        assertThat(UrlResolver.resolve("https://example.com/a?b=1")).isEqualTo("https://example.com/a?b=1")
        assertThat(UrlResolver.resolve("http://example.com")).isEqualTo("http://example.com")
    }

    @Test fun prefixesHttpsForBareHost() {
        assertThat(UrlResolver.resolve("example.com")).isEqualTo("https://example.com")
        assertThat(UrlResolver.resolve("www.a.co.uk/path?q=1")).isEqualTo("https://www.a.co.uk/path?q=1")
        assertThat(UrlResolver.resolve("localhost:8080")).isEqualTo("https://localhost:8080")
        assertThat(UrlResolver.resolve("192.168.1.1:8080/x")).isEqualTo("https://192.168.1.1:8080/x")
    }

    @Test fun keepsSchemeWithoutAuthority() {
        assertThat(UrlResolver.resolve("about:blank")).isEqualTo("about:blank")
        assertThat(UrlResolver.resolve("mailto:someone@example.com")).isEqualTo("mailto:someone@example.com")
        assertThat(UrlResolver.resolve("tel:10086")).isEqualTo("tel:10086")
    }

    @Test fun fallsBackToSearchForPlainWords() {
        assertThat(UrlResolver.resolve("kotlin"))
            .isEqualTo("https://www.bing.com/search?q=kotlin")
    }

    @Test fun fallsBackToSearchForPhrasesWithSpaces() {
        assertThat(UrlResolver.resolve("how to learn kotlin"))
            .isEqualTo("https://www.bing.com/search?q=how+to+learn+kotlin")
    }

    @Test fun fallsBackToSearchForNonAsciiInput() {
        // 中文既不是地址，也不该被拼成坏 URL——百分号编码后交给搜索引擎。
        assertThat(UrlResolver.resolve("怎么学 kotlin"))
            .isEqualTo("https://www.bing.com/search?q=%E6%80%8E%E4%B9%88%E5%AD%A6+kotlin")
        assertThat(UrlResolver.resolve("淘宝.com"))
            .isEqualTo("https://www.bing.com/search?q=%E6%B7%98%E5%AE%9D.com")
    }

    @Test fun emptyInputResolvesToEmptyString() {
        assertThat(UrlResolver.resolve("")).isEmpty()
        assertThat(UrlResolver.resolve("   ")).isEmpty()
    }

    @Test fun honoursSelectedEngine() {
        assertThat(UrlResolver.resolve("kotlin", SearchEngine.BAIDU))
            .isEqualTo("https://www.baidu.com/s?wd=kotlin")
        assertThat(UrlResolver.resolve("a.com", SearchEngine.GOOGLE))
            .isEqualTo("https://a.com")   // 地址不受引擎影响
    }

    @Test fun isUrlLikeRejectsAmbiguousInput() {
        assertThat(UrlResolver.isUrlLike("example.com")).isTrue()
        assertThat(UrlResolver.isUrlLike("https://example.com")).isTrue()
        assertThat(UrlResolver.isUrlLike("kotlin")).isFalse()
        assertThat(UrlResolver.isUrlLike("example")).isFalse()      // 没有点的单词
        assertThat(UrlResolver.isUrlLike("a b.com")).isFalse()      // 含空白
        assertThat(UrlResolver.isUrlLike("淘宝.com")).isFalse()      // 非 ASCII
        assertThat(UrlResolver.isUrlLike("")).isFalse()
    }
}

class SearchEngineTest {
    @Test fun encodesQuery() {
        assertThat(SearchEngine.BING.searchUrl("a&b")).isEqualTo("https://www.bing.com/search?q=a%26b")
        assertThat(SearchEngine.BAIDU.searchUrl("a b")).isEqualTo("https://www.baidu.com/s?wd=a+b")
    }

    @Test fun fromNameFallsBackToDefault() {
        assertThat(SearchEngine.fromName("BAIDU")).isEqualTo(SearchEngine.BAIDU)
        assertThat(SearchEngine.fromName("not-an-engine")).isEqualTo(SearchEngine.Default)
        assertThat(SearchEngine.fromName(null)).isEqualTo(SearchEngine.Default)
    }
}

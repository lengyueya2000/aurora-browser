package dev.aurora.browser.domain

import java.net.URLEncoder

/**
 * 搜索引擎：地址栏输入不是地址时，按此构造查询地址。
 *
 * 默认 Bing —— 中国大陆与海外都能直连、不做跳转追踪；可在「设置 → 搜索」里切换。
 */
enum class SearchEngine(val label: String, private val template: String) {
    BING("Bing", "https://www.bing.com/search?q=%s"),
    BAIDU("百度", "https://www.baidu.com/s?wd=%s"),
    GOOGLE("Google", "https://www.google.com/search?q=%s");

    fun searchUrl(query: String): String = template.format(URLEncoder.encode(query, "UTF-8"))

    companion object {
        val Default: SearchEngine = BING

        /** 持久化里存的是枚举名；未知值回落到默认，避免旧配置让应用崩。 */
        fun fromName(name: String?): SearchEngine =
            entries.firstOrNull { it.name == name } ?: Default
    }
}

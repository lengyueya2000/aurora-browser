package dev.aurora.browser.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub Releases 拉取版本列表。
 *
 * ## 为什么不用 /releases/latest
 *
 * 实测 `/releases/latest` 返回 v1.17，而仓库里 v1.18–v1.22 都已发布——因为那个
 * 端点**忽略 pre-release**，项目目前所有发布都是 pre-release。用 `/releases`
 * 取列表，由 [dev.aurora.browser.domain.UpdateChecker] 挑第一个非 draft。
 *
 * ## 为什么不用 OkHttp
 *
 * 项目没有网络库，为一个 GET 加依赖不值当。`HttpURLConnection` 在 Android 上
 * 够用，且这里只需要「拿到字符串，失败返回 null」，不需要连接池/拦截器。
 *
 * 所有失败（超时、非 200、解析异常）一律返回 null，由调用方静默吞掉——
 * 更新检测失败不该打扰用户。
 */
object UpdateRepository {

    private const val ENDPOINT =
        "https://api.github.com/repos/lengyueya2000/aurora-browser/releases"

    private const val TIMEOUT_MS = 10_000

    /** 在 IO 线程执行；返回 null 表示失败。 */
    suspend fun fetchReleases(): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "aurora-browser")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { connection?.disconnect() }
        }
    }
}

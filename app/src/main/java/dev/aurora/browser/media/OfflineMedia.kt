package dev.aurora.browser.media

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import dev.aurora.browser.data.DownloadSpeed
import dev.aurora.browser.data.estimateTotalBytes
import java.io.File
import java.util.concurrent.Executors

/**
 * 流式视频的「离线缓存」。
 *
 * 为什么不交给系统的 `DownloadManager`：对 HLS 站点它只会把 `.m3u8` 这个播放列表
 * （几 KB 文本）存下来，视频一个字节都没有——用户点完「下载」得到一个打不开的文本文件，
 * 比没有按钮更糟。欧乐影院就是这种：真流是一串 `.ts` 分片。
 *
 * Media3 的 `DownloadHelper` 会把播放列表和全部分片都拉下来，放进一个带索引的
 * [SimpleCache]；之后用**同一个 cache** 播放同一个 URL 就等于离线播放。这是官方给出的
 * 流媒体下载方案，不用自己拼分片。
 *
 * 播放器（[PlayerActivity]）和下载服务（[AuroraDownloadService]）必须共用这里的实例：
 * cache 目录和数据库一旦不是同一份，「下载过」和「能离线播」就对不上。
 */
@androidx.annotation.OptIn(UnstableApi::class)
object OfflineMedia {

    /** 缓存目录名。换名字等于让老用户的离线缓存凭空消失，别改。 */
    private const val CACHE_DIR = "offline_media"

    /** 下载并行的分片数。HLS 是一堆小文件，几条并发就够，再多只是抢带宽。 */
    private const val DOWNLOAD_THREADS = 3

    private const val TITLE_PREFS = "aurora_offline_titles"

    /** 只用来让 CDN 别把默认的 ExoPlayer UA 当成爬虫拦掉。 */
    private const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private var cacheInstance: SimpleCache? = null
    private var databaseInstance: StandaloneDatabaseProvider? = null
    private var managerInstance: DownloadManager? = null

    private val executor = Executors.newFixedThreadPool(DOWNLOAD_THREADS)

    /**
     * 速度计。速度问不出来，只能按「已下载字节」的变化自己采样，所以它必须活过单次
     * [items] 调用——每次刷新都新建一个的话，永远只有一次采样，速度永远是 0。
     */
    private val speed = DownloadSpeed()

    /** 离线缓存本体。`SimpleCache` 要求目录独占，进程内只能有一个实例。 */
    @Synchronized
    fun cache(context: Context): SimpleCache =
        cacheInstance ?: SimpleCache(
            File(context.applicationContext.filesDir, CACHE_DIR),
            NoOpCacheEvictor(),
            database(context),
        ).also { cacheInstance = it }

    @Synchronized
    private fun database(context: Context): StandaloneDatabaseProvider =
        databaseInstance ?: StandaloneDatabaseProvider(context.applicationContext)
            .also { databaseInstance = it }

    @Synchronized
    fun downloadManager(context: Context): DownloadManager =
        managerInstance ?: DownloadManager(
            context.applicationContext,
            database(context),
            cache(context),
            httpFactory(referer = null),
            executor,
        ).also { managerInstance = it }

    /**
     * 播放用的数据源：先查缓存，没命中再走网络。
     *
     * 用同一份 cache 才能让「下载完成的流」离线播放；命中时 ExoPlayer 完全不会碰网络。
     *
     * @param referer 页面地址。有的 CDN 会校验 Referer，播放和下载都带上更稳。
     */
    fun playbackDataSource(context: Context, referer: String? = null): DataSource.Factory {
        val upstream = DefaultDataSource.Factory(context, httpFactory(referer))
        return CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * HTTP 数据源。
     *
     * 刻意带一个浏览器 UA：ExoPlayer 默认的 `ExoPlayerLib/x.y.z` 会被不少国内 CDN
     * 直接 403。这不是伪装，是这些 CDN 只认浏览器的 UA。
     */
    private fun httpFactory(referer: String?): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory().apply {
            setUserAgent(BROWSER_USER_AGENT)
            referer?.let { setDefaultRequestProperties(mapOf("Referer" to it)) }
        }

    /**
     * 把一条流交给下载服务。
     *
     * 走 [DownloadService.sendAddDownload] 而不是直接操作 `DownloadManager`：分片下载是
     * 长时间任务，必须有前台服务撑着，否则用户切走应用就被系统连人带任务一起收走。
     */
    fun enqueue(context: Context, url: String, title: String) {
        rememberTitle(context, url, title)
        val request = DownloadRequest.Builder(url, android.net.Uri.parse(url)).build()
        runCatching {
            DownloadService.sendAddDownload(
                context,
                AuroraDownloadService::class.java,
                request,
                /* foreground = */ true,
            )
        }
    }

    /** 当前所有离线下载（含进行中与已完成）。 */
    fun items(context: Context): List<OfflineItem> = runCatching {
        val now = SystemClock.elapsedRealtime()
        // 走 downloadIndex 的游标而不是 getCurrentDownloads()：前者明确是「索引里的全部」，
        // 已完成的那条也在里面，后者只保证是「当前活动的那批」。
        downloadManager(context).downloadIndex.getDownloads().use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val download = cursor.download
                    val url = download.request.uri.toString()
                    add(
                        OfflineItem(
                            url = url,
                            title = titleOf(context, url),
                            state = stateOf(download.state),
                            percent = download.percentDownloaded,
                            bytes = download.bytesDownloaded,
                            contentLength = download.contentLength,
                            // 采样必须每次都记：两次刷新之间隔了多久，速度就按多久算。
                            speed = speed.sample(url, download.bytesDownloaded, now),
                        ),
                    )
                }
            }.also { list -> speed.retain(list.mapTo(mutableSetOf()) { it.url }) }
        }
    }.getOrDefault(emptyList())

    /** 把 media3 的状态常量翻译成我们自己的枚举，界面层就不必依赖 `@UnstableApi`。 */
    private fun stateOf(state: Int): OfflineItem.State = when (state) {
        Download.STATE_COMPLETED -> OfflineItem.State.COMPLETED
        Download.STATE_FAILED -> OfflineItem.State.FAILED
        Download.STATE_DOWNLOADING -> OfflineItem.State.DOWNLOADING
        Download.STATE_QUEUED -> OfflineItem.State.QUEUED
        else -> OfflineItem.State.STOPPED
    }

    fun remove(context: Context, url: String) {
        runCatching {
            DownloadService.sendRemoveDownload(
                context,
                AuroraDownloadService::class.java,
                url,
                /* foreground = */ false,
            )
        }
    }

    /** 页面标题只在入队那一刻知道，`DownloadRequest` 里没地方放，自己存一份。 */
    private fun rememberTitle(context: Context, url: String, title: String) {
        if (title.isBlank()) return
        runCatching {
            context.applicationContext
                .getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(url, title).apply()
        }
    }

    /** 拿不到标题就退回地址末段，总比列表里一片空白强。 */
    fun titleOf(context: Context, url: String): String {
        val saved = runCatching {
            context.applicationContext
                .getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE)
                .getString(url, null)
        }.getOrNull()
        return saved?.takeIf { it.isNotBlank() }
            ?: url.substringBefore('?').substringAfterLast('/').ifBlank { url }
    }
}

/**
 * 一条离线缓存。
 *
 * 刻意不直接暴露 media3 的 `Download`：那是 `@UnstableApi`，界面层不该被它的版本变化
 * 牵着走，也不该为了读一个进度就到处加 opt-in 注解。
 */
data class OfflineItem(
    val url: String,
    val title: String,
    val state: State,
    val percent: Float,
    val bytes: Long,
    /**
     * `Download.contentLength`。**HLS 上恒为 -1**：分片长度要下完才知道，Media3 的
     * `DownloadManager` 构造 `Download` 时就把这个字段写死成 `C.LENGTH_UNSET`。
     */
    val contentLength: Long = -1L,
    /** 字节/秒。0 表示「还不知道」（刚开始下，或者已经停下）。 */
    val speed: Long = 0L,
) {
    enum class State { QUEUED, DOWNLOADING, COMPLETED, FAILED, STOPPED }

    val isCompleted: Boolean get() = state == State.COMPLETED
    val isFailed: Boolean get() = state == State.FAILED
    val isRunning: Boolean
        get() = state == State.QUEUED || state == State.DOWNLOADING || state == State.STOPPED

    /** 0f..1f；未知进度时返回 0。 */
    val progress: Float get() = percent.coerceIn(0f, 100f) / 100f

    /**
     * 总大小。已知就用真值；HLS 上未知，靠「已下载 ÷ 已完成比例」反推。
     * 0 表示「还不知道」——刚开始下，或者比例低到估不准。
     */
    val totalBytes: Long get() = estimateTotalBytes(contentLength, bytes, percent)
}

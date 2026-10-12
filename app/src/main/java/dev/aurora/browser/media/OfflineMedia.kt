package dev.aurora.browser.media

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.MimeTypes
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
import dev.aurora.browser.BrowserIdentity
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

    /**
     * 下载并行的分片数。
     *
     * 这个数**就是**多线程下载本身：Media3 的 `SegmentDownloader` 会把分片一个接一个丢给
     * 我们给它的这个 executor（`download()` 是 final 的，改不了它的循环，只能改池子大小），
     * 池里有几条线程就有几个分片同时在拉。单条连接常常跑不满带宽，几条并发叠起来明显更快。
     *
     * 上限别太大：手机网络下并发太多会互相抢带宽、也更容易被 CDN 判定成异常流量。
     * 另外这几条线程是所有离线缓存共享的——同时下两三部片子时，每部能分到的并发会变少。
     */
    private const val DOWNLOAD_THREADS = 8

    private const val TITLE_PREFS = "aurora_offline_titles"

    /** 标题库的条数上限。超出就丢一批旧的——丢掉的只是显示用的标题，见 [rememberTitle]。 */
    private const val MAX_TITLES = 200

    /**
     * 「用户手动暂停」用的 stop reason。
     *
     * 取一个大于 0 的自定义值：Media3 只关心 reason 是不是 `NONE`，具体数值它不解释
     * （`STOP_REASON_*` 那几个常量是官方给已知原因占的号，自己排一个别撞上就行）。
     */
    private const val STOP_REASON_USER = 1_000

    private var cacheInstance: SimpleCache? = null
    private var databaseInstance: StandaloneDatabaseProvider? = null
    private var managerInstance: DownloadManager? = null

    private val executor = Executors.newFixedThreadPool(DOWNLOAD_THREADS)

    /**
     * 速度计。速度问不出来，只能按「已下载字节」的变化自己采样，所以它必须活过单次
     * [items] 调用——每次刷新都新建一个的话，永远只有一次采样，速度永远是 0。
     *
     * 窗口必须比 Media3 的进度落库间隔长。`DownloadManager` 里的
     * `UPDATE_PROGRESS_INTERVAL_MS = 5000`：**它每 5 秒才把已下载字节写进索引一次**，
     * 我们读到的那个数就只每 5 秒变一次。用默认的 3 秒窗口永远凑不齐两个不同的样本
     * （新样本一进来，旧样本已经出了窗口），速度会一直显示 0——真机上就是这样，
     * 明明在下、下得还挺快，界面上却一个速度都没有。
     *
     * 于是这里取 11 秒窗口：两次落库（5 秒）就能出数，之后是 10 秒左右的滑动平均。
     * 代价是迟钝——速度突变要十几秒才反映出来；换来的是不跳、可读。
     * 直链那条路（系统下载器，[DownloadsRepository]）字节是连续更新的，仍用默认窗口。
     */
    private val speed = DownloadSpeed(windowMs = 11_000L, minSampleMs = 4_000L)

    /**
     * 最近一次入队那条流的页面地址。
     *
     * `DownloadRequest` 里没有放请求头的地方，而 `DownloadManager` 的数据源是建管理器时
     * 一次性给定的，所以只能让数据源在每次开连接时现取。并发下不同站的流可能互相拿到
     * 对方的 Referer——但带了被拒会自动退到不带（[RefererFallbackDataSource]），
     * 最坏是多一次请求，不会把本来能下的下坏。
     */
    @Volatile
    private var pendingReferer: String? = null

    /** 离线缓存本体。`SimpleCache` 要求目录独占，进程内只能有一个实例。 */
    @Synchronized
    fun cache(context: Context): SimpleCache =
        cacheInstance ?: SimpleCache(
            File(context.applicationContext.filesDir, CACHE_DIR),
            NoOpCacheEvictor(),
            database(context),
        ).also { cacheInstance = it }

    /**
     * 提前把离线缓存建起来（**在后台线程调**）。
     *
     * `SimpleCache` 的构造要读索引库、抢目录锁，是实打实的磁盘 I/O；而它第一次被用到是在
     * 播放器的 `onCreate` 里（见 [playbackDataSource]），那条路在主线程上——索引一大，
     * 或者上次被杀进程留下半个索引，启动就会卡一下，甚至 ANR。所以在 Application 启动时
     * 先丢到后台把它建好，播放器真要用的时候只剩一次查表。
     *
     * 建不起来也无所谓：[playbackDataSource] 自己会退成纯网络播放。
     */
    fun warmUp(context: Context) {
        runCatching { cache(context.applicationContext) }
    }

    /**
     * 离线缓存实际占用的字节数。
     *
     * 只统计**缓存里真实落盘的分片**，不是「所有下载的 contentLength 之和」——删除一条
     * 下载到底有没有把字节还回来，只有这个数说了算（`DownloadManager.removeDownload`
     * 会连同缓存里的分片一起删）。界面把它显示出来，用户就能自己看见删完变小了。
     *
     * 播放是只读的（见 [playbackDataSource] 的 `setCacheWriteDataSinkFactory(null)`），
     * 在线看视频不会往这里写，所以这个数就是离线缓存自己的体积。
     */
    fun cacheBytes(context: Context): Long = runCatching { cache(context).cacheSpace }.getOrDefault(0L)

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
            // Referer 现取现用：管理器只建一次，而每条待下载流的页面地址都不一样。
            RefererFallbackDataSource.Factory(httpFactory()) { pendingReferer },
            executor,
        ).also { managerInstance = it }

    /**
     * 播放用的数据源：先查缓存，没命中再走网络。
     *
     * 用同一份 cache 才能让「下载完成的流」离线播放；命中时 ExoPlayer 完全不会碰网络。
     *
     * @param referer 页面地址。有的 CDN 会校验 Referer，带上更稳；被拒时
     *   [RefererFallbackDataSource] 会自动退到不带 Referer 的源再试一次。
     */
    fun playbackDataSource(context: Context, referer: String? = null): DataSource.Factory {
        val upstream = DefaultDataSource.Factory(
            context,
            RefererFallbackDataSource.Factory(httpFactory()) { referer },
        )
        // 缓存建不起来就退成**纯网络播放**，而不是把异常一路冒到播放器初始化里。
        //
        // `SimpleCache` 要独占目录和锁文件，构造时可能抛 `IllegalStateException`：上一次
        // 下到一半被系统杀进程、索引库损坏、存储写满，都会命中。这些都是「有时候才发生」，
        // 正好对上用户报的「有时候播放视频会崩溃」——而且它崩在 onCreate，看起来就是
        // 「一进播放器就闪退」，与视频本身无关，最难排查。缓存只是加分项：没有它照样能在线看。
        val simpleCache = runCatching { cache(context) }.getOrNull() ?: return upstream
        return CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(upstream)
            // **只读**：命中缓存就离线播，没命中就走网络、不往缓存里写。
            //
            // 为什么必须显式写这一句：`CacheDataSource.Factory` 默认是「边播边存」的
            // （`setCacheWriteDataSinkFactory(null)` 才会把 `cacheIsReadOnly` 置真）。
            // 而这份 cache 用的是 [NoOpCacheEvictor]——顺手存下来的分片永远不会被淘汰，
            // 用户在线看多少视频，应用私有目录就占多少存储，只能靠清数据回收。
            // 真要给在线播放留缓存，得另开一份带 LRU 淘汰的 cache，不能和下载共用。
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * HTTP 数据源。
     *
     * 刻意带一个浏览器 UA：ExoPlayer 默认的 `ExoPlayerLib/x.y.z` 会被不少国内 CDN
     * 直接 403。这不是伪装，是这些 CDN 只认浏览器的 UA。
     *
     * Referer 不在这里设：它是**按连接**现取的（见 [RefererFallbackDataSource]），
     * 而工厂是复用一份的。
     */
    private fun httpFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory().apply { setUserAgent(BrowserIdentity.MOBILE_USER_AGENT) }

    /**
     * 把一条流交给下载服务。
     *
     * 走 [DownloadService.sendAddDownload] 而不是直接操作 `DownloadManager`：分片下载是
     * 长时间任务，必须有前台服务撑着，否则用户切走应用就被系统连人带任务一起收走。
     *
     * @param referer 页面地址，有的 CDN 不给 Referer 就不放行（详见 [pendingReferer]）。
     * @return 是否真的交给了下载服务。启动前台服务失败时返回 `false`，让调用方如实提示，
     *   而不是不管成没成都说「已开始」。
     */
    fun enqueue(context: Context, url: String, title: String, referer: String? = null): Boolean {
        rememberTitle(context, url, title)
        if (!referer.isNullOrBlank()) pendingReferer = referer
        val request = DownloadRequest.Builder(url, android.net.Uri.parse(url))
            // 必须显式给 MIME：不给的话 Media3 只看地址推断，而嗅探收进来的播放列表地址
            // 未必带扩展名（`/m3u8/<hash>`、`/play?url=xxx.m3u8`）。那种地址会被当成
            // 直链，Media3 拿 `ProgressiveDownloader` 把播放列表文本当视频存下来——几 KB
            // 的「文件」，离线播不了。给了 MIME 就走 [MediaUrls.isStream] 同一把尺子。
            .setMimeType(
                when {
                    MediaUrls.isHls(url) -> MimeTypes.APPLICATION_M3U8
                    MediaUrls.isPlaylist(url) -> MimeTypes.APPLICATION_MPD
                    // 不是播放列表：交给 Media3 自己按地址推断，别替它猜错。
                    else -> null
                },
            )
            .build()
        return runCatching {
            DownloadService.sendAddDownload(
                context,
                AuroraDownloadService::class.java,
                request,
                /* foreground = */ true,
            )
        }.isSuccess
    }

    /**
     * 当前所有离线下载（含进行中与已完成）。
     *
     * 返回 `null` = **没读出来**：索引库损坏、目录锁被别的进程占着、存储写满，都会走到这里。
     * 以前这个方法直接返回列表、整个实现包在 `runCatching{}.getOrDefault(emptyList())` 里，
     * 于是「读失败」和「真的一条都没有」在界面上长得一模一样——用户下载的东西看起来像
     * 凭空消失了，还没有任何提示。调用方现在必须自己决定怎么处理这个 `null`：
     * 下载管理页给一句提示，播放器那条状态按「不知道」处理（不显示）。
     */
    fun itemsOrNull(context: Context): List<OfflineItem>? = runCatching {
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
    }.getOrNull()

    /** 把 media3 的状态常量翻译成我们自己的枚举，界面层就不必依赖 `@UnstableApi`。 */
    private fun stateOf(state: Int): OfflineItem.State = when (state) {
        Download.STATE_COMPLETED -> OfflineItem.State.COMPLETED
        Download.STATE_FAILED -> OfflineItem.State.FAILED
        Download.STATE_DOWNLOADING -> OfflineItem.State.DOWNLOADING
        Download.STATE_QUEUED -> OfflineItem.State.QUEUED
        else -> OfflineItem.State.STOPPED
    }

    /**
     * 删掉一条离线缓存（连同缓存里的分片）和它记下的标题。
     *
     * @return 是否已经把删除请求交给了下载服务。以前这里没有返回值、异常也被吞掉，用户点
     *   删除后行还在那儿、却什么提示都没有——按不动的按钮比没有按钮更让人困惑。
     */
    fun remove(context: Context, url: String): Boolean {
        forgetTitle(context, url)
        return runCatching {
            DownloadService.sendRemoveDownload(
                context,
                AuroraDownloadService::class.java,
                url,
                /* foreground = */ false,
            )
        }.isSuccess
    }

    /**
     * 暂停单条离线缓存。
     *
     * Media3 1.4.1 的 `DownloadService` **没有**「按某条下载暂停」的接口——只有
     * `sendPauseDownloads`（暂停全部）和 `sendSetStopReason`。所以单条暂停就是给它设一个
     * 自定义的 stop reason：`DownloadManager` 见到非 NONE 的 reason 会把这条停下，
     * 已下好的分片留在缓存里，继续时接着下（不是从头再来）。
     *
     * 与 [remove] 一样走 `DownloadService`：直接改 `DownloadManager` 会在没有前台服务的
     * 时候被系统连任务一起收走。
     *
     * @return 是否把请求交给了下载服务（同 [remove]，失败要让用户知道）。
     */
    fun pause(context: Context, url: String): Boolean = runCatching {
        DownloadService.sendSetStopReason(
            context,
            AuroraDownloadService::class.java,
            url,
            STOP_REASON_USER,
            /* foreground = */ false,
        )
    }.isSuccess

    /**
     * 继续：把 stop reason 清回 NONE，`DownloadManager` 会重新排队这条，从断点接着下。
     *
     * @return 是否把请求交给了下载服务（同 [remove]）。
     */
    fun resume(context: Context, url: String): Boolean = runCatching {
        DownloadService.sendSetStopReason(
            context,
            AuroraDownloadService::class.java,
            url,
            Download.STOP_REASON_NONE,
            /* foreground = */ false,
        )
    }.isSuccess

    /**
     * 页面标题只在入队那一刻知道，`DownloadRequest` 里没地方放，自己存一份。
     *
     * 这张表按完整 URL 存、只增不减（[forgetTitle] 负责在删除时清掉对应的那条）。超出
     * [MAX_TITLES] 就丢掉一批：`SharedPreferences` 整个文件在进程启动时读进内存，只增不减
     * 的话，下过很多次流之后会一直背着一堆没人再看的键。丢掉的只是显示用的标题，
     * [titleOf] 会退回地址末段，不影响任何功能。
     */
    private fun rememberTitle(context: Context, url: String, title: String) {
        if (title.isBlank()) return
        runCatching {
            val prefs = context.applicationContext
                .getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(url, title).apply()
            if (prefs.all.size > MAX_TITLES) {
                val editor = prefs.edit()
                prefs.all.keys.asSequence()
                    .filter { it != url }
                    .take(prefs.all.size - MAX_TITLES)
                    .forEach { editor.remove(it) }
                editor.apply()
            }
        }
    }

    /** 这一条已经不在了，标题也别留着——否则每个下过的流都会永久留下一个键。 */
    private fun forgetTitle(context: Context, url: String) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(TITLE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(url).apply()
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

    /** 正在下（可以被暂停）。 */
    val isActive: Boolean get() = state == State.QUEUED || state == State.DOWNLOADING

    /**
     * 用户按了暂停（可以被继续）。
     *
     * Media3 没有单独的「暂停」状态：`pauseDownload` 之后状态就是 [State.STOPPED]，
     * 与「还没开始」共用一个值。界面上把 STOPPED 一律当「已暂停」展示——因为入队之后
     * 会立刻变 QUEUED，能停在 STOPPED 的只可能是被暂停或曾经失败过。
     */
    val isPaused: Boolean get() = state == State.STOPPED

    /**
     * 能不能现在就看。**没下完也能看**（边下边看）：播放器读的是同一份 `SimpleCache`，
     * 已经落盘的分片直接命中、没下到的分片当场从网络补，所以下到一半点开就能放。
     */
    val isPlayable: Boolean get() = !isFailed

    /** 0f..1f；未知进度时返回 0。 */
    val progress: Float get() = percent.coerceIn(0f, 100f) / 100f

    /**
     * 总大小。已知就用真值；HLS 上未知，靠「已下载 ÷ 已完成比例」反推。
     * 0 表示「还不知道」——刚开始下，或者比例低到估不准。
     */
    val totalBytes: Long get() = estimateTotalBytes(contentLength, bytes, percent)
}

package dev.aurora.browser.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.aurora.browser.ui.browser.DesktopMode
import dev.aurora.browser.ui.cast.CastController
import dev.aurora.browser.ui.components.AuroraBottomSheet
import dev.aurora.browser.ui.components.SettingRow
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraRadius
import dev.aurora.browser.ui.theme.AuroraSpacing
import dev.aurora.browser.ui.theme.AuroraTheme
import kotlinx.coroutines.delay

/**
 * 内置播放器（Media3 / ExoPlayer）：网页里的「全屏」走这里。
 *
 * 为什么不再用 WebView 的全屏：网页视频点全屏时，WebView 会把一个自定义 View 通过
 * `WebChromeClient.onShowCustomView` 交出来，由应用挂到窗口上。这条路在真机上不稳——
 * 站点普遍用 MSE（`<video>` 只是 `blob:` 壳子），把那个 View 搬来搬去，画面就黑了。
 * 用户实测的「全屏之后是黑屏」就是这么来的。
 *
 * 换成原生播放器就没有这一层：地址由 [MediaSniffer] 从网络层拿到真实流（HLS 的
 * `.m3u8` 或直链），交给 ExoPlayer 用 Surface 渲染。顺带还能离线播放（见 [OfflineMedia]）。
 *
 * 控制层没有用 `PlayerView` 自带的那一套，而是自己画：自带控制条没有清晰度切换、
 * 没有倍速、没有画面比例，也不跟应用的深色令牌一致。手势与格式判断在 [PlayerGestures]
 * 与 [PlayerQuality] 里，那两部分是纯计算，能单测。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var prefs: PlaybackPreferences? = null
    private var savedCutoutMode: Int? = null
    private var mediaUrl: String = ""
    private var mediaTitle: String = ""
    private var mediaReferer: String? = null

    /** 建源用的数据源。换源重试时要拿它重新建一次，所以留着。 */
    private var dataSourceFactory: DataSource.Factory? = null

    /** 当前是按 HLS 还是按直链建的源；[retryWithOtherSource] 会把它翻过来。 */
    private var sourceAsHls = false

    /** 换源重试只做一次：换完还失败就是真播不了，再换只会来回踢皮球。 */
    private var sourceRetried = false

    /** 方向只根据视频宽高定一次，之后不再抢用户自己的旋转。 */
    private var orientationDecided = false

    /** 真实视频尺寸：小窗的宽高比要用它。 */
    private var videoWidth = 0
    private var videoHeight = 0

    /** 是否正处在小窗里。小窗里要把控制层与手势全部让开，否则会盖住系统的展开/关闭按钮。 */
    private val inPip = mutableStateOf(false)

    // ── 追剧模式（见 `docs/superpowers/specs/2026-10-11-binge-mode-design.md`）──

    /**
     * 这一集所在的剧。单集视频是 [EpisodePlayback.Single]，此时选集面板打不开、播完停在结尾
     * ——与 1.13 完全一致。
     *
     * 用 Compose 状态而不是普通字段：标题行要跟着切集变，`setContent` 里的读取要能触发重组。
     */
    private val episodePlay = mutableStateOf(EpisodePlayback.Single)

    /** 已看的集号。来自 [PlaybackPreferences]；无痕播放时不读也不写。 */
    private val watchedEpisodes = mutableStateOf(emptySet<Int>())

    /** 选集面板开着没有。面板在控制层之外，所以状态放在这里。 */
    private val episodeSheetOpen = mutableStateOf(false)

    /** 切集进行中要显示的那一集（「第 2 集」）；`null` 表示没在切。 */
    private val switchingLabel = mutableStateOf<String?>(null)

    /** 片尾卡片；`null` 表示不显示。 */
    private val endCard = mutableStateOf<EndCard?>(null)

    /** 切集用的隐藏嗅探窗口。同一时刻只存在一个，用完立刻销毁（一个 WebView 二三十 MB）。 */
    private var sniffer: EpisodeSniffer? = null

    private var endCardTicker: Runnable? = null
    private var incognito = false

    /** 当前是不是「请求桌面版网站」。切集时嗅探窗口要用同一个 UA，否则站点给另一套页面。 */
    private var desktopMode = false

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        mediaUrl = url
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)?.takeIf { it.isNotBlank() }
        mediaTitle = title.ifBlank { url }
        mediaReferer = referer
        incognito = intent.getBooleanExtra(EXTRA_INCOGNITO, false)
        desktopMode = intent.getBooleanExtra(EXTRA_DESKTOP, false)
        val preferences = PlaybackPreferences.create(this)
        prefs = preferences

        // 选集随 Intent 走（页面侧提取的结果，见 BrowserScreen）。坏 JSON 当没有选集：
        // 播放器宁可少一个面板，也不能因为一条坏数据起不来。
        val episodeList = EpisodeCodec.fromJson(intent.getStringExtra(EXTRA_EPISODES))
            ?.let { list ->
                // Intent 里的下标优先——它是「页面上的当前集」。越界就当不在列表里（-1）。
                val index = intent.getIntExtra(EXTRA_EPISODE_INDEX, list.currentIndex)
                list.copy(currentIndex = if (index in list.episodes.indices) index else -1)
            }
        episodePlay.value = EpisodePlayback.of(episodeList, preferences.autoNext())
        watchedEpisodes.value = watchedOf(episodePlay.value, preferences)

        // 先按横屏摆好：影视内容绝大多数是横的，而国内手机默认开着「方向锁定」，
        // 不主动掰就会一直竖着、视频缩在中间一条。拿到真实宽高后再按需要纠正。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        savedCutoutMode = ImmersiveMode.enter(this, savedCutoutMode)
        // 标题栏（任务切换器里那个）跟着选集走，所以放在 episodePlay 设好之后取。
        if (title.isNotBlank()) setTitle(displayTitle())

        val selector = DefaultTrackSelector(this)
        trackSelector = selector

        val exo = ExoPlayer.Builder(this)
            // 硬解初始化失败的机器（vivo / OPPO / 部分联发科）自动回退软解，
            // 而不是直接报错退出。这是「有些视频播不了」最常见的成因。
            .setRenderersFactory(DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setTrackSelector(selector)
            // 默认缓冲在弱网下太浅，一卡就重新缓冲。放宽到 20 秒起播、60 秒上限，
            // 并把「起播前最少缓冲」抬到 2.5 秒——起播慢一点，换卡顿少很多。
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(20_000, 60_000, 2_500, 5_000)
                    .build(),
            )
            .build()
        player = exo

        val dataSourceFactory = OfflineMedia.playbackDataSource(this, referer)
        this.dataSourceFactory = dataSourceFactory
        // HLS 显式建源并打开 chunkless 预解析：起播时不必先把分片清单整个拉下来
        // 才知道有几档码率。其它（直链、DASH）仍交给默认工厂。
        sourceAsHls = MediaUrls.isHls(url)
        exo.setMediaSource(buildMediaSource(url, sourceAsHls, dataSourceFactory))

        // 网页里点全屏时带着当时的进度；没有就接上次没看完的位置。
        val fromPageMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        val startMs = if (fromPageMs > 0L) fromPageMs else preferences.resumeMs(url)
        if (startMs > 0L) exo.seekTo(startMs)
        exo.setPlaybackSpeed(preferences.speed())
        exo.playWhenReady = true

        setContent {
            AuroraTheme {
                PlayerScreen(
                    player = exo,
                    // 标题由选集状态算出来：切集之后要变成「剧名 · 第 2 集」，所以读的是
                    // episodePlay 这个状态，而不是一个算好的字符串。
                    title = episodePlay.value.title(mediaTitle),
                    episodes = episodePlay.value,
                    watchedEpisodes = watchedEpisodes.value,
                    sheetOpen = episodeSheetOpen.value,
                    switchingLabel = switchingLabel.value,
                    endCard = endCard.value,
                    trackSelector = selector,
                    initialSpeed = preferences.speed(),
                    pip = inPip.value,
                    onSpeedChanged = { preferences.rememberSpeed(it) },
                    onEnded = { onEpisodeEnded() },
                    onOpenEpisodes = { episodeSheetOpen.value = true },
                    onCloseEpisodes = { episodeSheetOpen.value = false },
                    onAutoNextChanged = { enabled ->
                        episodePlay.value = episodePlay.value.withAutoNext(enabled)
                        preferences.rememberAutoNext(enabled)
                    },
                    onPickEpisode = { index ->
                        episodeSheetOpen.value = false
                        switchTo(index)
                    },
                    onPlayNextNow = {
                        endCard.value?.nextIndex?.let { next ->
                            cancelEndCard()
                            switchTo(next)
                        }
                    },
                    onCancelEndCard = { cancelEndCard() },
                    onPip = { hint -> enterPip(hint) },
                    onCast = { castVideo() },
                    onDownload = { downloadVideo() },
                    onExit = { finish() },
                    onVideoSize = { size -> applyOrientationFor(size) },
                    onError = { error ->
                        // 先试着自己救一下：容器猜错了换另一种建源方式重来。
                        if (!retryWithOtherSource(error)) {
                            // 救不了就直白告诉用户失败原因。静默停在黑屏上，用户只会以为
                            // 是「又黑了」，也报不出个所以然。
                            Toast.makeText(this, playbackFailureText(error), Toast.LENGTH_LONG).show()
                            finish()
                        }
                    },
                )
            }
        }
        exo.prepare()
    }

    // ── 追剧模式：片尾、切集 ────────────────────────────────────

    /**
     * 一集播完了。
     *
     * 已有的行为（1.13）：把这一集的进度从「继续观看」里删掉——看完的不该留在待续列表里，
     * 否则一季看完会堆一串已经看完的集。顺带记一笔「已看」，供选集面板标状态。
     */
    private fun onEpisodeEnded() {
        prefs?.forget(mediaUrl)
        markWatched()
        val play = episodePlay.value
        val next = play.nextIndex
        if (next == null) {
            // 单集视频：什么都不做，与 1.13 一致。有选集却没有下一集 = 已经是最后一集，
            // 用户此刻正期待「自动播」却没发生，得给个说法（规格 6.3）。
            if (play.hasEpisodes) {
                showEndCard(EndCard(nextIndex = null, secondsLeft = 0, label = "已经是最后一集"), 1_500L)
            }
            return
        }
        // 开关关掉时停在结尾、控制层已经显示出来（见 PlayerScreen 的 onPlaybackStateChanged），
        // 由用户自己决定——这也与 1.13 一致。
        if (!play.autoNext) return
        val episode = play.episode(next)
        showEndCard(
            EndCard(
                nextIndex = next,
                secondsLeft = AutoNextSeconds,
                label = "下一集 · " + (episode?.let { play.episodeLabel(it) } ?: "下一集"),
            ),
            dismissAfterMs = null,
        )
        tickDown(next)
    }

    /**
     * 记下「这一集看完了」。
     *
     * 无痕播放不记：与「无痕不记历史」同一个口径。集号取不到（站点没写数字）时不记——
     * 用一个猜出来的集号标记，后果是标错集，比不标更糟。
     */
    private fun markWatched() {
        if (incognito) return
        val play = episodePlay.value
        val ep = play.episode(play.index) ?: return
        val number = play.number(ep) ?: return
        val key = seriesKey(play) ?: return
        prefs?.markWatched(key, number)
        watchedEpisodes.value = watchedEpisodes.value + number
    }

    /** 一部剧在偏好里的键：`host|剧名`。同一部剧的各集在同一个站上，所以这个键跨集稳定。 */
    private fun seriesKey(play: EpisodePlayback): String? {
        val list = play.list ?: return null
        val any = list.episodes.firstOrNull() ?: return null
        return PlaybackPreferences.seriesKey(list.seriesTitle, any.url)
    }

    private fun watchedOf(play: EpisodePlayback, preferences: PlaybackPreferences): Set<Int> {
        if (incognito) return emptySet()
        val key = seriesKey(play) ?: return emptySet()
        return preferences.watched(key)
    }

    private fun showEndCard(card: EndCard, dismissAfterMs: Long?) {
        cancelEndCard()
        endCard.value = card
        if (dismissAfterMs != null) {
            val runnable = Runnable {
                endCardTicker = null
                endCard.value = null
            }
            endCardTicker = runnable
            main.postDelayed(runnable, dismissAfterMs)
        }
    }

    /** 每秒减一，减到 0 就切集。 */
    private fun tickDown(nextIndex: Int) {
        val runnable = object : Runnable {
            override fun run() {
                val card = endCard.value ?: return
                val left = card.secondsLeft - 1
                if (left > 0) {
                    endCard.value = card.copy(secondsLeft = left)
                    main.postDelayed(this, 1_000L)
                } else {
                    endCardTicker = null
                    endCard.value = null
                    switchTo(nextIndex)
                }
            }
        }
        endCardTicker = runnable
        main.postDelayed(runnable, 1_000L)
    }

    /** 取消倒计时：停在结尾，**不**自动重播（规格 6.3）。 */
    private fun cancelEndCard() {
        endCardTicker?.let { main.removeCallbacks(it) }
        endCardTicker = null
        endCard.value = null
    }

    /**
     * 切到第 [index] 集。
     *
     * **进度不继承**：这里只带新一集的地址进播放链路，`EXTRA_START_MS` 那套不参与。
     * 第 1 集看到 40 分钟播完、切到第 2 集直接从 40 分钟开始，就是这条没守住（规格 6.4）。
     * 新一集的起始位置由它**自己**的续播记录决定：看过一半就接着看，没看过就从头。
     */
    private fun switchTo(index: Int) {
        val play = episodePlay.value
        val episode = play.episode(index) ?: return
        cancelEndCard()
        // 先把当前这一集的位置存下来：手动切走时它不是「播完」，该留在续播列表里。
        // 已经播完的集不会被写进去——PlaybackPreferences.remember 会把结尾 30 秒内的一律丢掉。
        rememberPosition()
        switchingLabel.value = play.episodeLabel(episode)
        sniffer?.cancel()
        val probe = EpisodeSniffer(this)
        sniffer = probe
        probe.sniff(
            pageUrl = episode.url,
            host = findViewById(android.R.id.content),
            // 桌面版站点给的是另一套 DOM，UA 不一致会嗅到对不上的地址。
            userAgent = DesktopMode.userAgentFor(desktopMode, null),
            onReady = { streamUrl -> onEpisodeStreamReady(index, episode.url, streamUrl) },
            onFail = { reason -> onEpisodeStreamFailed(reason) },
        )
    }

    private fun onEpisodeStreamReady(index: Int, pageUrl: String, streamUrl: String) {
        switchingLabel.value = null
        sniffer = null
        val play = episodePlay.value
        if (play.episode(index) == null) return
        // 标题、Referer、进度全部按新一集重来。Referer 用**新一集的页面地址**：站点拿它校验
        // 热链（见 RefererFallbackDataSource），用上一集的地址会被拒。
        mediaUrl = streamUrl
        mediaReferer = pageUrl
        episodePlay.value = play.withIndex(index)
        watchedEpisodes.value = prefs?.let { watchedOf(episodePlay.value, it) } ?: emptySet()
        setTitle(displayTitle())
        applyEpisodeSource(streamUrl, pageUrl)
        // 标签跟着走：退出全屏时用户人在对的页面。不参与播放链路，失败也不影响播放。
        EpisodeSession.tabSync.onEpisodeChanged(pageUrl)
    }

    private fun onEpisodeStreamFailed(reason: String) {
        val label = switchingLabel.value ?: "这一集"
        switchingLabel.value = null
        sniffer = null
        // 留在当前集：不退出播放器、不黑屏，当前这一集照常在放（规格 6.5）。
        // 提示里既说清「怎么办」（回网页手动点一下），也留着底层原因——用户报障时那句
        // 「等这一集的地址等超时了」比「失败了」有用得多。
        Toast.makeText(
            this,
            "没能取到$label（$reason），可以回到网页手动点一下这一集",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** 换一集的地址：重建数据源与媒体源，进度按新地址重新查。 */
    private fun applyEpisodeSource(url: String, referer: String) {
        val exo = player ?: return
        val factory = OfflineMedia.playbackDataSource(this, referer)
        dataSourceFactory = factory
        sourceAsHls = MediaUrls.isHls(url)
        // 换集是全新的一个源，上一次「换源重试」的额度要还回去。
        sourceRetried = false
        // 清晰度选择与倍速不动（那是用户偏好），只有进度回到新一集自己的位置。
        val resumeMs = prefs?.resumeMs(url) ?: 0L
        exo.stop()
        exo.setMediaSource(buildMediaSource(url, sourceAsHls, factory))
        exo.prepare()
        if (resumeMs > 0L) exo.seekTo(resumeMs)
        exo.playWhenReady = true
    }

    /** 竖屏短剧掰成横屏同样是错的，所以等真实宽高出来再定方向，而且只定一次。 */
    private fun applyOrientationFor(size: VideoSize) {
        if (size.width > 0 && size.height > 0) {
            videoWidth = size.width
            videoHeight = size.height
        }
        if (orientationDecided || size.width == 0 || size.height == 0) return
        orientationDecided = true
        requestedOrientation = if (size.width >= size.height) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    /**
     * 按指定方式建一个媒体源。
     *
     * 抽出来是为了「换源重试」能原样再建一次：猜错容器时除了 `asHls` 以外的东西都必须一样。
     */
    private fun buildMediaSource(
        url: String,
        asHls: Boolean,
        factory: DataSource.Factory,
    ): MediaSource {
        val item = MediaItem.fromUri(url)
        return if (asHls) {
            HlsMediaSource.Factory(factory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(item)
        } else {
            DefaultMediaSourceFactory(factory).createMediaSource(item)
        }
    }

    /**
     * 建源方式猜错了就换另一种再试一次，成功返回 true。
     *
     * 嗅探到的地址不总能从扩展名看出容器：`/m3u8/<hash>` 没有后缀、有的 CDN 把格式写在
     * 查询串里、还有站点把 `.m3u8` 直接当直链发。猜错时 ExoPlayer 报的是「容器不支持」，
     * 与其直接告诉用户「播不了」，不如把 HLS 与直链互换重来一次——对用户来说这就是
     * 「同一个地址，第一次黑屏、第二次能放」。只换一次，换完还失败才是真播不了。
     */
    private fun retryWithOtherSource(error: PlaybackException): Boolean {
        val exo = player ?: return false
        val factory = dataSourceFactory ?: return false
        if (sourceRetried || !looksLikeWrongContainer(error)) return false
        sourceRetried = true
        sourceAsHls = !sourceAsHls

        val resumeMs = exo.currentPosition.coerceAtLeast(0L)
        // 换源要先停：setMediaSource 只在空闲态才允许，而失败后的播放器还挂在旧源上。
        exo.stop()
        exo.setMediaSource(buildMediaSource(mediaUrl, sourceAsHls, factory))
        exo.prepare()
        if (resumeMs > 0L) exo.seekTo(resumeMs)
        exo.playWhenReady = true
        return true
    }

    /**
     * 这个错误像不像「容器认错了」。
     *
     * 只认解析类错误：编解码器不支持、没有权限、网络断了这些换一种建源方式也一样失败，
     * 白等一轮还看不到真正的报错。`INVALID_HTTP_CONTENT_TYPE` 也算——服务器返回
     * `text/html` 时，往往是地址根本不是媒体，换一种解析器反而可能对。
     */
    private fun looksLikeWrongContainer(error: PlaybackException): Boolean =
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            -> true

            else -> false
        }

    /**
     * 给用户看的失败原因。
     *
     * 只有 `errorCodeName` 太干（`ERROR_CODE_IO_BAD_HTTP_STATUS` 谁知道是 403 还是 404），
     * 所以把最内层那句真实原因也带上——它就是 HTTP 状态码、解封装失败的具体位置这类
     * 能直接拿去搜的信息。
     */
    private fun playbackFailureText(error: PlaybackException): String {
        val detail = generateSequence(error.cause) { it.cause }
            .mapNotNull { it.message }
            .firstOrNull { it.isNotBlank() }
            ?.take(MAX_ERROR_DETAIL)
        return if (detail == null) {
            "这个视频播不了：${error.errorCodeName}"
        } else {
            "这个视频播不了：${error.errorCodeName}（$detail）"
        }
    }

    /**
     * 小窗播放。
     *
     * 宽高比先按 [PlayerWindow] 夹到系统允许的区间——竖屏短剧的比例会越界，
     * 越界时系统直接抛异常，点了没反应比报错更难查。
     */
    private fun enterPip(sourceRect: android.graphics.Rect?) {
        val (width, height) = PlayerWindow.pipAspectRatio(videoWidth, videoHeight)
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(width, height))
            // 画面当前的位置：系统拿它当缩放动画的起点。不给的话动画会从屏幕中心长出来，
            // Android 12 起这已经是「推荐做法」而不是可选优化。
            .apply { sourceRect?.let { setSourceRectHint(it) } }
            .build()
        val entered = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
        if (!entered) {
            Toast.makeText(this, "这个应用现在不能进小窗", Toast.LENGTH_SHORT).show()
        }
    }

    /** 小窗进出时系统会回调这里；小窗里把控制层让开，交给系统的展开/关闭按钮。 */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
    }

    /**
     * 投屏：把正在播的地址交给能接视频通配 MIME 的应用（各类投屏播放器都注册了它）。
     * 与网页浮层的投屏走同一条路，不引入 Google Cast。
     */
    private fun castVideo() {
        val ok = CastController(this).playOnDevice(mediaUrl, "video/*")
        if (!ok) {
            Toast.makeText(this, "没有能投屏播放该视频的应用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 下载：直链交给系统下载器，HLS 进离线缓存——分岔在 [MediaDownload] 里。 */
    private fun downloadVideo() {
        val result = MediaDownload.enqueue(
            context = this,
            url = mediaUrl,
            title = mediaTitle,
            referer = mediaReferer,
        )
        Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
    }

    /** 退到后台就停：不停的话用户切走了声音还在放。同时把进度存下来。 */
    override fun onStop() {
        super.onStop()
        player?.pause()
        rememberPosition()
    }

    override fun onDestroy() {
        rememberPosition()
        // 切集用的隐藏 WebView 必须显式销毁：一个二三十 MB，跟着 Activity 一起走。
        sniffer?.destroy()
        sniffer = null
        cancelEndCard()
        player?.release()
        player = null
        ImmersiveMode.exit(this, savedCutoutMode)
        super.onDestroy()
    }

    private fun rememberPosition() {
        val exo = player ?: return
        // 标题与来源页一起存：主页的「继续观看」要显示是哪一集，重开时还要把 Referer
        // 交回来（见 RefererFallbackDataSource）。标题带上集号，一季里才分得清哪条是哪集。
        prefs?.remember(
            url = mediaUrl,
            positionMs = exo.currentPosition,
            durationMs = exo.duration,
            title = displayTitle(),
            referer = mediaReferer,
        )
    }

    /** 界面上显示、也写进续播记录的标题。有选集时是「剧名 · 第 N 集」。 */
    private fun displayTitle(): String = episodePlay.value.title(mediaTitle)

    companion object {
        private const val EXTRA_URL = "dev.aurora.browser.player.URL"
        private const val EXTRA_TITLE = "dev.aurora.browser.player.TITLE"
        private const val EXTRA_REFERER = "dev.aurora.browser.player.REFERER"
        private const val EXTRA_START_MS = "dev.aurora.browser.player.START_MS"
        private const val EXTRA_EPISODES = "dev.aurora.browser.player.EPISODES"
        private const val EXTRA_EPISODE_INDEX = "dev.aurora.browser.player.EPISODE_INDEX"
        private const val EXTRA_INCOGNITO = "dev.aurora.browser.player.INCOGNITO"
        private const val EXTRA_DESKTOP = "dev.aurora.browser.player.DESKTOP"

        /**
         * 打开内置播放器。
         *
         * @param url 已经解析好的可播地址（HLS 播放列表或直链），不是页面里的 `blob:`。
         * @param referer 页面地址：有的 CDN 会校验 Referer。
         * @param startPositionMs 从第几毫秒接着播；0 表示用上次记住的位置或从头。
         * @param episodes 剧集 JSON（见 `EpisodeCodec.toJson`）；`null` 表示单集视频，
         *   播放器里就没有选集面板，播完停在结尾——与 1.13 一致。
         * @param episodeIndex 当前集下标；`-1` 表示当前页不在列表里。
         * @param incognito 无痕来源：「已看」标记不落盘。
         * @param desktopMode 当前是否「请求桌面版网站」；切集嗅探要用同一个 UA。
         */
        fun intent(
            context: Context,
            url: String,
            title: String?,
            referer: String? = null,
            startPositionMs: Long = 0L,
            episodes: String? = null,
            episodeIndex: Int = -1,
            incognito: Boolean = false,
            desktopMode: Boolean = false,
        ): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_REFERER, referer)
                .putExtra(EXTRA_START_MS, startPositionMs)
                .putExtra(EXTRA_EPISODES, episodes)
                .putExtra(EXTRA_EPISODE_INDEX, episodeIndex)
                .putExtra(EXTRA_INCOGNITO, incognito)
                .putExtra(EXTRA_DESKTOP, desktopMode)
    }
}

/**
 * 片尾卡片。两种形态合成一个状态：
 *
 * - 有下一集 → 倒计时（大号秒数 + 「立即播放 / 取消」两个按钮）；
 * - 已经是最后一集 → 只有一句话，1.5 秒后自己消失。
 *
 * 没有选集时不弹卡片（与 1.13 一致）。
 */
private data class EndCard(
    /** 下一集下标；`null` 表示已经是最后一集。 */
    val nextIndex: Int?,
    /** 还要等几秒自动播下一集。 */
    val secondsLeft: Int,
    /** 卡片上那行主文案。 */
    val label: String,
)

/**
 * 片尾倒计时的秒数。
 *
 * 3 秒是「来得及看清写了什么」和「不打断连看」之间的折中：再短用户没读完，
 * 再长每一集之间都要干等。
 */
private const val AutoNextSeconds = 3

/** 控制层里正在展开的二级面板。 */
private enum class PlayerPanel { NONE, SPEED, QUALITY, RESIZE }

/** 手势调整时屏幕中央的提示。 */
private data class GestureHint(val title: String, val detail: String = "")

/** 画面比例的三档，标签用国内用户熟悉的说法。 */
@androidx.annotation.OptIn(UnstableApi::class)
private val RESIZE_OPTIONS = listOf(
    "适应" to AspectRatioFrameLayout.RESIZE_MODE_FIT,
    "铺满" to AspectRatioFrameLayout.RESIZE_MODE_FILL,
    "裁剪" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
)

/**
 * 失败提示里带的那句底层原因最多这么长。
 *
 * 有些解封装异常会带上一整段二进制上下文，直接塞进 Toast 会把真正有用的错误码挤没。
 */
private const val MAX_ERROR_DETAIL = 120

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun PlayerScreen(
    player: ExoPlayer,
    title: String,
    episodes: EpisodePlayback,
    watchedEpisodes: Set<Int>,
    sheetOpen: Boolean,
    switchingLabel: String?,
    endCard: EndCard?,
    trackSelector: DefaultTrackSelector,
    initialSpeed: Float,
    pip: Boolean,
    onSpeedChanged: (Float) -> Unit,
    onEnded: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onCloseEpisodes: () -> Unit,
    onAutoNextChanged: (Boolean) -> Unit,
    onPickEpisode: (Int) -> Unit,
    onPlayNextNow: () -> Unit,
    onCancelEndCard: () -> Unit,
    /** 参数是画面在窗口里的位置：进小窗时交给系统当过渡动画的起点。拿不到时为 null。 */
    onPip: (android.graphics.Rect?) -> Unit,
    onCast: () -> Unit,
    onDownload: () -> Unit,
    onExit: () -> Unit,
    onVideoSize: (VideoSize) -> Unit,
    onError: (PlaybackException) -> Unit,
) {
    var playing by remember { mutableStateOf(player.isPlaying) }
    var buffering by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(player.currentPosition) }
    var durationMs by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var controlsVisible by remember { mutableStateOf(true) }
    var panel by remember { mutableStateOf(PlayerPanel.NONE) }
    var speed by remember { mutableFloatStateOf(initialSpeed) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var qualityOptions by remember { mutableStateOf(emptyList<PlayerQuality.Option>()) }
    var videoGroups by remember { mutableStateOf(emptyList<TrackGroup>()) }
    var selectedQuality by remember { mutableStateOf<String?>(null) }
    var hint by remember { mutableStateOf<GestureHint?>(null) }

    /**
     * 画面在窗口里的位置。
     *
     * 进小窗时把它作为 `setSourceRectHint` 交给系统：不给的话，系统只能猜一个起点，
     * 小窗的缩放动画会从屏幕中央长出来，明显比别的应用差。
     *
     * Lint 的 `PictureInPictureIssue` 建议的是「`setAutoEnterEnabled(true)` 加上
     * `setSourceRectHint`」两件事。这里只做后者：前者是「用户一离开应用就自动进小窗」，
     * 那是另一种行为，而用户要的是**按一下「小窗」才进**，所以那条告警是刻意留下的。
     *
     * 用 `android.graphics.Rect` 而不是 Compose 的 `Rect`：这个值要一路传到 Activity 的
     * `PictureInPictureParams` 里，在那边再转换一次没有意义。
     */
    var pipHint by remember { mutableStateOf<android.graphics.Rect?>(null) }

    // 拖动进度时不要被播放器自己的进度回调拽回去，所以拖动期间冻结显示的进度。
    var scrubbing by remember { mutableStateOf(false) }
    var scrubMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    durationMs = player.duration.coerceAtLeast(0L)
                }
                if (playbackState == Player.STATE_ENDED) {
                    controlsVisible = true
                    onEnded()
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                onVideoSize(videoSize)
            }

            override fun onTracksChanged(tracks: Tracks) {
                qualityOptions = PlayerQuality.options(videoTrackInfos(tracks))
                videoGroups = videoGroupsOf(tracks)
                selectedQuality = currentQualityLabel(tracks, qualityOptions)
            }

            override fun onPlayerError(error: PlaybackException) {
                onError(error)
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 进度轮询。200ms 足够让进度条看起来是连续的，又不至于每帧都重组。
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) positionMs = player.currentPosition
            if (durationMs <= 0L) durationMs = player.duration.coerceAtLeast(0L)
            delay(200)
        }
    }
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_000)
            hint = null
        }
    }
    // 控制层自动隐藏：正在播、面板没开、也没在拖进度时才收起来。
    // 选集面板开着时也不收：标题行是面板的入口，收起来会让「点了标题打开面板」这件事
    // 在面板关掉之后没有落点（面板只盖住下半屏，标题行还看得见）。
    LaunchedEffect(controlsVisible, playing, panel, scrubbing, sheetOpen, endCard) {
        if (controlsVisible && playing && panel == PlayerPanel.NONE && !scrubbing &&
            !sheetOpen && endCard == null
        ) {
            delay(3_500)
            controlsVisible = false
        }
    }

    // 片尾倒计时期间按返回 = 取消（规格 6.3）。没有卡片时不接管返回键：
    // 切集途中的返回该退出播放器（嗅探窗口由 onDestroy 销毁）。
    BackHandler(enabled = endCard != null) { onCancelEndCard() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Aurora.colors.bg),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setKeepContentOnPlayerReset(true)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                view.player = player
                view.resizeMode = resizeMode
            },
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInWindow()
                    pipHint = android.graphics.Rect(
                        b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt(),
                    )
                },
        )

        // 手势层铺在画面之上、控制层之下。控制层里没有点击处理的地方不消费事件，
        // 所以控制层显示时手势照样能用。小窗里整层让开，否则会盖住系统的展开/关闭按钮。
        if (!pip) {
            GestureLayer(
                player = player,
                    panelOpen = panel != PlayerPanel.NONE,
                onToggleControls = { controlsVisible = !controlsVisible },
                onClosePanel = { panel = PlayerPanel.NONE },
                onHint = { hint = it },
                onSeekPreview = { target ->
                    scrubbing = true
                    scrubMs = target
                },
                onSeekCommit = { target ->
                    player.seekTo(target)
                    scrubbing = false
                },
            )
        }

        if (buffering && !pip && switchingLabel == null) {
            CircularProgressIndicator(
                color = Aurora.colors.brand,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
            )
        }

        // 切集的等待：转圈 + 一行说清楚在等哪一集。与缓冲转圈同一套视觉，但多一句话——
        // 「点了第 5 集却什么都没发生」和「正在取第 5 集」是两种完全不同的体验（规格 6.5）。
        if (switchingLabel != null && !pip) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = Aurora.colors.brand, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(AuroraSpacing.m))
                Text(
                    text = "正在获取$switchingLabel…",
                    color = Aurora.colors.text,
                    fontSize = 13.sp,
                )
            }
        }

        if (!pip) {
            hint?.let { current -> GestureHintOverlay(current, Modifier.align(Alignment.Center)) }
        }

        // 片尾卡片在控制层之上：它要压住画面，也要能接住自己的两个按钮。
        // 「点卡片以外的任何位置取消」靠一层透明的手势捕获层实现（规格 6.3）——
        // 刻意不做成「点画面进下一集」：误触进下一集比多按一次取消烦人得多。
        if (endCard != null && !pip) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCancelEndCard,
                    ),
            )
            EndCardOverlay(
                card = endCard,
                modifier = Modifier.align(Alignment.Center),
                onPlayNow = onPlayNextNow,
                onCancel = onCancelEndCard,
            )
        }

        // 选集面板盖在最上面：它有遮罩，打开时下面所有东西都不该收到点击。
        if (sheetOpen && !pip) {
            EpisodeSheet(
                play = episodes,
                watched = watchedEpisodes,
                onToggleAutoNext = onAutoNextChanged,
                onPick = onPickEpisode,
                onDismiss = onCloseEpisodes,
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !pip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerControls(
                title = title,
                episodes = episodes,
                onOpenEpisodes = onOpenEpisodes,
                playing = playing,
                positionMs = if (scrubbing) scrubMs else positionMs,
                durationMs = durationMs,
                speed = speed,
                qualityLabel = selectedQuality ?: PlayerQuality.AUTO_LABEL,
                hasQualityChoice = qualityOptions.isNotEmpty(),
                selectedQuality = selectedQuality,
                resizeMode = resizeMode,
                panel = panel,
                qualityOptions = qualityOptions,
                onPanel = { panel = it },
                onTogglePlay = { if (player.isPlaying) player.pause() else player.play() },
                onSeekBy = { delta ->
                    // 时长未知（直播 / 还没 prepare 完）时上界不能当 0，否则每次快进快退
                    // 都会跳到第 0 秒。口径统一在 PlayerGestures.seekTarget 里。
                    player.seekTo(
                        PlayerGestures.seekTarget(player.currentPosition, delta, player.duration),
                    )
                },
                onScrub = { fraction ->
                    scrubbing = true
                    scrubMs = (durationMs * fraction).toLong()
                },
                onScrubEnd = { fraction ->
                    if (durationMs > 0L) player.seekTo((durationMs * fraction).toLong())
                    scrubbing = false
                },
                onSpeed = { value ->
                    speed = value
                    player.setPlaybackSpeed(value)
                    onSpeedChanged(value)
                    panel = PlayerPanel.NONE
                },
                onQuality = { option ->
                    applyQuality(trackSelector, option, videoGroups)
                    selectedQuality = option?.label ?: PlayerQuality.AUTO_LABEL
                    panel = PlayerPanel.NONE
                },
                onResize = { mode ->
                    resizeMode = mode
                    panel = PlayerPanel.NONE
                },
                onPip = { onPip(pipHint) },
                onCast = onCast,
                onDownload = onDownload,
                onExit = onExit,
            )
        }
    }
}

/**
 * 左半屏上下滑调亮度、右半屏调音量、横向拖看进度。
 *
 * 点击与双击交给 [detectTapGestures]（它能正确区分单击与双击，自己写很容易让
 * 「双击快进」顺带把控制层开关两次），这里只处理拖动，且拖动一旦判定出主轴就
 * 消费掉事件，避免和点击手势互相干扰。
 */
@Composable
private fun GestureLayer(
    player: ExoPlayer,
    panelOpen: Boolean,
    onToggleControls: () -> Unit,
    onClosePanel: () -> Unit,
    onHint: (GestureHint?) -> Unit,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val activity = context as? Activity

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(player, panelOpen) {
                if (panelOpen) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val startX = down.position.x
                    val startY = down.position.y
                    val width = size.width
                    val height = size.height
                    val side = PlayerGestures.sideOf(startX, width)
                    val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val startBrightness =
                        activity?.currentBrightness() ?: PlayerGestures.DEFAULT_BRIGHTNESS
                    val startPosition = player.currentPosition
                    val duration = player.duration.coerceAtLeast(0L)
                    var axis = PlayerGestures.Axis.UNDECIDED
                    var dragDx = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // 抬手不能消费：点击/双击由下面那个 detectTapGestures 处理，
                        // 它一旦看到事件被消费就当成手势取消，单击与双击都会失灵。
                        if (!change.pressed) break
                        val dx = change.position.x - startX
                        val dy = change.position.y - startY
                        if (axis == PlayerGestures.Axis.UNDECIDED) {
                            axis = PlayerGestures.axisOf(dx, dy)
                            if (axis == PlayerGestures.Axis.UNDECIDED) continue
                        }
                        dragDx = dx
                        change.consume()
                        when (axis) {                            PlayerGestures.Axis.HORIZONTAL -> {
                                val preview =
                                    PlayerGestures.seekPreview(startPosition, dx, width, duration)
                                onSeekPreview(preview.targetMs)
                                onHint(
                                    GestureHint(
                                        PlayerGestures.formatTime(preview.targetMs),
                                        PlayerGestures.deltaLabel(preview.deltaMs),
                                    ),
                                )
                            }

                            PlayerGestures.Axis.VERTICAL -> if (side == PlayerGestures.Side.LEFT) {
                                val value = PlayerGestures.brightness(startBrightness, dy, height)
                                activity?.setBrightness(value)
                                onHint(GestureHint("亮度 ${(value * 100).toInt()}%"))
                            } else {
                                val value = PlayerGestures.volume(startVolume, dy, height, maxVolume)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                                val percent = if (maxVolume > 0) value * 100 / maxVolume else 0
                                onHint(GestureHint("音量 $percent%"))
                            }

                            PlayerGestures.Axis.UNDECIDED -> Unit
                        }
                    }

                    // 抬手才真正跳转，拖动过程中只预览，免得一路拖一路 seek。
                    if (axis == PlayerGestures.Axis.HORIZONTAL) {
                        onSeekCommit(
                            PlayerGestures.seekPreview(startPosition, dragDx, width, duration).targetMs,
                        )
                    }
                }
            }
            .pointerInput(player, panelOpen) {
                detectTapGestures(
                    onTap = { if (panelOpen) onClosePanel() else onToggleControls() },
                    onDoubleTap = { offset ->
                        val target = PlayerGestures.doubleTapSeekMs(
                            player.currentPosition,
                            offset.x,
                            size.width,
                            player.duration.coerceAtLeast(0L),
                        )
                        player.seekTo(target)
                        onHint(GestureHint(PlayerGestures.formatTime(target)))
                    },
                )
            },
    )
}

/**
 * 控制层。位置按真机使用习惯排：
 * 右上角放「小窗 / 投屏」（都是把画面交出去的动作），右侧中间放「下载」，
 * 右下角一排是「倍速 / 画质 / 适应 / 退出全屏」，中间留播放/暂停与 ±10 秒，底部是进度。
 */
@Composable
private fun PlayerControls(
    title: String,
    episodes: EpisodePlayback,
    onOpenEpisodes: () -> Unit,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    speed: Float,
    qualityLabel: String,
    hasQualityChoice: Boolean,
    selectedQuality: String?,
    resizeMode: Int,
    panel: PlayerPanel,
    qualityOptions: List<PlayerQuality.Option>,
    onPanel: (PlayerPanel) -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onScrub: (Float) -> Unit,
    onScrubEnd: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onQuality: (PlayerQuality.Option?) -> Unit,
    onResize: (Int) -> Unit,
    onPip: () -> Unit,
    onCast: () -> Unit,
    onDownload: () -> Unit,
    onExit: () -> Unit,
) {
    val colors = Aurora.colors

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim),
    ) {
        // 标题行是选集面板唯一的入口。有选集时可点、末尾带 caret-down；没有选集时它就是
        // 一段纯文本——按钮缺席比按钮点了没反应好（规格 6.1）。命中区抬到 48dp，
        // 因为 15sp 的文字本身只有十几 dp 高。
        val hasEpisodes = episodes.hasEpisodes
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = AuroraSpacing.l)
                .fillMaxWidth(0.6f)
                .heightIn(min = AuroraSpacing.touchTarget)
                .then(
                    if (hasEpisodes) {
                        Modifier
                            .semantics {
                                contentDescription = episodes.currentLabel()
                                    ?.let { "选集，$it，共 ${episodes.count} 集" }
                                    ?: "选集，共 ${episodes.count} 集"
                            }
                            .clickable(
                                role = Role.Button,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onOpenEpisodes,
                            )
                    } else {
                        Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hasEpisodes) {
                Spacer(Modifier.width(AuroraSpacing.xs))
                CaretDownGlyph(tint = colors.textSecondary, side = 14.dp)
            }
        }

        // 右上角：把画面交出去的两个动作。
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = AuroraSpacing.m, vertical = AuroraSpacing.s),
            horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlChip("小窗", active = false, onClick = onPip)
            ControlChip("投屏", active = false, onClick = onCast)
        }

        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextTransportButton("−10s", "后退 10 秒", colors.text) {
                onSeekBy(-PlayerGestures.DOUBLE_TAP_SEEK_MS)
            }
            Spacer(Modifier.width(AuroraSpacing.l))
            Box(
                Modifier
                    .size(64.dp)
                    .clickable(onClick = onTogglePlay),
                contentAlignment = Alignment.Center,
            ) {
                PlayPauseGlyph(playing, colors.text, 34.dp)
            }
            Spacer(Modifier.width(AuroraSpacing.l))
            TextTransportButton("+10s", "前进 10 秒", colors.text) {
                onSeekBy(PlayerGestures.DOUBLE_TAP_SEEK_MS)
            }
        }

        // 右侧中间：下载。单独占一列，不与中间的播放控制抢位置。
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .padding(end = AuroraSpacing.m),
        ) {
            ControlChip("下载", active = false, onClick = onDownload)
        }

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(),
        ) {
            if (panel != PlayerPanel.NONE) {
                PlayerPanelContent(
                    panel = panel,
                    speed = speed,
                    selectedQuality = selectedQuality,
                    resizeMode = resizeMode,
                    qualityOptions = qualityOptions,
                    onSpeed = onSpeed,
                    onQuality = onQuality,
                    onResize = onResize,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AuroraSpacing.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    PlayerGestures.formatTime(positionMs),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
                // 拖动中的比例由这里自己记：Slider 的 onValueChangeFinished 不给最终值，
                // 而回调闭包里读不到同一帧刚更新的外部状态。
                var dragging by remember { mutableStateOf(false) }
                var fraction by remember { mutableFloatStateOf(0f) }
                val shown = when {
                    dragging -> fraction
                    durationMs > 0L -> (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    else -> 0f
                }
                Slider(
                    value = shown,
                    onValueChange = { value ->
                        dragging = true
                        fraction = value
                        onScrub(value)
                    },
                    onValueChangeFinished = {
                        onScrubEnd(fraction)
                        dragging = false
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = colors.brand,
                        activeTrackColor = colors.brand,
                        inactiveTrackColor = colors.border,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = AuroraSpacing.s),
                )
                Text(
                    PlayerGestures.formatTime(durationMs),
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                )
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AuroraSpacing.m, vertical = AuroraSpacing.s),
                horizontalArrangement = Arrangement.spacedBy(
                    AuroraSpacing.s,
                    Alignment.End,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlChip(PlayerGestures.speedLabel(speed), panel == PlayerPanel.SPEED) {
                    onPanel(if (panel == PlayerPanel.SPEED) PlayerPanel.NONE else PlayerPanel.SPEED)
                }
                if (hasQualityChoice) {
                    ControlChip(qualityLabel, panel == PlayerPanel.QUALITY) {
                        onPanel(
                            if (panel == PlayerPanel.QUALITY) PlayerPanel.NONE else PlayerPanel.QUALITY,
                        )
                    }
                }
                ControlChip(resizeLabel(resizeMode), panel == PlayerPanel.RESIZE) {
                    onPanel(if (panel == PlayerPanel.RESIZE) PlayerPanel.NONE else PlayerPanel.RESIZE)
                }
                ControlChip("退出全屏", active = false, onClick = onExit)
            }
        }
    }
}

/** ±10 秒。用文字而不是图标：图标集里没有「10 秒」这种形状，自造一个不如直接写清楚。 */
@Composable
private fun TextTransportButton(
    label: String,
    description: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(AuroraSpacing.touchTarget)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = tint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

/** 播放/暂停：三角形与两条竖条，自己画，省掉一个图标依赖。 */
@Composable
private fun PlayPauseGlyph(playing: Boolean, tint: Color, side: Dp) {
    Canvas(Modifier.size(side)) {
        if (playing) {
            val barWidth = size.width * 0.22f
            val gap = size.width * 0.18f
            val radius = CornerRadius(barWidth * 0.3f)
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.16f, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = radius,
            )
            drawRoundRect(
                color = tint,
                topLeft = Offset(size.width * 0.16f + barWidth + gap, 0f),
                size = Size(barWidth, size.height),
                cornerRadius = radius,
            )
        } else {
            val path = Path().apply {
                moveTo(size.width * 0.2f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(size.width * 0.2f, size.height)
                close()
            }
            drawPath(path, tint)
        }
    }
}

@Composable
private fun ControlChip(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = Aurora.colors
    Text(
        text = label,
        color = if (active) colors.brand else colors.text,
        fontSize = 13.sp,
        modifier = Modifier
            .background(
                color = if (active) colors.surfaceHover else colors.surface.copy(alpha = 0.72f),
                shape = RoundedCornerShape(AuroraRadius.full),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AuroraSpacing.m, vertical = AuroraSpacing.xs + 2.dp),
    )
}

/** 标题行末尾的展开箭头。自己画：图标集里没有 caret 这一类形状，为它加一个 drawable 不值。 */
@Composable
private fun CaretDownGlyph(tint: Color, side: Dp) {
    Canvas(Modifier.size(side)) {
        val path = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.34f)
            lineTo(size.width * 0.5f, size.height * 0.68f)
            lineTo(size.width * 0.82f, size.height * 0.34f)
        }
        drawPath(path, tint, style = Stroke(width = size.width * 0.14f))
    }
}

/** 「已看」的对勾。与颜色一起用——不以颜色为唯一指示（规格 8）。 */
@Composable
private fun CheckGlyph(tint: Color, side: Dp) {
    Canvas(Modifier.size(side)) {
        val path = Path().apply {
            moveTo(size.width * 0.2f, size.height * 0.55f)
            lineTo(size.width * 0.42f, size.height * 0.76f)
            lineTo(size.width * 0.8f, size.height * 0.26f)
        }
        drawPath(path, tint, style = Stroke(width = size.width * 0.16f))
    }
}

/**
 * 开关行的尾巴。整行才是开关（`SettingRow` 的 `toggleable`），这里只做展示，
 * 颜色与设置页 / 隐私页保持一致——同一份 `SwitchDefaults.colors`。
 */
@Composable
private fun SwitchTrailing(checked: Boolean) {
    val c = Aurora.colors
    Switch(
        checked = checked,
        onCheckedChange = null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = c.onBrand,
            checkedTrackColor = c.brand,
            uncheckedThumbColor = c.textMuted,
            uncheckedTrackColor = c.surfaceHover,
            uncheckedBorderColor = c.border,
        ),
    )
}

/**
 * 选集面板（规格 6.2）。
 *
 * 三块：剧名 + `共 N 集`、自动下一集的开关行、5 列剧集网格。复用浏览器的
 * [AuroraBottomSheet]：遮罩关闭、面板吃点击、上沿圆角这些行为都已经定好了，不必再来一遍。
 */
@Composable
private fun EpisodeSheet(
    play: EpisodePlayback,
    watched: Set<Int>,
    onToggleAutoNext: (Boolean) -> Unit,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = Aurora.colors
    val list = play.list ?: return
    val current = play.currentIndex()
    AuroraBottomSheet(onDismiss = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = AuroraSpacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = list.seriesTitle,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(AuroraSpacing.m))
            Text(
                text = "共 ${play.count} 集",
                color = colors.textMuted,
                fontSize = 12.sp,
            )
        }
        SettingRow(
            title = "自动播放下一集",
            toggleState = play.autoNext,
            onClick = { onToggleAutoNext(!play.autoNext) },
            trailing = { SwitchTrailing(play.autoNext) },
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = EpisodeGridMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(top = AuroraSpacing.m),
        ) {
            // 5 列：412dp 宽度下 5×64dp 加间距正好铺满（规格 6.2）。用 chunked + Row 而不是
            // FlowRow：后者还是实验 API，而这里的列数是固定的。
            list.episodes.chunked(EpisodeColumns).forEachIndexed { rowIndex, row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AuroraSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
                ) {
                    row.forEachIndexed { columnIndex, episode ->
                        val index = rowIndex * EpisodeColumns + columnIndex
                        val number = play.number(episode)
                        EpisodeCell(
                            label = play.shortLabel(episode),
                            current = index == current,
                            watched = number != null && number in watched,
                            modifier = Modifier.weight(1f),
                            onClick = { onPick(index) },
                        )
                    }
                    // 最后一行不满时用空位撑住，格子才不会被拉宽——5 列宽度一致比「把最后一格
                    // 拉长」整齐得多。
                    repeat(EpisodeColumns - row.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(AuroraSpacing.s))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EpisodeLegend(current = true, label = "正在看")
            EpisodeLegend(current = false, label = "未看")
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckGlyph(tint = colors.textMuted, side = 11.dp)
                Spacer(Modifier.width(5.dp))
                Text(text = "已看", color = colors.textDisabled, fontSize = 11.sp)
            }
        }
    }
}

/** 图例里的一格：正在看画品牌色描边，未看画底色。 */@Composable
private fun EpisodeLegend(current: Boolean, label: String) {
    val colors = Aurora.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(11.dp)
                .then(
                    if (current) {
                        Modifier.border(1.5.dp, colors.brand, RoundedCornerShape(2.dp))
                    } else {
                        Modifier.background(colors.surfaceHover, RoundedCornerShape(2.dp))
                    }
                )
        )
        Spacer(Modifier.width(5.dp))
        Text(text = label, color = colors.textDisabled, fontSize = 11.sp)
    }
}

/**
 * 剧集网格里的一格。
 *
 * 三态：正在看（品牌色描边 + 品牌色文字）、已看（文字弱化 + 对勾）、未看（底色 + 常规文字）。
 * 无障碍：命中区 ≥ 48dp；状态如实暴露——正在看用 `selected`，已看 / 未看用
 * `stateDescription`。**不以颜色为唯一指示**：「已看」同时有对勾（规格 8）。
 */
@Composable
private fun EpisodeCell(
    label: String,
    current: Boolean,
    watched: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = Aurora.colors
    val shape = RoundedCornerShape(AuroraRadius.xs)
    val state = when {
        current -> "正在看"
        watched -> "已看过"
        else -> "未看过"
    }
    Box(
        modifier = modifier
            .height(EpisodeCellHeight)
            .then(
                if (current) Modifier.border(1.5.dp, colors.brand, shape)
                else Modifier.background(colors.surfaceHover, shape)
            )
            .semantics {
                // 标签本身就是数字时读成「第 12 集」；不是数字（「预告」）时原样读，
                // 「第 预告 集」这种话读出来是坏的。
                contentDescription = if (label.toIntOrNull() != null) "第 $label 集" else label
                stateDescription = state
            }
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                current -> colors.brand
                watched -> colors.textMuted
                else -> colors.text
            },
            fontSize = 14.sp,
            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (watched && !current) {
            Box(Modifier.align(Alignment.TopEnd).padding(3.dp)) {
                CheckGlyph(tint = colors.textMuted, side = 10.dp)
            }
        }
    }
}

/**
 * 片尾卡片（规格 6.3）。
 *
 * 有下一集时是「大号秒数 + 下一集 · 第 N 集 + 3 秒后自动播放 + 立即播放 / 取消」；
 * 已经是最后一集时只有一句说明、没有按钮（那 1.5 秒是自动消失的，给按钮没有意义）。
 *
 * 倒计时用 `liveRegion = Polite` 播报：它是「过一会儿自己会发生的事」，该读给用户听，
 * 但不该抢焦点、打断正在读的内容。
 */
@Composable
private fun EndCardOverlay(
    card: EndCard,
    modifier: Modifier = Modifier,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = Aurora.colors
    val live = Modifier.semantics {
        liveRegion = LiveRegionMode.Polite
        contentDescription = if (card.nextIndex == null) {
            card.label
        } else {
            "${card.label}，${card.secondsLeft} 秒后自动播放"
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth(0.62f)
            .background(colors.bgElevated.copy(alpha = 0.96f), RoundedCornerShape(AuroraRadius.l))
            .border(1.dp, colors.divider, RoundedCornerShape(AuroraRadius.l))
            .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (card.nextIndex != null) {
            Text(
                text = card.secondsLeft.toString(),
                color = colors.brand,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = live,
            )
            Spacer(Modifier.height(AuroraSpacing.s))
        }
        Text(
            text = card.label,
            color = colors.text,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = if (card.nextIndex == null) live else Modifier,
        )
        if (card.nextIndex != null) {
            Spacer(Modifier.height(AuroraSpacing.xs))
            Text(
                text = "${card.secondsLeft} 秒后自动播放",
                color = colors.textMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(AuroraSpacing.l))
            Row(horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m)) {
                EndCardButton("立即播放", primary = true, onClick = onPlayNow)
                EndCardButton("取消", primary = false, onClick = onCancel)
            }
        }
    }
}

@Composable
private fun EndCardButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = Aurora.colors
    Text(
        text = label,
        color = if (primary) colors.onBrand else colors.text,
        fontSize = 13.sp,
        modifier = Modifier
            .background(
                color = if (primary) colors.brand else colors.surfaceHover,
                shape = RoundedCornerShape(AuroraRadius.full),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s),
    )
}

/** 选集网格的列数。5 列是 412dp 宽度下的取值（规格 6.2）。 */
private const val EpisodeColumns = 5

/** 网格里一格的高度。 */
private val EpisodeCellHeight = 44.dp

/**
 * 网格区最高多少。
 *
 * 一季 100 集的话 20 行能把整屏铺满、把「自动播放下一集」那行挤出视野，所以网格自己滚。
 * 460dp 与浏览器里的 `SheetContentMaxHeight` 取同一个值——两个抽屉看起来才是一套东西。
 */
private val EpisodeGridMaxHeight = 460.dp

/** 三个二级面板：倍速、清晰度、画面比例。 */
@Composable
private fun PlayerPanelContent(
    panel: PlayerPanel,
    speed: Float,
    selectedQuality: String?,
    resizeMode: Int,
    qualityOptions: List<PlayerQuality.Option>,
    onSpeed: (Float) -> Unit,
    onQuality: (PlayerQuality.Option?) -> Unit,
    onResize: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AuroraSpacing.m, vertical = AuroraSpacing.s)
            .background(Aurora.colors.glass, RoundedCornerShape(AuroraRadius.m))
            .padding(AuroraSpacing.xs),
    ) {
        when (panel) {
            PlayerPanel.SPEED -> PlayerGestures.SPEEDS.forEach { value ->
                PanelRow(
                    label = PlayerGestures.speedLabel(value),
                    selected = value == speed,
                    onClick = { onSpeed(value) },
                )
            }

            PlayerPanel.QUALITY -> {
                PanelRow(
                    label = PlayerQuality.AUTO_LABEL,
                    selected = selectedQuality == PlayerQuality.AUTO_LABEL,
                    onClick = { onQuality(null) },
                )
                qualityOptions.forEach { option ->
                    PanelRow(
                        label = option.label,
                        selected = option.label == selectedQuality,
                        onClick = { onQuality(option) },
                    )
                }
            }

            PlayerPanel.RESIZE -> RESIZE_OPTIONS.forEach { (label, mode) ->
                PanelRow(label, mode == resizeMode) { onResize(mode) }
            }

            PlayerPanel.NONE -> Unit
        }
    }
}

@Composable
private fun PanelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Aurora.colors
    Text(
        text = label,
        color = if (selected) colors.brand else colors.text,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AuroraSpacing.m, vertical = AuroraSpacing.s + 2.dp),
    )
}

@Composable
private fun GestureHintOverlay(hint: GestureHint, modifier: Modifier = Modifier) {
    val colors = Aurora.colors
    Column(
        modifier
            .background(colors.glass, RoundedCornerShape(AuroraRadius.s))
            .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            hint.title,
            color = colors.text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        if (hint.detail.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(hint.detail, color = colors.textMuted, fontSize = 12.sp)
        }
    }
}

private fun resizeLabel(mode: Int): String =
    RESIZE_OPTIONS.firstOrNull { it.second == mode }?.first ?: "适应"

/** 视频组本身（用于回写清晰度覆盖），顺序与 [videoTrackInfos] 的 `groupIndex` 一致。 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun videoGroupsOf(tracks: Tracks): List<TrackGroup> =
    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.map { it.mediaTrackGroup }

/** 从当前轨道里挑出视频档位，交给 [PlayerQuality] 整理。 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun videoTrackInfos(tracks: Tracks): List<PlayerQuality.VideoTrackInfo> {
    var groupIndex = -1
    return tracks.groups.flatMap { group ->
        if (group.type != C.TRACK_TYPE_VIDEO) return@flatMap emptyList()
        groupIndex++
        val index = groupIndex
        (0 until group.length).map { trackIndex ->
            val format = group.getTrackFormat(trackIndex)
            PlayerQuality.VideoTrackInfo(
                groupIndex = index,
                trackIndex = trackIndex,
                height = format.height,
                bitrate = effectiveBitrate(format),
            )
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun effectiveBitrate(format: Format): Int =
    listOf(format.averageBitrate, format.bitrate, format.peakBitrate).firstOrNull { it > 0 } ?: 0

/** 当前正在播的是哪一档，用来在面板上标出「现在是多少」。 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun currentQualityLabel(
    tracks: Tracks,
    options: List<PlayerQuality.Option>,
): String? {
    var groupIndex = -1
    tracks.groups.forEach { group ->
        if (group.type != C.TRACK_TYPE_VIDEO) return@forEach
        groupIndex++
        for (trackIndex in 0 until group.length) {
            if (group.isTrackSelected(trackIndex)) {
                return PlayerQuality.labelOf(options, groupIndex, trackIndex)
                    ?: PlayerQuality.AUTO_LABEL
            }
        }
    }
    return PlayerQuality.AUTO_LABEL
}

/** 应用清晰度选择：给视频轨设一条覆盖，或清掉覆盖交回自动。 */
@androidx.annotation.OptIn(UnstableApi::class)
private fun applyQuality(
    trackSelector: DefaultTrackSelector,
    option: PlayerQuality.Option?,
    videoGroups: List<TrackGroup>,
) {
    val builder = trackSelector.buildUponParameters()
    if (option == null) {
        builder.clearOverridesOfType(C.TRACK_TYPE_VIDEO)
    } else {
        val group = videoGroups.getOrNull(option.groupIndex) ?: return
        builder.setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
    }
    trackSelector.setParameters(builder)
}

private fun Activity.currentBrightness(): Float {
    val value = window.attributes.screenBrightness
    return if (value < 0f) PlayerGestures.DEFAULT_BRIGHTNESS else value
}

private fun Activity.setBrightness(value: Float) {
    window.attributes = window.attributes.apply {
        screenBrightness = value.coerceIn(0f, 1f)
    }
}

package dev.aurora.browser.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Bundle
import android.util.Rational
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import dev.aurora.browser.ui.cast.CastController
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
        val preferences = PlaybackPreferences.create(this)
        prefs = preferences

        // 先按横屏摆好：影视内容绝大多数是横的，而国内手机默认开着「方向锁定」，
        // 不主动掰就会一直竖着、视频缩在中间一条。拿到真实宽高后再按需要纠正。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        savedCutoutMode = ImmersiveMode.enter(this, savedCutoutMode)
        if (title.isNotBlank()) setTitle(title)

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
                    title = mediaTitle,
                    trackSelector = selector,
                    initialSpeed = preferences.speed(),
                    pip = inPip.value,
                    onSpeedChanged = { preferences.rememberSpeed(it) },
                    onEnded = { preferences.forget(url) },
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
        player?.release()
        player = null
        ImmersiveMode.exit(this, savedCutoutMode)
        super.onDestroy()
    }

    private fun rememberPosition() {
        val exo = player ?: return
        prefs?.remember(mediaUrl, exo.currentPosition, exo.duration)
    }

    companion object {
        private const val EXTRA_URL = "dev.aurora.browser.player.URL"
        private const val EXTRA_TITLE = "dev.aurora.browser.player.TITLE"
        private const val EXTRA_REFERER = "dev.aurora.browser.player.REFERER"
        private const val EXTRA_START_MS = "dev.aurora.browser.player.START_MS"

        /**
         * 打开内置播放器。
         *
         * @param url 已经解析好的可播地址（HLS 播放列表或直链），不是页面里的 `blob:`。
         * @param referer 页面地址：有的 CDN 会校验 Referer。
         * @param startPositionMs 从第几毫秒接着播；0 表示用上次记住的位置或从头。
         */
        fun intent(
            context: Context,
            url: String,
            title: String?,
            referer: String? = null,
            startPositionMs: Long = 0L,
        ): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_REFERER, referer)
                .putExtra(EXTRA_START_MS, startPositionMs)
    }
}

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
    trackSelector: DefaultTrackSelector,
    initialSpeed: Float,
    pip: Boolean,
    onSpeedChanged: (Float) -> Unit,
    onEnded: () -> Unit,
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
    LaunchedEffect(controlsVisible, playing, panel, scrubbing) {
        if (controlsVisible && playing && panel == PlayerPanel.NONE && !scrubbing) {
            delay(3_500)
            controlsVisible = false
        }
    }

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

        if (buffering && !pip) {
            CircularProgressIndicator(
                color = Aurora.colors.brand,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp),
            )
        }

        if (!pip) {
            hint?.let { current -> GestureHintOverlay(current, Modifier.align(Alignment.Center)) }
        }

        AnimatedVisibility(
            visible = controlsVisible && !pip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlayerControls(
                title = title,
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
                    val upper = player.duration.coerceAtLeast(0L)
                    player.seekTo((player.currentPosition + delta).coerceIn(0L, upper))
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
        Text(
            text = title,
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.m)
                .fillMaxWidth(0.6f),
        )

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

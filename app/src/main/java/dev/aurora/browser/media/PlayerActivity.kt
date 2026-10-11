package dev.aurora.browser.media

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * 内置播放器（Media3 / ExoPlayer）：网页里的「全屏」走这里。
 *
 * 为什么不再用 WebView 的全屏：网页视频点全屏时，WebView 会把一个自定义 View 通过
 * `WebChromeClient.onShowCustomView` 交出来，由应用挂到窗口上。这条路在真机上不稳——
 * 站点普遍用 MSE（`<video>` 只是 `blob:` 壳子），把那个 View 搬来搬去，画面就黑了。
 * 用户实测的「全屏之后是黑屏」就是这么来的。
 *
 * 换成原生播放器就没有这一层：地址由 [MediaSniffer] 从网络层拿到真实流（HLS 的
 * `.m3u8` 或直链），交给 ExoPlayer 用 Surface 渲染，全屏、横屏、沉浸式都是我们自己
 * 说了算。顺带还能离线播放（见 [OfflineMedia]）。
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    private var player: ExoPlayer? = null
    private var savedCutoutMode: Int? = null

    /** 方向只根据视频宽高定一次，之后不再抢用户自己的旋转。 */
    private var orientationDecided = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REFERER)?.takeIf { it.isNotBlank() }

        // 先按横屏摆好：影视内容绝大多数是横的，而国内手机默认开着「方向锁定」，
        // 不主动掰就会一直竖着、视频缩在中间一条。拿到真实宽高后再按需要纠正。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        savedCutoutMode = ImmersiveMode.enter(this, savedCutoutMode)

        val view = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            useController = true
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(view)
        })

        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(OfflineMedia.playbackDataSource(this, referer)),
            )
            .build()
        player = exo
        view.player = exo

        exo.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // 竖屏短剧掰成横屏同样是错的，所以等真实宽高出来再定方向。
                if (orientationDecided || videoSize.width == 0 || videoSize.height == 0) return
                orientationDecided = true
                requestedOrientation = if (videoSize.width >= videoSize.height) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 直白告诉用户失败原因。静默停在黑屏上，用户只会以为是「又黑了」。
                Toast.makeText(
                    this@PlayerActivity,
                    "这个视频播不了：${error.errorCodeName}",
                    Toast.LENGTH_LONG,
                ).show()
                finish()
            }
        })

        exo.setMediaItem(MediaItem.fromUri(url))
        // 从网页里切过来时接着刚才那秒播，而不是从头开始——用户已经看了十分钟，
        // 一按全屏回到第 0 秒是会被骂的。
        val startMs = intent.getLongExtra(EXTRA_START_MS, 0L)
        if (startMs > 0L) exo.seekTo(startMs)
        exo.playWhenReady = true
        if (title.isNotBlank()) setTitle(title)
        exo.prepare()
    }

    /** 退到后台就停：不停的话用户切走了声音还在放。 */
    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        ImmersiveMode.exit(this, savedCutoutMode)
        super.onDestroy()
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
         * @param startPositionMs 从第几毫秒接着播；0 表示从头。
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

package dev.aurora.browser.media

/**
 * 播放器切集后，通知浏览器把该标签挪到这一集。
 *
 * **单向、可选**：只有「告诉浏览器去哪一页」这一个方向，播放器不等回话。没有标签上下文
 * （从主页「继续观看」进来）或通知失败都不影响播放——切集靠的是
 * [EpisodeSniffer] 自己嗅探，不靠浏览器。
 */
fun interface EpisodeTabSync {
    fun onEpisodeChanged(pageUrl: String)
}

/**
 * 播放器 ↔ 浏览器 的进程内接线点。
 *
 * 与 `NoopFullscreenHost` 同一个套路：默认实现什么都不做，于是单测和不带宿主的入口
 * （从主页续播）都不会因为「没人接」而崩。真正的实现由 `BrowserScreen` 在
 * `DisposableEffect` 里装上、离开时卸掉。
 */
object EpisodeSession {

    @Volatile
    var tabSync: EpisodeTabSync = EpisodeTabSync { }
}

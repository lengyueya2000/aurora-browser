package dev.aurora.browser.data

/**
 * 下载速度计。
 *
 * 速度没有任何接口能问出来，只能自己采样：拿两次「已下载字节数」的差除以时间差。单次采样
 * 的差值受 TCP 分片、磁盘写入和 CDN 抖动影响，直接显示会跳得没法看，所以在一个窗口内
 * 多采几次再算总差——窗口越长越稳，也越迟钝。
 *
 * 时间由调用方传进来（真机上用 `SystemClock.elapsedRealtime()`），这样这个类是纯的、能单测。
 *
 * **三个方法都加了锁。** 一个实例会被两条线程同时用：播放器里那个每 700ms 跑一次的下载
 * 轮询在后台线程上，下载管理页在主线程上（画中画里开着播放器、再打开下载管理就是这个
 * 情形）。`samples` 是普通 `mutableMapOf`，并发 `getOrPut` 与 `keys.retainAll` 会撞出
 * `ConcurrentModificationException`——而调用方（`OfflineMedia.items`）把整个方法包在
 * `runCatching` 里，异常会被吞掉、列表静默变空，看起来就像「我的离线缓存没了」。
 */
class DownloadSpeed(
    /** 参与计算的样本窗口。 */
    private val windowMs: Long = 3_000L,
    /** 两个样本至少要隔这么久才算数，否则除数太小，速度会算出一个荒唐的大数。 */
    private val minSampleMs: Long = 400L,
) {
    private data class Sample(val atMs: Long, val bytes: Long)

    private val samples = mutableMapOf<String, ArrayDeque<Sample>>()

    /**
     * 记一次采样，返回字节/秒。
     *
     * 样本还不够时返回 0——「还不知道」和「一点没动」在界面上是两种说法，调用方按 0 当成
     * 「暂时没有速度可报」处理即可。
     */
    @Synchronized
    fun sample(key: String, bytes: Long, nowMs: Long): Long {
        val queue = samples.getOrPut(key) { ArrayDeque() }
        val last = queue.lastOrNull()
        when {
            last == null -> queue.addLast(Sample(nowMs, bytes))
            // 字节数倒退说明这条被删掉重下、或者换了源：旧样本全部作废。留着会算出负数，
            // 夹到 0 又会让速度一直压在 0 上，要等窗口自然过期才恢复。
            bytes < last.bytes -> {
                queue.clear()
                queue.addLast(Sample(nowMs, bytes))
            }
            // 没有新字节就不记样本。否则「原地不动」的那段时间会一直往后推窗口的右端，
            // 把已有的进度摊薄——一个卡住的下载看起来像在慢慢下。
            bytes == last.bytes -> Unit
            else -> queue.addLast(Sample(nowMs, bytes))
        }
        while (queue.size > 1 && nowMs - queue.first().atMs > windowMs) queue.removeFirst()
        val spanMs = nowMs - queue.first().atMs
        if (queue.size < 2 || spanMs < minSampleMs) return 0L
        val delta = bytes - queue.first().bytes
        return if (delta <= 0L) 0L else delta * 1000L / spanMs
    }

    /** 这条已经不存在了（被删掉），丢掉它的样本，免得这张表一直长。 */
    @Synchronized
    fun forget(key: String) {
        samples.remove(key)
    }

    /** 只保留这些 key。列表刷新后调用，用来清掉已删除条目的样本。 */
    @Synchronized
    fun retain(keys: Set<String>) {
        samples.keys.retainAll(keys)
    }
}

/**
 * 估算总大小。
 *
 * Media3 给不出 HLS 的总长度：分片长度要下完才知道，`Download.contentLength` 在 1.4.1 里
 * 恒为 `C.LENGTH_UNSET`（`DownloadManager` 构造 `Download` 时写死 -1）。能拿到的是「已下载
 * 字节」和「已完成比例」——HLS 的比例是按分片数算的——两者相除就是总量。
 *
 * 比例太低时不给估算：头几个分片和平均值差得远，1% 的时候算出来的数字会离谱地大。
 * 与其显示一个会往下缩的数字，不如先说不知道。返回 0 表示「还不知道」。
 *
 * @param contentLength 已知的总长度，未知传 -1。
 */
fun estimateTotalBytes(contentLength: Long, bytesDownloaded: Long, percent: Float): Long = when {
    contentLength > 0L -> contentLength
    bytesDownloaded <= 0L || percent < MIN_ESTIMATE_PERCENT -> 0L
    percent >= 100f -> bytesDownloaded
    else -> (bytesDownloaded * 100.0 / percent).toLong()
}

/** 低于这个比例不给估算：样本太少，误差比信息量大。 */
private const val MIN_ESTIMATE_PERCENT = 1f

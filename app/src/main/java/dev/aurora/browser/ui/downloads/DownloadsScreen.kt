package dev.aurora.browser.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aurora.browser.data.DownloadItem
import dev.aurora.browser.data.DownloadsRepository
import dev.aurora.browser.media.OfflineItem
import dev.aurora.browser.ui.components.ScreenHeader
import dev.aurora.browser.ui.components.SegmentedControl
import dev.aurora.browser.ui.components.auroraClickable
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraRadius
import dev.aurora.browser.ui.theme.AuroraSpacing

/**
 * 下载管理（规格 5.8）：进行中 / 已完成分段 + 行内进度与操作。
 *
 * 这里有两种下载，来源完全不同：
 * - **文件**（[DownloadItem]）走系统 `DownloadManager`，落在公共「下载」目录里，
 *   用户能在文件管理器里找到、能分享；
 * - **离线缓存**（[OfflineItem]）走 Media3，存在应用私有目录里，只能在应用内离线播。
 *
 * 之所以分成两条路：直链（`.mp4`/`.flv`）能直接存成一个文件，而 HLS（`.m3u8`）是
 * 几百个 `.ts` 分片，系统 `DownloadManager` 只会存下那个几 KB 的播放列表文本——
 * 用户点完「下载」得到一个打不开的文件，比不给按钮更糟。
 *
 * **暂停/继续只给离线缓存这条**。系统 `DownloadManager` 的 `pauseDownload`/`resumeDownload`
 * 是 `@hide` 的（公开 API 只有 enqueue/query/remove），拿反射去调会被 Android 9+ 的隐藏
 * API 限制挡掉，所以文件那条路没有暂停按钮——它本来也做不了「边下边看」（半截 mp4 打不开）。
 * 离线缓存读的是一份分片缓存，下到一半也能播，暂停和边下边看都在这条路上。
 */
@Composable
fun DownloadsScreen(
    downloads: List<DownloadItem>,
    onOpen: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: DownloadTab = DownloadTab.RUNNING,
    offline: List<OfflineItem> = emptyList(),
    /**
     * 离线缓存占用的字节数。显示它是为了回答「删除到底有没有把数据删掉」——
     * 删掉一条后这个数立刻变小，就是真删了（OfflineMedia.cacheBytes）。
     */
    cacheBytes: Long = 0L,
    onOpenOffline: (OfflineItem) -> Unit = {},
    onRemoveOffline: (OfflineItem) -> Unit = {},
    onPauseOffline: (OfflineItem) -> Unit = {},
    onResumeOffline: (OfflineItem) -> Unit = {},
) {
    val c = Aurora.colors
    var tab by remember { mutableStateOf(initialTab) }

    // 只 memo「数据 → 行」这一步。回调**不进 memo**：调用方传进来的 lambda 每次组合都是
    // 新对象，把它们当键等于让 memo 永远命中不了；不当键又会让列表留着上一帧的旧回调。
    // 折中办法是行里只留数据，点击回调到渲染时再绑（见 DownloadRowItem）。
    val rows = remember(downloads, offline, tab) {
        val all = downloads.map { it.toRow() } + offline.map { it.toRow() }
        if (tab == DownloadTab.RUNNING) all.filterNot { it.completed } else all.filter { it.completed }
    }

    Column(modifier = modifier.fillMaxSize().background(c.bg)) {
        ScreenHeader(title = "下载管理", onBack = onBack)

        SegmentedControl(
            options = listOf(DownloadTab.RUNNING to "进行中", DownloadTab.DONE to "已完成"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = AuroraSpacing.l),
        )

        // 离线缓存的落盘体积。没有缓存时不占位，免得空页面上多一行 0 B 的噪音。
        if (cacheBytes > 0L) {
            Text(
                text = "离线缓存占用 " + DownloadsRepository.formatSize(cacheBytes),
                color = c.textMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(
                    start = AuroraSpacing.l,
                    end = AuroraSpacing.l,
                    top = AuroraSpacing.s,
                ),
            )
        }

        if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(AuroraSpacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (tab == DownloadTab.RUNNING) "没有进行中的下载" else "还没有已完成的下载",
                    color = c.textMuted,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = AuroraSpacing.s, horizontal = AuroraSpacing.l),
                verticalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
            ) {
                items(rows, key = { it.key }) { row ->
                    DownloadRowItem(
                        row = row,
                        onOpenFile = onOpen,
                        onRemoveFile = onRemove,
                        onOpenOffline = onOpenOffline,
                        onRemoveOffline = onRemoveOffline,
                        onPauseOffline = onPauseOffline,
                        onResumeOffline = onResumeOffline,
                    )
                }
            }
        }
    }
}

enum class DownloadTab { RUNNING, DONE }

/** 行的来源。回调签名两种来源不同（文件按 id、离线缓存按整条），所以要把来源留着。 */
private sealed interface RowSource {
    data class File(val id: Long) : RowSource
    data class Offline(val item: OfflineItem) : RowSource
}

/** 两种下载归一成一种行，列表才不用为来源分叉。行里只放数据，回调渲染时再绑。 */
private data class DownloadRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val progress: Float?,
    val failed: Boolean,
    val completed: Boolean,
    /** 现在能不能看：已完成能打开，离线缓存没下完也能看（边下边看）。 */
    val canPlay: Boolean,
    /** 现在能不能暂停/继续。 */
    val canPause: Boolean,
    val paused: Boolean,
    val source: RowSource,
)

private fun DownloadItem.toRow() = DownloadRow(
    key = "file-$id",
    title = title.ifBlank { "下载文件" },
    subtitle = statusLine(this),
    progress = if ((isActive || isPaused) && totalBytes > 0) progress else null,
    failed = !isRunning && !isSuccess,
    completed = isSuccess,
    // 直链要下完才有文件可打开，做不了「边下边看」——半截的 mp4/zip 打不开。
    canPlay = isSuccess,
    // 系统下载器没有公开的按条暂停接口（见文件头注释），所以这条路不给暂停按钮。
    // `paused` 仍可能是真：系统自己会因为「等网络」把下载挂起，状态行如实显示。
    canPause = false,
    paused = isPaused,
    source = RowSource.File(id),
)

private fun OfflineItem.toRow() = DownloadRow(
    key = "offline-$url",
    title = title,
    subtitle = offlineStatusLine(this),
    progress = if (isRunning) progress else null,
    failed = isFailed,
    completed = isCompleted,
    canPlay = isPlayable,
    canPause = isRunning,
    paused = isPaused,
    source = RowSource.Offline(this),
)

@Composable
private fun DownloadRowItem(
    row: DownloadRow,
    onOpenFile: (Long) -> Unit,
    onRemoveFile: (Long) -> Unit,
    onOpenOffline: (OfflineItem) -> Unit,
    onRemoveOffline: (OfflineItem) -> Unit,
    onPauseOffline: (OfflineItem) -> Unit,
    onResumeOffline: (OfflineItem) -> Unit,
) {
    val c = Aurora.colors
    val open: (() -> Unit)? = if (!row.canPlay) {
        null
    } else {
        when (val s = row.source) {
            is RowSource.File -> ({ onOpenFile(s.id) })
            is RowSource.Offline -> ({ onOpenOffline(s.item) })
        }
    }
    val remove: () -> Unit = when (val s = row.source) {
        is RowSource.File -> ({ onRemoveFile(s.id) })
        is RowSource.Offline -> ({ onRemoveOffline(s.item) })
    }
    // 暂停 / 继续共用一个位置：正在下显示暂停，已暂停显示继续。只有离线缓存给这个按钮。
    val togglePause: (() -> Unit)? = if (!row.canPause) {
        null
    } else {
        when (val s = row.source) {
            is RowSource.File -> null
            is RowSource.Offline ->
                if (row.paused) ({ onResumeOffline(s.item) }) else ({ onPauseOffline(s.item) })
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AuroraRadius.m))
            .background(c.surface)
            .heightIn(min = 72.dp)
            .padding(AuroraSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
    ) {
        Icon(
            painter = painterResource(AuroraIcons.Download),
            contentDescription = null,
            tint = if (row.failed) c.danger else c.brand,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AuroraSpacing.xs),
        ) {
            Text(row.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = row.subtitle,
                color = if (row.failed) c.danger else c.textMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            row.progress?.let { value ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(AuroraRadius.full))
                        .background(c.bgElevated),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(value)
                            .height(3.dp)
                            .background(c.brand),
                    )
                }
            }
        }
        // 暂停 / 继续：只在下到一半（或已暂停）时出现。图标在两种状态间切换。
        togglePause?.let { onClick ->
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .auroraClickable(onClick = onClick)
                    .semantics { contentDescription = if (row.paused) "继续下载" else "暂停下载" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (row.paused) AuroraIcons.Play else AuroraIcons.Pause),
                    contentDescription = null,
                    tint = c.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        // 播放：已完成是「打开 / 离线播放」，离线缓存没下完是「边下边看」（读同一份缓存，
        // 缺的分片当场从网络补）。直链没下完不给这个按钮——半截文件打不开。
        open?.let { onClick ->
            val label = when (row.source) {
                is RowSource.File -> "打开文件"
                is RowSource.Offline -> if (row.completed) "离线播放" else "边下边看"
            }
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .auroraClickable(onClick = onClick)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(AuroraIcons.Play),
                    contentDescription = null,
                    tint = c.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(AuroraSpacing.touchTarget)
                .auroraClickable(onClick = remove)
                .semantics { contentDescription = "删除下载" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(AuroraIcons.Trash),
                contentDescription = null,
                tint = c.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

private fun statusLine(item: DownloadItem): String = when {
    item.isSuccess ->
        DownloadsRepository.formatSize(item.totalBytes.takeIf { it > 0 } ?: item.bytesDownloaded)
    // 暂停要在「进行中」之前判：`isRunning` 把 PAUSED 也算在内。
    item.isPaused -> "已暂停 · " + DownloadsRepository.formatSize(item.bytesDownloaded)
    item.isRunning -> DownloadsRepository.formatProgress(
        downloaded = item.bytesDownloaded,
        total = item.totalBytes,
        percent = item.progress * 100f,
        speed = item.speed,
        approxTotal = false,
    )
    else -> "下载失败"
}

private fun offlineStatusLine(item: OfflineItem): String = when {
    // 字节数是 0 的「已完成」不是失败：这条流之前已经缓存过，Media3 复用了同一份缓存，
    // 新记录里的字节数就是 0。写「0 B」会让人以为没下下来。
    item.isCompleted -> if (item.bytes > 0) {
        "离线缓存 · " + DownloadsRepository.formatSize(item.bytes)
    } else {
        "离线缓存 · 已缓存"
    }
    item.isFailed -> "离线缓存失败"
    // 已暂停：给个明确的字眼，别让它看起来像卡住了。此时仍可「边下边看」。
    item.isPaused -> "离线缓存 · 已暂停 · " + DownloadsRepository.formatSize(item.bytes)
    item.bytes > 0 || item.speed > 0 -> "离线缓存 · " + DownloadsRepository.formatProgress(
        downloaded = item.bytes,
        total = item.totalBytes,
        percent = item.percent,
        speed = item.speed,
        approxTotal = true,
    ) + DownloadsRepository.formatSpeedNote(item.speed)
    else -> "离线缓存中…"
}

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
    onOpenOffline: (OfflineItem) -> Unit = {},
    onRemoveOffline: (OfflineItem) -> Unit = {},
) {
    val c = Aurora.colors
    var tab by remember { mutableStateOf(initialTab) }

    val rows = remember(downloads, offline, tab) {
        val all = downloads.map { it.toRow(onOpen, onRemove) } +
            offline.map { it.toRow(onOpenOffline, onRemoveOffline) }
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
                items(rows, key = { it.key }) { row -> DownloadRowItem(row) }
            }
        }
    }
}

enum class DownloadTab { RUNNING, DONE }

/** 两种下载归一成一种行，列表才不用为来源分叉。 */
private data class DownloadRow(
    val key: String,
    val title: String,
    val subtitle: String,
    val progress: Float?,
    val failed: Boolean,
    val completed: Boolean,
    val onOpen: (() -> Unit)?,
    val onRemove: () -> Unit,
)

private fun DownloadItem.toRow(onOpen: (Long) -> Unit, onRemove: (Long) -> Unit) = DownloadRow(
    key = "file-$id",
    title = title.ifBlank { "下载文件" },
    subtitle = statusLine(this),
    progress = if (isRunning && totalBytes > 0) progress else null,
    failed = !isRunning && !isSuccess,
    completed = isSuccess,
    onOpen = if (isSuccess) ({ onOpen(id) }) else null,
    onRemove = { onRemove(id) },
)

private fun OfflineItem.toRow(
    onOpen: (OfflineItem) -> Unit,
    onRemove: (OfflineItem) -> Unit,
) = DownloadRow(
    key = "offline-$url",
    title = title,
    subtitle = offlineStatusLine(this),
    progress = if (isRunning) progress else null,
    failed = isFailed,
    completed = isCompleted,
    onOpen = if (isCompleted) ({ onOpen(this) }) else null,
    onRemove = { onRemove(this) },
)

@Composable
private fun DownloadRowItem(row: DownloadRow) {
    val c = Aurora.colors
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
        row.onOpen?.let { open ->
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .auroraClickable(onClick = open)
                    .semantics { contentDescription = "打开下载文件" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(AuroraIcons.Globe),
                    contentDescription = null,
                    tint = c.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(AuroraSpacing.touchTarget)
                .auroraClickable(onClick = row.onRemove)
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
    item.isCompleted -> "离线缓存 · ${DownloadsRepository.formatSize(item.bytes)}"
    item.isFailed -> "离线缓存失败"
    item.bytes > 0 || item.speed > 0 -> "离线缓存 · " + DownloadsRepository.formatProgress(
        downloaded = item.bytes,
        total = item.totalBytes,
        percent = item.percent,
        speed = item.speed,
        approxTotal = true,
    )
    else -> "离线缓存中…"
}

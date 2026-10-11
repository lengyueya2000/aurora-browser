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
import dev.aurora.browser.ui.components.ScreenHeader
import dev.aurora.browser.ui.components.SegmentedControl
import dev.aurora.browser.ui.components.auroraClickable
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraRadius
import dev.aurora.browser.ui.theme.AuroraSpacing

/** 下载管理（规格 5.8）：进行中 / 已完成分段 + 行内进度与操作。 */
@Composable
fun DownloadsScreen(
    downloads: List<DownloadItem>,
    onOpen: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: DownloadTab = DownloadTab.RUNNING,
) {
    val c = Aurora.colors
    var tab by remember { mutableStateOf(initialTab) }

    val running = downloads.filter { it.isRunning || (!it.isSuccess && !it.isRunning) }
    val finished = downloads.filter { it.isSuccess }
    val rows = if (tab == DownloadTab.RUNNING) running else finished

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
                items(rows, key = { it.id }) { item ->
                    DownloadRowItem(item, onOpen = { onOpen(item.id) }, onRemove = { onRemove(item.id) })
                }
            }
        }
    }
}

enum class DownloadTab { RUNNING, DONE }

@Composable
private fun DownloadRowItem(item: DownloadItem, onOpen: () -> Unit, onRemove: () -> Unit) {
    val c = Aurora.colors
    val failed = !item.isRunning && !item.isSuccess
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
            tint = if (failed) c.danger else c.brand,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(AuroraSpacing.xs),
        ) {
            Text(item.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = statusLine(item),
                color = if (failed) c.danger else c.textMuted,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
            if (item.isRunning && item.totalBytes > 0) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(AuroraRadius.full))
                        .background(c.bgElevated),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(item.progress)
                            .height(3.dp)
                            .background(c.brand),
                    )
                }
            }
        }
        if (item.isSuccess) {
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .auroraClickable(onClick = onOpen)
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
                .auroraClickable(onClick = onRemove)
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
    item.isSuccess -> DownloadsRepository.formatSize(item.totalBytes)
    item.isRunning && item.totalBytes > 0 ->
        "${DownloadsRepository.formatSize(item.bytesDownloaded)} / ${DownloadsRepository.formatSize(item.totalBytes)}"
    item.isRunning -> "下载中…"
    else -> "下载失败"
}

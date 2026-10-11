package dev.aurora.browser.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aurora.browser.domain.model.Bookmark
import dev.aurora.browser.domain.model.HistoryEntry
import dev.aurora.browser.ui.components.ScreenHeader
import dev.aurora.browser.ui.components.SegmentedControl
import dev.aurora.browser.ui.components.auroraClickable
import dev.aurora.browser.ui.components.hostOf
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraRadius
import dev.aurora.browser.ui.theme.AuroraSpacing

/** 书签与历史（规格 5.9）：分段切换 + 过滤框 + 列表。 */
@Composable
fun LibraryScreen(
    bookmarks: List<Bookmark>,
    history: List<HistoryEntry>,
    onOpen: (String) -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onClearHistory: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: LibraryTab = LibraryTab.BOOKMARKS,
) {
    val c = Aurora.colors
    var tab by remember { mutableStateOf(initialTab) }
    var filter by remember { mutableStateOf("") }

    // 旧版本存下来的历史里同一地址可能有多条（那时只跟上一条比对去重）。列表的 key 必须
    // 唯一，所以这里按 URL 再兜一次底——顺便把「同一页出现两行」也修掉。
    val rows: List<LibraryRow> = when (tab) {
        LibraryTab.BOOKMARKS -> bookmarks
            .filter { it.matches(filter) }
            .map { LibraryRow(it.title, it.url, it.url, removable = true) }
        LibraryTab.HISTORY -> history
            .filter { it.matches(filter) }
            .map { LibraryRow(it.title, it.url, hostOf(it.url), removable = false) }
    }.distinctBy { it.url }

    Column(modifier = modifier.fillMaxSize().background(c.bg)) {
        ScreenHeader(
            title = "书签与历史",
            onBack = onBack,
            trailing = {
                if (tab == LibraryTab.HISTORY) {
                    Box(
                        modifier = Modifier
                            .size(AuroraSpacing.touchTarget)
                            .auroraClickable(onClick = onClearHistory)
                            .semantics { contentDescription = "清空历史" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(AuroraIcons.Trash),
                            contentDescription = null,
                            tint = c.textSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
        )

        SegmentedControl(
            options = listOf(LibraryTab.BOOKMARKS to "书签", LibraryTab.HISTORY to "历史"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = AuroraSpacing.l),
        )

        FilterField(
            value = filter,
            onValueChange = { filter = it },
            hint = if (tab == LibraryTab.BOOKMARKS) "过滤书签" else "过滤历史",
        )

        if (rows.isEmpty()) {
            EmptyHint(
                text = when (tab) {
                    LibraryTab.BOOKMARKS ->
                        if (filter.isBlank()) "还没有书签。在网页里点「☆」即可收藏。" else "没有匹配的书签"
                    LibraryTab.HISTORY ->
                        if (filter.isBlank()) "还没有浏览记录。" else "没有匹配的记录"
                },
            )
        } else {
            // 唯一滚动容器
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = AuroraSpacing.xxl),
            ) {
                items(rows, key = { it.url }) { row ->
                    LibraryRowItem(
                        row = row,
                        onOpen = { onOpen(row.url) },
                        onRemove = { onRemoveBookmark(row.url) },
                    )
                }
            }
        }
    }
}

enum class LibraryTab { BOOKMARKS, HISTORY }

private data class LibraryRow(
    val title: String,
    val url: String,
    val subtitle: String,
    val removable: Boolean,
)

private fun Bookmark.matches(q: String) =
    q.isBlank() || title.contains(q, true) || url.contains(q, true)

private fun HistoryEntry.matches(q: String) =
    q.isBlank() || title.contains(q, true) || url.contains(q, true)

@Composable
private fun FilterField(value: String, onValueChange: (String) -> Unit, hint: String) {
    val c = Aurora.colors
    Box(
        modifier = Modifier
            .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s)
            .fillMaxWidth()
            .heightIn(min = AuroraSpacing.touchTarget)
            .clip(RoundedCornerShape(AuroraRadius.m))
            .background(c.surface)
            .border(1.dp, c.divider, RoundedCornerShape(AuroraRadius.m))
            .padding(horizontal = AuroraSpacing.m),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = c.textDisabled)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = c.text),
            cursorBrush = SolidColor(c.brand),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = hint },
        )
    }
}

@Composable
private fun LibraryRowItem(row: LibraryRow, onOpen: () -> Unit, onRemove: () -> Unit) {
    val c = Aurora.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .auroraClickable(onClick = onOpen)
            .padding(start = AuroraSpacing.l, end = AuroraSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
    ) {
        Icon(
            painter = painterResource(if (row.removable) AuroraIcons.Star else AuroraIcons.Clock),
            contentDescription = null,
            tint = c.textMuted,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(row.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = row.subtitle,
                color = c.textMuted,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.removable) {
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .auroraClickable(onClick = onRemove)
                    .semantics { contentDescription = "删除书签" },
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
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(AuroraSpacing.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Aurora.colors.textMuted)
    }
}

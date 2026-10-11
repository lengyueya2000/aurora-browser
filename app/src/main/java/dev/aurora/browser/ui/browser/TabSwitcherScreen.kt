package dev.aurora.browser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aurora.browser.domain.model.BrowserTab
import dev.aurora.browser.ui.components.SegmentedControl
import dev.aurora.browser.ui.components.hostOf
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

enum class TabLayout { GRID, LIST }

@Composable
fun TabSwitcherScreen(
    tabs: List<BrowserTab>,
    activeId: String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onNewTab: () -> Unit,
    onCloseAll: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    initialLayout: TabLayout = TabLayout.GRID,
) {
    val c = Aurora.colors
    var layout by remember { mutableStateOf(initialLayout) }

    Column(modifier = modifier.fillMaxSize().background(c.bg)) {
        // 顶栏：计数 + 视图切换 + 新建 + 关闭全部
        Row(
            modifier = Modifier.fillMaxWidth().padding(AuroraSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
        ) {
            Text(
                text = tabs.size.toString(),
                color = c.text,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
            )
            Text("个标签页", color = c.textMuted)
            Spacer(Modifier.weight(1f))
            SegmentedControl(
                options = listOf(TabLayout.GRID to "网格", TabLayout.LIST to "列表"),
                selected = layout,
                onSelect = { layout = it },
            )
        }

        // 每屏只允许一个滚动容器
        Box(modifier = Modifier.weight(1f)) {
            when (layout) {
                TabLayout.GRID -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s),
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
                    verticalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
                ) {
                    items(tabs, key = { it.id }) { tab ->
                        TabCard(tab, selected = tab.id == activeId,
                            onSelect = { onSelect(tab.id) }, onClose = { onClose(tab.id) })
                    }
                }
                TabLayout.LIST -> LazyColumn(
                    contentPadding = PaddingValues(vertical = AuroraSpacing.s),
                ) {
                    listItems(tabs, key = { it.id }) { tab ->
                        TabRowItem(tab, selected = tab.id == activeId,
                            onSelect = { onSelect(tab.id) }, onClose = { onClose(tab.id) })
                    }
                }
            }
        }

        // 底部：关闭全部 / 新建 / 完成
        Row(
            modifier = Modifier.fillMaxWidth().padding(AuroraSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
        ) {
            IconAction(AuroraIcons.Trash, "关闭全部标签页", onCloseAll)
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.brand)
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onNewTab,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(AuroraIcons.Add),
                    contentDescription = "新建标签页",
                    tint = c.onBrand,
                )
            }
            Spacer(Modifier.weight(1f))
            IconAction(AuroraIcons.ChevronRight, "完成", onDone)
        }
    }
}

@Composable
private fun IconAction(icon: Int, label: String, onClick: () -> Unit) {
    val c = Aurora.colors
    Box(
        modifier = Modifier
            .size(AuroraSpacing.touchTarget)
            .clip(RoundedCornerShape(AuroraRadius.full))
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = label, tint = c.textSecondary)
    }
}

@Composable
private fun TabCard(tab: BrowserTab, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val c = Aurora.colors
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(AuroraRadius.m))
            .background(c.surface)
            .border(
                1.dp,
                if (selected) c.brand else c.divider,
                RoundedCornerShape(AuroraRadius.m),
            )
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            ),
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(96.dp).background(c.bgElevated)) {
            // 关闭是独立可点目标，触摸区必须 ≥48dp（全局约束「最小触摸目标 48×48dp」）。
            // 可见的圆形底片仍保持 28dp，由 48dp 触摸区居中包裹它。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(AuroraSpacing.touchTarget)
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    )
                    .semantics { contentDescription = "关闭标签页" },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(AuroraRadius.full))
                        .background(c.scrim),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(AuroraIcons.Close),
                        contentDescription = null,
                        tint = c.textSecondary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Text(
            text = tab.title,
            color = c.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(AuroraSpacing.s),
        )
    }
}

@Composable
private fun TabRowItem(tab: BrowserTab, selected: Boolean, onSelect: () -> Unit, onClose: () -> Unit) {
    val c = Aurora.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (selected) c.surfaceHover else c.bg)
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect,
            )
            .padding(horizontal = AuroraSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tab.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = tab.url?.let { hostOf(it) } ?: "新标签页",
                // 次级信息文字须 ≥4.5:1：textDisabled(#64748B) 在 bg 上仅 ~3.75:1，
                // textMuted(#94A3B8) 在 bg/surface 上 ~7.0/6.1:1。禁用色只留给真正禁用的控件。
                color = c.textMuted,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            )
        }
        IconAction(AuroraIcons.Close, "关闭标签页", onClose)
    }
}

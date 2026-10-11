package dev.aurora.browser.ui.browser

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aurora.browser.domain.model.BrowserTab
import dev.aurora.browser.ui.components.SegmentedControl
import dev.aurora.browser.ui.components.auroraClickable
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
    onNewIncognitoTab: () -> Unit = {},
    /** 标签缩略图，按 tabId 取。没拍到的（空白标签、无痕标签）退回占位图。 */
    thumbnails: Map<String, Bitmap> = emptyMap(),
) {
    val c = Aurora.colors
    var layout by remember { mutableStateOf(initialLayout) }

    // Page/WebView 常驻在下方，本覆盖层必须吞掉空白区域的点击（如空标签列表的空白视口），
    // 否则会穿透到 WebView。不透明 background 只是绘制，不是命中测试边界——只有消费型指针
    // 修饰符才能挡住事件。用 detectTapGestures（而非 clickable）：只消费事件、不加 Button
    // 语义角色，且要求 down 未被消费，因此标签卡片/按钮仍能拿到自己的点击。同 AuroraBottomSheet。
    Column(
        modifier = modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .fillMaxSize()
            .background(c.bg),
    ) {
        // 顶栏：计数 + 视图切换
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
                        TabCard(
                            tab, selected = tab.id == activeId,
                            thumbnail = thumbnails[tab.id],
                            onSelect = { onSelect(tab.id) }, onClose = { onClose(tab.id) },
                        )
                    }
                }
                TabLayout.LIST -> LazyColumn(
                    contentPadding = PaddingValues(vertical = AuroraSpacing.s),
                ) {
                    listItems(tabs, key = { it.id }) { tab ->
                        TabRowItem(
                            tab, selected = tab.id == activeId,
                            thumbnail = thumbnails[tab.id],
                            onSelect = { onSelect(tab.id) }, onClose = { onClose(tab.id) },
                        )
                    }
                }
            }
        }

        // 底部：关闭全部 / 新建（普通 + 无痕）/ 完成
        Row(
            modifier = Modifier.fillMaxWidth().padding(AuroraSpacing.l),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
        ) {
            IconAction(AuroraIcons.Trash, "关闭全部标签页", onCloseAll)
            Spacer(Modifier.weight(1f))
            // 无痕入口做成描边圆钮（对照填充品牌色的普通新建），两个相邻可点目标
            // 之间有 8dp 间距（Row 的 spacedBy），满足全局约束。
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.bgElevated)
                    .border(1.dp, c.brand.copy(alpha = 0.5f), RoundedCornerShape(AuroraRadius.full))
                    .auroraClickable(onClick = onNewIncognitoTab)
                    .semantics { contentDescription = "新建无痕标签页" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(AuroraIcons.Incognito),
                    contentDescription = null,
                    tint = c.brand,
                )
            }
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.brand)
                    .auroraClickable(onClick = onNewTab)
                    .semantics { contentDescription = "新建标签页" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(AuroraIcons.Add),
                    contentDescription = null,
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
            .auroraClickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter = painterResource(icon), contentDescription = null, tint = c.textSecondary)
    }
}

@Composable
private fun TabCard(
    tab: BrowserTab,
    selected: Boolean,
    thumbnail: Bitmap?,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Aurora.colors
    val accent = if (tab.isIncognito) c.brand.copy(alpha = 0.5f) else if (selected) c.brand else c.divider
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(AuroraRadius.m))
            .background(c.surface)
            .border(1.dp, accent, RoundedCornerShape(AuroraRadius.m))
            .auroraClickable(onClick = onSelect),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(if (tab.isIncognito) c.bg else c.bgElevated),
            contentAlignment = Alignment.Center,
        ) {
            when {
                tab.isIncognito -> Icon(
                    painter = painterResource(AuroraIcons.Incognito),
                    contentDescription = "无痕标签页",
                    tint = c.brand,
                    modifier = Modifier.size(28.dp),
                )
                thumbnail != null -> Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                // 还没拍到的标签（空白标签、或拍出来是空白）留一块底色，别让卡片空着。
                else -> Unit
            }
            // 关闭是独立可点目标，触摸区必须 ≥48dp（全局约束「最小触摸目标 48×48dp」）。
            // 可见的圆形底片仍保持 28dp，由 48dp 触摸区居中包裹它。
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(AuroraSpacing.touchTarget)
                    .auroraClickable(onClick = onClose)
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
private fun TabRowItem(
    tab: BrowserTab,
    selected: Boolean,
    thumbnail: Bitmap?,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val c = Aurora.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .background(if (selected) c.surfaceHover else c.bg)
            .auroraClickable(onClick = onSelect)
            .padding(horizontal = AuroraSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
    ) {
        if (tab.isIncognito) {
            Icon(
                painter = painterResource(AuroraIcons.Incognito),
                contentDescription = "无痕标签页",
                tint = c.brand,
                modifier = Modifier.size(18.dp),
            )
        } else if (thumbnail != null) {
            // 列表视图里的小缩略图：够认出是哪一页就行，不占地方。
            Image(
                bitmap = thumbnail.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(48.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(AuroraRadius.xs)),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(tab.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 新标签页没有地址，副标题就不画：否则标题和副标题都是「新标签页」，
            // 看着像渲染了两遍。
            tab.url?.let { url ->
                Text(
                    text = hostOf(url),
                    // 次级信息文字须 ≥4.5:1：textDisabled(#64748B) 在 bg 上仅 ~3.75:1，
                    // textMuted(#94A3B8) 在 bg/surface 上 ~7.0/6.1:1。禁用色只留给真正禁用的控件。
                    color = c.textMuted,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                )
            }
        }
        IconAction(AuroraIcons.Close, "关闭标签页", onClose)
    }
}

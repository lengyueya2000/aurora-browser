package dev.aurora.browser.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

/** 信息流缩略图的固定比例（宽 4 : 高 3），不取自间距刻度——它不是间距。 */
private val FeedThumbWidth = 88.dp
private val FeedThumbHeight = 66.dp

/** 给悬浮工具栏留出的底部空间：工具栏 58dp + 上下留白，避免最后一条内容被压住。 */
private val BottomToolbarClearance = 96.dp

data class Shortcut(val label: String, val url: String)

val DefaultShortcuts = listOf(
    Shortcut("知乎", "https://www.zhihu.com"),
    Shortcut("哔哩哔哩", "https://www.bilibili.com"),
    Shortcut("微博", "https://weibo.com"),
    Shortcut("GitHub", "https://github.com"),
    Shortcut("掘金", "https://juejin.cn"),
    Shortcut("淘宝", "https://www.taobao.com"),
)

private data class FeedItem(val title: String, val source: String, val tag: String)
private val DefaultFeed = listOf(
    FeedItem("Compose 1.11 稳定版发布：延迟布局与共享元素转场", "Android Weekly", "技术"),
    FeedItem("为什么现代浏览器都在重做标签管理？", "少数派", "产品"),
    FeedItem("一次点击背后：追踪器拦截的技术原理拆解", "InfoQ", "隐私"),
)

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    shortcuts: List<Shortcut> = DefaultShortcuts,
    showFeed: Boolean = true,
) {
    val c = Aurora.colors
    // 唯一滚动容器：快捷入口网格嵌在 LazyColumn 的一个 item 里，用非滚动网格
    LazyColumn(modifier = modifier.fillMaxSize().background(c.bg)) {
        item {
            Box(
                modifier = Modifier
                    .padding(AuroraSpacing.m)
                    .fillMaxWidth()
                    // 同 OmniboxBar：可点元素须 ≥48dp 高，原型 44px 只是视觉稿。
                    .height(AuroraSpacing.touchTarget)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.surfaceHover)
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSearch,
                    )
                    .semantics(mergeDescendants = true) { contentDescription = "搜索或输入网址" }
                    .padding(horizontal = AuroraSpacing.l),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
                    // 整条已经用 contentDescription 描述过了；不清掉里面的图标与文字，
                    // 合并语义后读屏会把「搜索或输入网址」念两遍（文案 + 描述）。
                    modifier = Modifier.clearAndSetSemantics { },
                ) {
                    Icon(painterResource(AuroraIcons.Search), null, tint = c.textDisabled,
                        modifier = Modifier.size(18.dp))
                    Text("搜索或输入网址", color = c.textDisabled)
                }
            }
        }
        item {
            SectionTitle("快捷入口")
            Column(modifier = Modifier.padding(horizontal = AuroraSpacing.l)) {
                shortcuts.chunked(4).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = AuroraSpacing.s),
                        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s)) {
                        row.forEach { s ->
                            ShortcutTile(s, Modifier.weight(1f)) { onNavigate(s.url) }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (showFeed) {
            item { SectionTitle("为你推荐") }
            items(DefaultFeed.size) { i ->
                val f = DefaultFeed[i]
                Row(
                    modifier = Modifier
                        .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(AuroraRadius.m))
                        .background(c.surface)
                        .border(1.dp, c.divider, RoundedCornerShape(AuroraRadius.m))
                        .padding(AuroraSpacing.m),
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
                ) {
                    Box(modifier = Modifier.size(width = FeedThumbWidth, height = FeedThumbHeight)
                        .clip(RoundedCornerShape(AuroraRadius.s)).background(c.bgElevated))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(AuroraSpacing.xs)) {
                        Text(f.title, color = c.text, maxLines = 2)
                        // 次级信息文字须 ≥4.5:1（同 Task 11 的 TabRowItem URL）：用 textMuted。
                        Text("${f.tag} · ${f.source}", color = c.textMuted,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(BottomToolbarClearance)) }   // 给悬浮工具栏留出空间，避免遮挡内容
    }
}

@Composable
private fun SectionTitle(text: String) {
    val c = Aurora.colors
    Text(
        text = text,
        color = c.textMuted,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(
            start = AuroraSpacing.l, end = AuroraSpacing.l,
            top = AuroraSpacing.xl, bottom = AuroraSpacing.s,
        ),
    )
}

@Composable
private fun ShortcutTile(s: Shortcut, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Aurora.colors
    Column(
        modifier = modifier
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = s.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(AuroraRadius.m))
                .background(c.surfaceHover),
            contentAlignment = Alignment.Center,
        ) {
            Text(s.label.take(1), color = c.text)
        }
        Text(s.label, color = c.textMuted,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            maxLines = 1)
    }
}

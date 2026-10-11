package dev.aurora.browser.ui.home

import androidx.compose.foundation.background
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
import dev.aurora.browser.ui.components.BottomToolbarHeight
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

/** 给悬浮工具栏留出的底部空间，取自工具栏自己的高度定义，避免两处各写一个数。 */
private val BottomToolbarClearance = BottomToolbarHeight

data class Shortcut(val label: String, val url: String)

val DefaultShortcuts = listOf(
    Shortcut("知乎", "https://www.zhihu.com"),
    Shortcut("哔哩哔哩", "https://www.bilibili.com"),
    Shortcut("微博", "https://weibo.com"),
    Shortcut("GitHub", "https://github.com"),
    Shortcut("掘金", "https://juejin.cn"),
    Shortcut("淘宝", "https://www.taobao.com"),
)

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    shortcuts: List<Shortcut> = DefaultShortcuts,
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

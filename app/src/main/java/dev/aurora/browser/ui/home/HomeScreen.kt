package dev.aurora.browser.ui.home

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aurora.browser.data.FaviconStore
import dev.aurora.browser.media.PlayerGestures
import dev.aurora.browser.media.ResumeEntry
import dev.aurora.browser.ui.components.BottomToolbarHeight
import dev.aurora.browser.ui.components.hostOf
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

/** 给悬浮工具栏留出的底部空间，取自工具栏自己的高度定义，避免两处各写一个数。 */
private val BottomToolbarClearance = BottomToolbarHeight

/** 「继续观看」最多露几张。再多就把下面的快捷入口挤到屏幕外了。 */
private const val MaxResumeCards = 4

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
    resume: List<ResumeEntry> = emptyList(),
    onResume: (ResumeEntry) -> Unit = {},
    onForgetResume: (ResumeEntry) -> Unit = {},
    /** 站点图标。默认空表时快捷入口画首字母，测试与预览不必去取图。 */
    favicons: FaviconStore? = null,
) {
    val c = Aurora.colors
    // 图标是异步取的（内存 → 磁盘 → 网络），取到之后由 store 内部的快照 Map 触发重画，
    // 这里只负责在组合时把没取过的补上。
    LaunchedEffect(shortcuts, favicons) {
        val store = favicons ?: return@LaunchedEffect
        shortcuts.map { hostOf(it.url) }.filter { it.isNotBlank() }.distinct()
            .forEach { store.load(it) }
    }
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
        // 排在快捷入口前面：接着看上一集比再点一个站点更接近「打开浏览器想干的事」。
        if (resume.isNotEmpty()) {
            item {
                SectionTitle("继续观看")
                Column(
                    modifier = Modifier.padding(horizontal = AuroraSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
                ) {
                    resume.take(MaxResumeCards).forEach { entry ->
                        ResumeCard(
                            entry = entry,
                            onClick = { onResume(entry) },
                            onForget = { onForgetResume(entry) },
                        )
                    }
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
                            ShortcutTile(
                                s, Modifier.weight(1f),
                                icon = favicons?.cached(hostOf(s.url)),
                                onClick = { onNavigate(s.url) },
                            )
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(BottomToolbarClearance)) }   // 给悬浮工具栏留出空间，避免遮挡内容
    }
}

/**
 * 一张「接着看」的卡片：标题 + 出处与剩余时间 + 进度条。
 *
 * 右侧那个 × 是**独立**的可点元素，不在整行的合并语义里——嵌套可点目标会让读屏
 * 把两个动作读成一个。所以这里是 Row[可点的内容块, 删除按钮]，而不是「整行可点 + 里面一个按钮」。
 */
@Composable
private fun ResumeCard(entry: ResumeEntry, onClick: () -> Unit, onForget: () -> Unit) {
    val c = Aurora.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AuroraRadius.m))
            .background(c.surfaceHover),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .semantics(mergeDescendants = true) { contentDescription = resumeDescription(entry) }
                .padding(
                    start = AuroraSpacing.m, top = AuroraSpacing.m,
                    end = AuroraSpacing.s, bottom = AuroraSpacing.m,
                ),
            verticalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
        ) {
            Text(
                text = entry.title,
                color = c.text,
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = resumeSubtitle(entry),
                color = c.textMuted,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 进度条自绘（3dp 高的两层 Box），和网页加载条一样——比拖进一个
            // Material 进度组件轻，颜色也用得上主题里的品牌色。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.border),
            ) {
                if (entry.progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(entry.progress)
                            .fillMaxHeight()
                            .background(c.brand),
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(end = AuroraSpacing.xs)
                .size(AuroraSpacing.touchTarget)
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onForget,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(AuroraIcons.Close),
                contentDescription = "从「继续观看」中移除",
                tint = c.textDisabled,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 出处用**来源页**的域名，不是流的地址：真实流多半在某个 CDN 上，那个域名用户没见过。 */
private fun resumeSubtitle(entry: ResumeEntry): String {
    val where = hostOf(entry.referer ?: entry.url)
    val rest = if (entry.durationMs > 0L) {
        "还剩 " + PlayerGestures.formatTime(entry.durationMs - entry.positionMs)
    } else {
        "看到 " + PlayerGestures.formatTime(entry.positionMs)
    }
    return if (where.isBlank()) rest else "$where · $rest"
}

private fun resumeDescription(entry: ResumeEntry): String =
    "继续观看 ${entry.title}，${resumeSubtitle(entry)}"

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
private fun ShortcutTile(
    s: Shortcut,
    modifier: Modifier = Modifier,
    icon: Bitmap? = null,
    onClick: () -> Unit,
) {
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
            if (icon != null) {
                // 图标本身多半带透明边或方形底，缩到 28dp 留出呼吸空间，
                // 不然站点的方块底会把整个 52dp 填满，六个格子糊成一片。
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Text(s.label.take(1), color = c.text)
            }
        }
        Text(s.label, color = c.textMuted,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            maxLines = 1)
    }
}

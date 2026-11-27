package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aurora.browser.ui.icons.AuroraIcons

/**
 * 底栏固定配色：**不透明白底 + 纯黑图标 + 浅灰分隔线，不跟随主题**。
 *
 * 为什么这一层不跟主题走：参考样式里的底栏是一块固定在屏幕最下方的白条，
 * 它的语义更接近系统导航条而不是应用内容——深色主题下也保持白底，才会和
 * 网页内容区分得开（网页在深色主题下是深底，底栏跟着变深就糊成一片）。
 *
 * 和播放器控制层（固定深色）是同一类处理，只是方向相反。
 */
private val BarBackground: Color = Color(0xFFFFFFFF)
private val BarDivider: Color = Color(0xFFE5E5E5)
private val BarIcon: Color = Color(0xFF111111)
private val BarIconDisabled: Color = Color(0xFFB0B0B0)
private val BarBrand: Color = Color(0xFF0F5C4A)

/**
 * 底栏高度：48dp 触摸目标 + 上下各 8dp。
 *
 * 底栏是**贴屏幕底边的平条**（不是悬浮胶囊），顶上一条 1dp 分隔线。渲染时
 * 还会按 `navigationBarsPadding()` 额外让出系统手势区。
 *
 * 内容避让都读 [BottomToolbarHeight]：首页信息流的 `BottomToolbarClearance`、
 * 网页末尾的 `WebViewPool.bottomInsetScript`、标签缩略图拍摄时的 `toolbarInsetPx`。
 */
private val ToolbarBarHeight: Dp = 48.dp + 8.dp * 2

/** 内容需要避让的底栏高度（不含系统手势区，那部分由各消费方自己的 insets 处理）。 */
val BottomToolbarHeight: Dp = ToolbarBarHeight

/**
 * 贴屏幕底边的导航栏。五个按钮：后退 / 前进 / 主页 / 标签 / 更多。
 *
 * 触摸目标 48dp、相邻间距 8dp——这是 Android 的最小可点尺寸，`BottomToolbarTest`
 * 里两条测试专门盯着，改样式时别把它改小了。
 */
@Composable
fun BottomToolbar(
    tabCount: Int,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onHome: () -> Unit,
    onTabs: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    canGoBack: Boolean = true,
    canGoForward: Boolean = false,
    homeSelected: Boolean = false,
    tabsSelected: Boolean = false,
    incognito: Boolean = false,
) {
    val tabLabel = if (tabCount > 99) "99+" else tabCount.toString()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BarBackground)
            .border(width = 1.dp, color = BarDivider)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ToolbarBarHeight)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarSlot(
                icon = AuroraIcons.Back, label = "后退",
                enabled = canGoBack, selected = false, onClick = onBack,
            )
            ToolbarSlot(
                icon = AuroraIcons.Forward, label = "前进",
                enabled = canGoForward, selected = false, onClick = onForward,
            )
            ToolbarSlot(
                icon = AuroraIcons.Home, label = "主页",
                enabled = true, selected = homeSelected, onClick = onHome,
            )
            // 标签槽显示计数方块，语义同原型
            ToolbarSlot(label = "标签页，$tabLabel 个", enabled = true, selected = tabsSelected, onClick = onTabs) {
                Box(
                    modifier = Modifier
                        .size(width = 24.dp, height = 24.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.6.dp, LocalContentColor.current, RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = tabLabel,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LocalContentColor.current,
                    )
                }
            }
            ToolbarSlot(
                icon = AuroraIcons.More, label = "更多",
                enabled = true, selected = false, onClick = onMenu,
                rotateDegrees = 90f,
            )
        }
    }
}

@Composable
private fun ToolbarSlot(
    label: String,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    icon: Int? = null,
    rotateDegrees: Float = 0f,
    content: (@Composable () -> Unit)? = null,
) {
    val tint = when {
        !enabled -> BarIconDisabled
        selected -> BarBrand
        else -> BarIcon
    }
    Box(
        modifier = Modifier
            .size(48.dp)                                  // 最小触摸目标
            .clip(CircleShape)
            .semantics { contentDescription = label }      // 计数槽没有 Icon，语义挂在这里
            .clickable(
                enabled = enabled,
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides tint) {
            if (content != null) content()
            else if (icon != null) {
                Icon(
                    painter = painterResource(icon),
                    contentDescription = null,             // 语义已由外层 Box 承载，避免重复朗读
                    tint = tint,
                    modifier = Modifier
                        .size(24.dp)
                        .then(if (rotateDegrees != 0f) Modifier.rotate(rotateDegrees) else Modifier),
                )
            }
        }
    }
}

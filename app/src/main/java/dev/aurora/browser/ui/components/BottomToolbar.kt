package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

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
    val c = Aurora.colors
    val tabLabel = if (tabCount > 99) "99+" else tabCount.toString()

    Row(
        modifier = modifier
            .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.xl)
            .height(58.dp)
            .clip(RoundedCornerShape(AuroraRadius.full))
            .background(if (incognito) c.bg.copy(alpha = 0.82f) else c.glass)
            .border(
                width = 1.dp,
                color = if (incognito) c.brand.copy(alpha = 0.34f) else c.glassBorder,
                shape = RoundedCornerShape(AuroraRadius.full),
            )
            .padding(horizontal = AuroraSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.xs),
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
                    .clip(RoundedCornerShape(AuroraRadius.xs))
                    .border(1.6.dp, LocalContentColor.current, RoundedCornerShape(AuroraRadius.xs)),
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
    val c = Aurora.colors
    val tint = when {
        !enabled -> c.textDisabled
        selected -> c.brand
        else -> c.textSecondary
    }
    Box(
        modifier = Modifier
            .size(AuroraSpacing.touchTarget)          // 48dp，满足最小触摸目标
            .clip(CircleShape)
            .semantics { contentDescription = label }  // 计数槽没有 Icon，语义只能挂在这里
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
                    contentDescription = null,   // 语义已由外层 Box 承载，避免重复朗读
                    tint = tint,
                    modifier = Modifier
                        .size(24.dp)
                        .then(
                            if (rotateDegrees != 0f) Modifier.rotate(rotateDegrees) else Modifier
                        ),
                )
            }
        }
    }
}

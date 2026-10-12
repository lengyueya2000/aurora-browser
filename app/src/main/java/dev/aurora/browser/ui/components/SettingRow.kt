package dev.aurora.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    icon: Int? = null,
    onClick: (() -> Unit)? = null,
    /**
     * 开关行的当前状态；非 null 时整行按**开关**而不是按钮暴露给无障碍服务。
     *
     * 为什么要有这个参数：开关行把 `Switch(onCheckedChange = null)` 交给整行的点击处理
     * （见 `SwitchRow`），于是无障碍树里只剩这一层的语义。用 `Role.Button` 的话读屏会把
     * 一个开关念成「按钮」、也读不出开还是关——这里补上 `toggleable` + `Role.Switch`。
     */
    toggleState: Boolean? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = Aurora.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .then(
                when {
                    onClick == null -> Modifier
                    toggleState != null -> Modifier.toggleable(
                        value = toggleState,
                        role = Role.Switch,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onValueChange = { onClick() },
                    )
                    else -> Modifier.clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClick,
                    )
                }
            )
            .padding(horizontal = AuroraSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,          // 装饰性：旁边有可见标题
                tint = c.textMuted,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = c.text)
            if (subtitle != null) {
                Text(text = subtitle, color = c.textMuted, fontFamily = FontFamily.Monospace,
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
            }
        }
        if (value != null) {
            Text(text = value, color = c.textSecondary, fontFamily = FontFamily.Monospace)
        }
        if (trailing != null) trailing()
        else if (onClick != null && value == null) {
            Icon(
                painter = painterResource(AuroraIcons.ChevronRight),
                contentDescription = null,
                tint = c.textDisabled,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

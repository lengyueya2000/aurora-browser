package dev.aurora.browser.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role

/**
 * 全应用统一的可点修饰符：无涟漪、无尺寸变化（规格 2.5「按压态不得改变布局尺寸」）。
 *
 * 抽出来是因为这个 `clickable(role, interactionSource = remember { … }, indication = null)`
 * 组合在项目里重复了十几处，改一次按压反馈要改十几处。
 */
@Composable
fun Modifier.auroraClickable(
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier = this.clickable(
    enabled = enabled,
    role = role,
    interactionSource = remember { MutableInteractionSource() },
    indication = null,
    onClick = onClick,
)

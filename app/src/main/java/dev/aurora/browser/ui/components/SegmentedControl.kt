package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.theme.*

@Composable
fun <T> SegmentedControl(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Aurora.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(AuroraRadius.full))
            .background(c.bgElevated)
            .border(1.dp, c.divider, RoundedCornerShape(AuroraRadius.full))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { (value, label) ->
            val isOn = value == selected
            Box(
                modifier = Modifier
                    // 48dp 而非紧凑的 36dp：每个分段都是独立可点目标，须满足
                    // 全局约束「最小触摸目标 48×48dp」。
                    .heightIn(min = AuroraSpacing.touchTarget)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(if (isOn) c.surface else c.bgElevated)
                    .semantics { this.selected = isOn }
                    .clickable(
                        role = Role.Tab,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onSelect(value) },
                    )
                    .padding(horizontal = AuroraSpacing.m, vertical = AuroraSpacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = label, color = if (isOn) c.text else c.textMuted)
            }
        }
    }
}

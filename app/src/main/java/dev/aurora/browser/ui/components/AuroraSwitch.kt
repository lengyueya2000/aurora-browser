package dev.aurora.browser.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import dev.aurora.browser.ui.theme.*

/**
 * 整行可点，命中区 ≥48dp。label 既是可见文案也是无障碍名称，
 * 因此点击 label 也能切换（用 toggleable 而非只包 Switch）。
 */
@Composable
fun AuroraSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val c = Aurora.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AuroraSpacing.touchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = AuroraSpacing.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label != null) {
            Text(text = label, color = c.text, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        Switch(
            checked = checked,
            onCheckedChange = null,          // 由父级 toggleable 统一处理，避免双重触发
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.onBrand,
                checkedTrackColor = c.brand,
                uncheckedThumbColor = c.textMuted,
                uncheckedTrackColor = c.surfaceHover,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}

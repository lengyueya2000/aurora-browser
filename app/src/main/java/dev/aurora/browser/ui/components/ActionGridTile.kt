package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraRadius
import dev.aurora.browser.ui.theme.AuroraSpacing

/**
 * 菜单面板里的动作块（规格 6 `ActionGridTile`）：图标 24dp + 标签 13sp，
 * 命中区 ≥72×72dp，4 列。整块可点，语义描述挂在整块上。
 */
@Composable
fun ActionGridTile(
    icon: Int,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Aurora.colors
    val tint = if (enabled) c.brand else c.textDisabled
    val labelColor = if (enabled) c.textSecondary else c.textDisabled
    Column(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clip(RoundedCornerShape(AuroraRadius.m))
            // surface 而不是 bgElevated：动作块铺在同样用 bgElevated 的菜单面板上，
            // 底色相同等于没有底（深浅两套都是如此，原型里 .act 用的是 surface + 发丝线）。
            .background(c.surface)
            .border(1.dp, c.divider, RoundedCornerShape(AuroraRadius.m))
            .auroraClickable(enabled = enabled, onClick = onClick)
            .padding(vertical = AuroraSpacing.m, horizontal = AuroraSpacing.s)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AuroraSpacing.s, Alignment.CenterVertically),
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            color = labelColor,
            textAlign = TextAlign.Center,
            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

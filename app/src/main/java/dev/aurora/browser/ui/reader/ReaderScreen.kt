package dev.aurora.browser.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aurora.browser.ui.components.ScreenHeader
import dev.aurora.browser.ui.components.auroraClickable
import dev.aurora.browser.ui.components.hostOf
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraSpacing

/**
 * 阅读模式（规格 5.7）：去掉页面装饰，只留正文，配字号调节。
 * 正文宽度限制在 34–40 字符/行（规格 7「长文本」）。
 */
@Composable
fun ReaderScreen(
    title: String,
    url: String,
    text: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Aurora.colors
    var scale by remember { mutableFloatStateOf(1f) }

    Column(modifier = modifier.fillMaxSize().background(c.bg)) {
        ScreenHeader(
            title = "阅读模式",
            onBack = onBack,
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.xs),
                ) {
                    FontSizeButton(AuroraIcons.Minus, "减小字号") { scale = (scale - 0.1f).coerceAtLeast(0.8f) }
                    Text(
                        text = "${(scale * 100).toInt()}%",
                        color = c.textMuted,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.widthIn(min = 44.dp),
                        textAlign = TextAlign.Center,
                    )
                    FontSizeButton(AuroraIcons.Add, "增大字号") { scale = (scale + 0.1f).coerceAtMost(1.8f) }
                }
            },
        )

        if (text.isBlank()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(AuroraSpacing.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text("本页无法进入阅读模式", color = c.textMuted)
            }
        } else {
            // 唯一滚动容器
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AuroraSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
            ) {
                Text(title, color = c.text, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = hostOf(url).ifBlank { url },
                    color = c.textMuted,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = text,
                    color = c.textSecondary,
                    fontSize = (15 * scale).sp,
                    lineHeight = (26 * scale).sp,
                    modifier = Modifier.padding(bottom = AuroraSpacing.xxl),
                )
            }
        }
    }
}

@Composable
private fun FontSizeButton(icon: Int, label: String, onClick: () -> Unit) {
    val c = Aurora.colors
    Box(
        modifier = Modifier
            .size(AuroraSpacing.touchTarget)
            .auroraClickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = c.textSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

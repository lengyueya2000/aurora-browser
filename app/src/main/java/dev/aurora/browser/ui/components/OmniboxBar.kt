package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

enum class SiteSecurity { SECURE, INSECURE, WARNING }

@Composable
fun OmniboxBar(
    displayUrl: String,
    security: SiteSecurity,
    onTap: () -> Unit,
    onTrailingAction: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: Int = AuroraIcons.Refresh,   // 加载中传 AuroraIcons.Close
    placeholder: String = "搜索或输入网址",
) {
    val c = Aurora.colors
    val blank = displayUrl.isBlank()
    val (identityIcon, identityDesc, identityTint) = when (security) {
        SiteSecurity.SECURE -> Triple(AuroraIcons.Lock, "连接安全", c.success)
        SiteSecurity.INSECURE -> Triple(AuroraIcons.Globe, "连接不安全", c.textMuted)
        SiteSecurity.WARNING -> Triple(AuroraIcons.ShieldCheck, "证书警告", c.warning)
    }

    Row(
        modifier = modifier
            .padding(horizontal = AuroraSpacing.m, vertical = AuroraSpacing.xs)
            // 48dp 而非原型的 44px：整行与右侧动作按钮都是可点元素，
            // 必须满足全局约束"触摸目标 ≥48dp"（44dp 会把按钮压到 44 高）。
            .height(AuroraSpacing.touchTarget)
            .clip(RoundedCornerShape(AuroraRadius.full))
            .background(c.surfaceHover)
            // mergeDescendants = true：整条地址栏合成一个无障碍节点，读屏一次读出
            // "地址栏，域名"，而不是把它拆成图标/文本/按钮三段。
            // 右侧动作按钮自身是 clickable（也是合并节点），实测嵌套的合并节点不会被
            // 祖先吸收——`onNodeWithContentDescription("刷新")` 仍解析到按钮本身并触发
            // onTrailingAction，不会被整行吞掉（见 progress.md Ruling 13）。
            .semantics(mergeDescendants = true) {
                contentDescription = if (blank) "地址栏" else "地址栏，$displayUrl"
            }
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(start = AuroraSpacing.m, end = AuroraSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(identityTint.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(identityIcon),
                contentDescription = identityDesc,
                tint = identityTint,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = if (blank) placeholder else displayUrl,
            color = if (blank) c.textDisabled else c.textSecondary,
            fontFamily = if (blank) FontFamily.Default else FontFamily.Monospace,
            fontSize = if (blank) 14.sp else 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(AuroraSpacing.touchTarget)
                .clip(CircleShape)
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTrailingAction,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(trailingIcon),
                contentDescription = if (trailingIcon == AuroraIcons.Close) "停止加载" else "刷新",
                tint = c.textMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * URL → 展示用域名。解析失败（含手输的半截地址）时原样返回，
 * 这样用户至少能看到自己敲进去的东西，而不是空白。
 */
fun hostOf(url: String?): String {
    if (url.isNullOrBlank()) return ""
    return runCatching { java.net.URI(url).host }.getOrNull() ?: url
}

/**
 * 按 scheme 判定站点安全态。本期输入只有 http/https；
 * 证书异常（WARNING）要等 M3 接入 `WebViewClient.onReceivedSslError` 才能产生。
 */
fun securityOf(url: String?): SiteSecurity =
    if (url != null && url.startsWith("https://")) SiteSecurity.SECURE else SiteSecurity.INSECURE

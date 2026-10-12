package dev.aurora.browser.ui.privacy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aurora.browser.domain.TrackerBlocklist
import dev.aurora.browser.ui.components.ScreenHeader
import dev.aurora.browser.ui.components.SettingRow
import dev.aurora.browser.ui.components.SiteSecurity
import dev.aurora.browser.ui.components.hostOf
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraSpacing

/**
 * 隐私与安全（规格 5.6）。
 *
 * 与规格的差异：规格里的「站点权限开关（相机/麦克风/定位/通知）」需要 WebView
 * `onPermissionRequest` 的逐站授权流程，属 M4；这里不摆不可用的开关，改为说明当前策略
 * （默认全部拒绝），避免出现点了没反应的控件。
 */
@Composable
fun PrivacyScreen(
    trackerCount: Int,
    blockEnabled: Boolean,
    onBlockChange: (Boolean) -> Unit,
    currentUrl: String?,
    security: SiteSecurity,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Aurora.colors
    val (securityText, securityTint, securityIcon) = when (security) {
        SiteSecurity.SECURE -> Triple("连接安全（HTTPS）", c.success, AuroraIcons.Lock)
        SiteSecurity.INSECURE -> Triple("连接不安全（明文 HTTP）", c.textMuted, AuroraIcons.Globe)
        SiteSecurity.WARNING -> Triple("证书异常，已拦截", c.warning, AuroraIcons.ShieldCheck)
    }

    // 页头留在滚动容器外：二级界面没有悬浮工具栏，返回箭头是唯一的应用内返回入口，
    // 跟着内容滚走用户就只能靠系统返回键了（原型里 .topbar 也是 flex:0 0 auto）。
    Column(modifier = modifier.fillMaxSize().background(c.bg)) {
        ScreenHeader(title = "隐私与安全", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // 拦截计数大数字
            Column(
                modifier = Modifier.fillMaxWidth().padding(AuroraSpacing.l),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AuroraSpacing.xs),
            ) {
                Text(
                    text = trackerCount.toString(),
                    color = c.success,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 44.sp,
                )
                Text("个追踪器已拦截（本页）", color = c.textMuted, style = MaterialTheme.typography.labelMedium)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
            ) {
                Box(
                    modifier = Modifier.size(26.dp).clip(CircleShape).background(securityTint.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(securityIcon),
                        contentDescription = null,
                        tint = securityTint,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(securityText, color = c.text)
                    Text(
                        text = currentUrl?.let { hostOf(it) }?.takeIf { it.isNotBlank() } ?: "新标签页",
                        color = c.textMuted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            SettingRow(
                title = "追踪器拦截",
                subtitle = "命中 ${TrackerBlocklist.entries.size} 条主机名规则",
                onClick = { onBlockChange(!blockEnabled) },
                toggleState = blockEnabled,
                trailing = {
                    Switch(
                        checked = blockEnabled,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = c.onBrand,
                            checkedTrackColor = c.brand,
                            uncheckedThumbColor = c.textMuted,
                            uncheckedTrackColor = c.surfaceHover,
                            uncheckedBorderColor = c.border,
                        ),
                    )
                },
            )

            SettingRow(
                title = "站点权限",
                subtitle = "相机 / 麦克风 / 定位 / 通知：当前一律拒绝（逐站授权见 M4）",
                value = "全部拒绝",
            )
            SettingRow(
                title = "第三方 Cookie",
                subtitle = "跨站请求不携带 Cookie",
                value = "已阻止",
            )

            Column(modifier = Modifier.padding(bottom = AuroraSpacing.xxl)) {}
        }
    }
}

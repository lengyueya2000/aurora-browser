package dev.aurora.browser.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.aurora.browser.domain.SearchEngine
import dev.aurora.browser.ui.components.ScreenHeader
import dev.aurora.browser.ui.components.SegmentedControl
import dev.aurora.browser.ui.components.SettingRow
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraSpacing

/** 设置（规格 5.11）：外观 / 隐私与安全 / 搜索 / 下载 / 无障碍 / 关于。 */
@Composable
fun SettingsScreen(
    engine: SearchEngine,
    onEngineChange: (SearchEngine) -> Unit,
    showFeed: Boolean,
    onShowFeedChange: (Boolean) -> Unit,
    blockTrackers: Boolean,
    onBlockTrackersChange: (Boolean) -> Unit,
    onOpenDownloads: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    versionName: String = "1.1",
) {
    val c = Aurora.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(c.bg)
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = "设置", onBack = onBack)

        SettingGroup("外观")
        SettingRow(
            title = "深色模式",
            subtitle = "Aurora 只提供深色主题，跟随系统字号缩放",
            value = "已开启",
        )
        SwitchRow(
            title = "主页信息流",
            subtitle = "在新标签页显示「为你推荐」",
            checked = showFeed,
            onCheckedChange = onShowFeedChange,
        )

        SettingGroup("隐私与安全")
        SwitchRow(
            title = "追踪器拦截",
            subtitle = "按主机名拦截常见统计与广告域名",
            checked = blockTrackers,
            onCheckedChange = onBlockTrackersChange,
        )

        SettingGroup("搜索")
        SettingRow(
            title = "搜索引擎",
            subtitle = "地址栏输入非网址内容时使用",
        )
        SegmentedControl(
            options = SearchEngine.entries.map { it to it.label },
            selected = engine,
            onSelect = onEngineChange,
            modifier = Modifier
                .padding(horizontal = AuroraSpacing.l, vertical = AuroraSpacing.s)
                .semantics { contentDescription = "搜索引擎" },
        )

        SettingGroup("下载")
        SettingRow(
            title = "下载管理",
            subtitle = "查看进行中与已完成的下载",
            icon = AuroraIcons.Download,
            onClick = onOpenDownloads,
        )

        SettingGroup("无障碍")
        SettingRow(title = "字号缩放", subtitle = "跟随系统设置，正文在 200% 下不截断", value = "跟随系统")
        SettingRow(title = "移除动画", subtitle = "跟随系统「移除动画」开关", value = "跟随系统")

        SettingGroup("关于")
        SettingRow(title = "版本", value = versionName)
        SettingRow(title = "开源协议", value = "MIT")
        SettingRow(title = "项目主页", subtitle = "github.com/lengyueya2000/aurora-browser")

        Column(modifier = Modifier.padding(bottom = AuroraSpacing.xxl)) {}
    }
}

@Composable
private fun SettingGroup(title: String) {
    Text(
        text = title,
        color = Aurora.colors.textMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(
            start = AuroraSpacing.l, end = AuroraSpacing.l,
            top = AuroraSpacing.xl, bottom = AuroraSpacing.xs,
        ),
    )
}

/** 整行可点切换：整行是命中区，Switch 只做展示（避免双重触发）。 */
@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val c = Aurora.colors
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked = checked,
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
}

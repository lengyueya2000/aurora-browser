package dev.aurora.browser.ui.browser

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.components.AuroraBottomSheet
import dev.aurora.browser.ui.components.SettingRow
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraSpacing

/** 抽屉里可选列表的高度上限：超出后滚动，避免在矮屏上顶到状态栏。 */
private val SheetListMaxHeight = 420.dp

/**
 * 视频源列表抽屉：下载与投屏共用同一份「从页面里提取出来的媒体源」。
 *
 * 流式地址（`blob:` / MSE）单独标出来并**不可点**：给一个点了没反应的按钮，
 * 用户只会以为应用坏了；写明「不支持」才是诚实的降级。
 */
@Composable
fun VideoSourceSheet(
    title: String,
    sources: List<VideoSource>,
    icon: Int,
    onPick: (VideoSource) -> Unit,
    onDismiss: () -> Unit,
) {
    AuroraBottomSheet(onDismiss = onDismiss) {
        SheetTitle(title)
        Column(
            modifier = Modifier
                .heightIn(max = SheetListMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            sources.forEach { source ->
                if (source.isStream) {
                    SettingRow(title = source.label, subtitle = source.subtitle, value = "不支持")
                } else {
                    SettingRow(
                        title = source.label,
                        subtitle = source.subtitle,
                        icon = icon,
                        onClick = { onPick(source) },
                    )
                }
            }
        }
    }
}

/**
 * 投屏入口抽屉。
 *
 * 两条路径分开是因为它们在 Android 上根本是两件事：
 * - 屏幕镜像由系统实现，应用只能把用户送到系统的投屏界面；
 * - 「投屏播放视频」是把视频地址交给能播它的应用（各类投屏播放器都注册了 video 通配 MIME）。
 */
@Composable
fun CastOptionsSheet(
    onMirror: () -> Unit,
    onCastVideo: () -> Unit,
    onDismiss: () -> Unit,
) {
    AuroraBottomSheet(onDismiss = onDismiss) {
        SheetTitle("投屏")
        SettingRow(
            title = "屏幕镜像",
            subtitle = "用系统无线投屏把整个屏幕投到电视",
            icon = AuroraIcons.Desktop,
            onClick = onMirror,
        )
        SettingRow(
            title = "投屏播放视频",
            subtitle = "把当前页面里的视频交给投屏播放器播放",
            icon = AuroraIcons.Share,
            onClick = onCastVideo,
        )
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        color = Aurora.colors.textMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(bottom = AuroraSpacing.s),
    )
}

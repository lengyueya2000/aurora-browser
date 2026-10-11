package dev.aurora.browser.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

val LocalAuroraColors = staticCompositionLocalOf { DarkAuroraColors }

@Composable
fun AuroraTheme(
    colors: AuroraColors = DarkAuroraColors,
    content: @Composable () -> Unit,
) {
    // M1/M2 深色优先：无论系统主题如何都用深色令牌。
    val material = darkColorScheme(
        primary = colors.brand,
        onPrimary = colors.onBrand,
        background = colors.bg,
        onBackground = colors.text,
        surface = colors.surface,
        onSurface = colors.text,
        surfaceVariant = colors.surfaceHover,
        onSurfaceVariant = colors.textMuted,
        outline = colors.border,
        error = colors.danger,
    )
    CompositionLocalProvider(LocalAuroraColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

/** 组件内读语义色的统一入口：`Aurora.colors.brand`。 */
object Aurora {
    val colors: AuroraColors
        @Composable get() = LocalAuroraColors.current
}

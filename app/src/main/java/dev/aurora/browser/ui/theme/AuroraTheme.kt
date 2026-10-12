package dev.aurora.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.aurora.browser.domain.ThemeMode

val LocalAuroraColors = staticCompositionLocalOf { DarkAuroraColors }

/**
 * @param dark 用深色令牌还是浅色令牌。默认深色：既有测试与预览都是在这个前提下写的，
 *   把默认翻成浅色会让它们悄悄改验另一套配色。
 */
@Composable
fun AuroraTheme(
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkAuroraColors else LightAuroraColors
    val material = if (dark) {
        darkColorScheme(
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
    } else {
        lightColorScheme(
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
    }
    CompositionLocalProvider(LocalAuroraColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

/**
 * 按用户偏好选配色。
 *
 * 只有 [ThemeMode.SYSTEM] 才去问系统，而 `isSystemInDarkTheme()` 是组合函数、
 * 只能在组合里读——`MainActivity` 还要在组合之外给系统栏图标算颜色，所以那边
 * 走 [AuroraTheme] 的 `dark` 参数，两边共用 [ThemeMode.isDark] 这一个纯函数。
 */
@Composable
fun AuroraTheme(mode: ThemeMode, content: @Composable () -> Unit) =
    AuroraTheme(dark = mode.isDark(isSystemInDarkTheme()), content = content)

/** 组件内读语义色的统一入口：`Aurora.colors.brand`。 */
object Aurora {
    val colors: AuroraColors
        @Composable get() = LocalAuroraColors.current
}

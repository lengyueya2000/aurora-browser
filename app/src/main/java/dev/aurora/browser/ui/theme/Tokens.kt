package dev.aurora.browser.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 唯一允许出现十六进制颜色字面量的文件。
 * 值取自 design-system/aurora-browser/MASTER.md，改动需同步该文件。
 */
data class AuroraColors(
    val bg: Color, val bgElevated: Color, val surface: Color, val surfaceHover: Color,
    val border: Color, val divider: Color,
    val text: Color, val textSecondary: Color, val textMuted: Color, val textDisabled: Color,
    val brand: Color, val brandStrong: Color, val onBrand: Color,
    val success: Color, val warning: Color, val danger: Color,
    val scrim: Color, val glass: Color, val glassBorder: Color,
)

val DarkAuroraColors = AuroraColors(
    bg = Color(0xFF0F172A), bgElevated = Color(0xFF131C31),
    surface = Color(0xFF1B2336), surfaceHover = Color(0xFF272F42),
    border = Color(0xFF334155), divider = Color(0x2994A3B8),   // 16% of #94A3B8
    text = Color(0xFFF8FAFC), textSecondary = Color(0xFFCBD5E1),
    textMuted = Color(0xFF94A3B8), textDisabled = Color(0xFF64748B),
    brand = Color(0xFF38BDF8), brandStrong = Color(0xFF0EA5E9), onBrand = Color(0xFF0F172A),
    success = Color(0xFF22C55E), warning = Color(0xFFF59E0B), danger = Color(0xFFEF4444),
    scrim = Color(0xB8020617),                                  // 72% of #020617
    glass = Color(0xB8131C31),                                  // 72% of #131C31
    glassBorder = Color(0x2494A3B8),                            // 14% of #94A3B8
)

object AuroraSpacing {
    val xs: Dp = 4.dp; val s: Dp = 8.dp; val m: Dp = 12.dp; val l: Dp = 16.dp
    val xl: Dp = 24.dp; val xxl: Dp = 32.dp; val xxxl: Dp = 48.dp
    /** Android 最小触摸目标。所有可点组件不得小于此值。 */
    val touchTarget: Dp = 48.dp
    /** 相邻可点目标的最小间距。 */
    val touchGap: Dp = 8.dp
}

object AuroraRadius {
    val xs: Dp = 6.dp; val s: Dp = 10.dp; val m: Dp = 14.dp
    val l: Dp = 20.dp; val xl: Dp = 28.dp; val full: Dp = 999.dp
}

object AuroraMotion {
    const val FAST = 120
    const val BASE = 200
    const val SLOW = 320
    val standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

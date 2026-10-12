package dev.aurora.browser.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 唯一允许出现十六进制颜色字面量的文件。
 * 深色值取自 design-system/aurora-browser/MASTER.md，浅色值取自
 * docs/superpowers/specs/2026-10-11-ui-theme-directions-design.md（方向 A「晨雾」）。
 * 改动需同步这两份文件。
 */
data class AuroraColors(
    val bg: Color, val bgElevated: Color, val surface: Color, val surfaceHover: Color,
    val border: Color, val divider: Color,
    val text: Color, val textSecondary: Color, val textMuted: Color, val textDisabled: Color,
    val brand: Color, val brandStrong: Color, val onBrand: Color,
    val success: Color, val warning: Color, val danger: Color,
    val scrim: Color, val glass: Color, val glassBorder: Color,
)

/** 1.15 之前的深色令牌。1.16 起作为「深色」选项保留，不再是唯一一套。 */
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

/**
 * 浅色令牌：方向 A「晨雾」。纸白底 + 松绿品牌色，1.16 起的默认主题。
 *
 * 与原型有两处差异，都是为了过 WCAG：
 * - `warning` 从 `#A9691A` 压到 `#A05F14`（白底 4.45 → 5.07）。原型那版在纸白底上
 *   差 0.05 才够 AA，正文色不该踩线。
 * - `scrim` 沿用深色令牌而不是原型那个 42% 的值：播放器遮罩属于固定深色的那一层，
 *   见 [OnVideoColors]。
 */
val LightAuroraColors = AuroraColors(
    bg = Color(0xFFFBF9F6), bgElevated = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF), surfaceHover = Color(0xFFF0ECE5),
    border = Color(0xFFDED8CE), divider = Color(0x171C1B19),    // 9% of #1C1B19
    text = Color(0xFF1B1A17), textSecondary = Color(0xFF3E3B35),
    textMuted = Color(0xFF6A655C), textDisabled = Color(0xFF928B80),
    brand = Color(0xFF0F5C4A), brandStrong = Color(0xFF0A4335), onBrand = Color(0xFFFFFFFF),
    success = Color(0xFF2E7D52), warning = Color(0xFFA05F14), danger = Color(0xFFB3402E),
    scrim = Color(0x6B1C1B19),                                  // 42% of #1C1B19
    glass = Color(0xCCFFFFFF),                                  // 80% of #FFFFFF
    glassBorder = Color(0x141C1B19),                            // 8% of #1C1B19
)

/**
 * 播放器控制层的配色：**固定深色，不随应用主题变化**。
 *
 * 为什么这一层不跟着主题走：控制层浮在视频画面上，而画面本身既不是浅色也不是深色。
 * 浅色主题下把它翻成浅底深字，碰到明亮画面就糊成一片（原型里 A「晨雾」的播放器
 * 同样是深底浅字，见 `prototype/themes.html#theme=a` 屏 14）。
 *
 * 直接复用深色令牌而不是另抄一份十六进制：这一层要的正是「和深色令牌一样」，
 * 抄成两份只会各自漂移。所有直接压在画面上的控件（标题、胶囊、进度条、手势提示、
 * 下载状态、控制面板）都从这里取色，其余界面照旧读 `Aurora.colors`。
 */
val OnVideoColors: AuroraColors = DarkAuroraColors

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

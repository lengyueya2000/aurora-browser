package dev.aurora.browser.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.pow

class TokensTest {
    @Test fun semanticColorsMatchSpec() {
        val c = DarkAuroraColors
        assertThat(
            listOf(
                c.bg, c.bgElevated, c.surface, c.surfaceHover, c.border, c.divider,
                c.text, c.textSecondary, c.textMuted, c.textDisabled,
                c.brand, c.brandStrong, c.onBrand,
                c.success, c.warning, c.danger,
                c.scrim, c.glass, c.glassBorder,
            )
        ).containsExactly(
            Color(0xFF0F172A), Color(0xFF131C31), Color(0xFF1B2336), Color(0xFF272F42),
            Color(0xFF334155), Color(0x2994A3B8),
            Color(0xFFF8FAFC), Color(0xFFCBD5E1), Color(0xFF94A3B8), Color(0xFF64748B),
            Color(0xFF38BDF8), Color(0xFF0EA5E9), Color(0xFF0F172A),
            Color(0xFF22C55E), Color(0xFFF59E0B), Color(0xFFEF4444),
            Color(0xB8020617), Color(0xB8131C31), Color(0x2494A3B8),
        ).inOrder()
    }

    @Test fun spacingFollowsFourEightRhythm() {
        assertThat(
            listOf(
                AuroraSpacing.xs.value, AuroraSpacing.s.value, AuroraSpacing.m.value,
                AuroraSpacing.l.value, AuroraSpacing.xl.value, AuroraSpacing.xxl.value,
                AuroraSpacing.xxxl.value,
            )
        ).containsExactly(4f, 8f, 12f, 16f, 24f, 32f, 48f).inOrder()
    }

    @Test fun radiusScaleMatchesSpec() {
        assertThat(
            listOf(
                AuroraRadius.xs.value, AuroraRadius.s.value, AuroraRadius.m.value,
                AuroraRadius.l.value, AuroraRadius.xl.value, AuroraRadius.full.value,
            )
        ).containsExactly(6f, 10f, 14f, 20f, 28f, 999f).inOrder()
    }

    @Test fun motionDurationsMatchSpec() {
        assertThat(listOf(AuroraMotion.FAST, AuroraMotion.BASE, AuroraMotion.SLOW))
            .containsExactly(120, 200, 320).inOrder()
    }

    @Test fun touchTargetMinimumIs48dp() {
        assertThat(AuroraSpacing.touchTarget.value).isAtLeast(48f)
        assertThat(AuroraSpacing.touchGap.value).isAtLeast(8f)
    }

    // ---------- 浅色令牌（方向 A「晨雾」） ----------

    @Test fun lightSemanticColorsMatchSpec() {
        val c = LightAuroraColors
        assertThat(
            listOf(
                c.bg, c.bgElevated, c.surface, c.surfaceHover, c.border, c.divider,
                c.text, c.textSecondary, c.textMuted, c.textDisabled,
                c.brand, c.brandStrong, c.onBrand,
                c.success, c.warning, c.danger,
                c.scrim, c.glass, c.glassBorder,
            )
        ).containsExactly(
            Color(0xFFFBF9F6), Color(0xFFFFFFFF), Color(0xFFFFFFFF), Color(0xFFF0ECE5),
            Color(0xFFDED8CE), Color(0x171C1B19),
            Color(0xFF1B1A17), Color(0xFF3E3B35), Color(0xFF6A655C), Color(0xFF928B80),
            Color(0xFF0F5C4A), Color(0xFF0A4335), Color(0xFFFFFFFF),
            Color(0xFF2E7D52), Color(0xFFA05F14), Color(0xFFB3402E),
            Color(0x6B1C1B19), Color(0xCCFFFFFF), Color(0x141C1B19),
        ).inOrder()
    }

    /**
     * 浅色令牌的正文与语义色必须过 WCAG AA（4.5:1）。
     *
     * 这条测试存在的意义：色值是可以随手改的，而「白底上那行灰字还看不看得清」不写在
     * 代码里。原型里 warning 那一档就差 0.05 没过线，落地时才压深了一点点——不测的话，
     * 下次谁把它改回原型的值也不会有人发现。
     */
    @Test fun lightTextPairsClearWcagAa() {
        val c = LightAuroraColors
        val pairs = listOf(
            "正文 / 页面底色" to (c.text to c.bg),
            "正文 / 卡片" to (c.text to c.surface),
            "次级文字 / 卡片" to (c.textSecondary to c.surface),
            "弱化文字 / 卡片" to (c.textMuted to c.surface),
            "弱化文字 / 页面底色" to (c.textMuted to c.bg),
            "品牌色 / 卡片" to (c.brand to c.surface),
            "品牌色 / 页面底色" to (c.brand to c.bg),
            "品牌底上的文字" to (c.onBrand to c.brand),
            "成功 / 卡片" to (c.success to c.surface),
            "警告 / 卡片" to (c.warning to c.surface),
            "危险 / 卡片" to (c.danger to c.surface),
        )
        pairs.forEach { (name, colors) ->
            val ratio = contrastRatio(colors.first, colors.second)
            assertThat(ratio).isAtLeast(4.5f)
            println("%-16s %.2f:1".format(name, ratio))
        }
    }

    /**
     * 禁用态文字不进上面的表：WCAG 1.4.3 明确把「未激活的控件」排除在外，
     * 深色那套本身也只有 3.75（页面底）/ 3.29（卡片）。这里只守一条底线——
     * 不能掉到「基本看不见」，浅色这套 3.21 / 3.37。
     */
    @Test fun disabledTextStaysReadableEnoughInBothPalettes() {
        listOf("深色" to DarkAuroraColors, "浅色" to LightAuroraColors).forEach { (name, c) ->
            assertThat(contrastRatio(c.textDisabled, c.bg)).isAtLeast(3f)
            assertThat(contrastRatio(c.textDisabled, c.surface)).isAtLeast(3f)
            println("$name 禁用态 / 页面底色 %.2f:1".format(contrastRatio(c.textDisabled, c.bg)))
        }
    }

    /**
     * 播放器控制层必须永远是深底浅字。
     *
     * 它压在视频画面上，而画面既不是浅色也不是深色——浅色主题下若把它翻成浅底深字，
     * 碰到明亮画面就糊成一片。原型里 A「晨雾」的播放器也是深底浅字。
     */
    @Test fun onVideoLayerStaysDarkWithLightText() {
        assertThat(relativeLuminance(OnVideoColors.bg)).isLessThan(0.2f)
        assertThat(relativeLuminance(OnVideoColors.surface)).isLessThan(0.2f)
        assertThat(relativeLuminance(OnVideoColors.text)).isGreaterThan(0.5f)
        assertThat(relativeLuminance(OnVideoColors.textSecondary)).isGreaterThan(0.4f)
        assertThat(relativeLuminance(OnVideoColors.scrim)).isLessThan(0.2f)
        // 画面上的文字对遮罩之后的画面仍要够清楚（按最亮的纯白画面估，是最坏情况）。
        val overBrightVideo = OnVideoColors.scrim.compositeOver(Color.White)
        assertThat(contrastRatio(OnVideoColors.text, overBrightVideo)).isAtLeast(4.5f)
        assertThat(contrastRatio(OnVideoColors.textSecondary, overBrightVideo)).isAtLeast(4.5f)
    }
}

/** WCAG 相对亮度（sRGB）。自己算而不是用框架的：这里要断言的是 WCAG 的定义。 */
private fun relativeLuminance(color: Color): Float {
    fun channel(value: Float): Float =
        if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

/** WCAG 对比度，1:1 到 21:1。 */
private fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** 把半透明色叠在不透明底色上，用于估算遮罩之后的实际画面。 */
private fun Color.compositeOver(background: Color): Color = Color(
    red = red * alpha + background.red * (1f - alpha),
    green = green * alpha + background.green * (1f - alpha),
    blue = blue * alpha + background.blue * (1f - alpha),
    alpha = 1f,
)

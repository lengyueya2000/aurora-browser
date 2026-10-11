package dev.aurora.browser.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

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
}

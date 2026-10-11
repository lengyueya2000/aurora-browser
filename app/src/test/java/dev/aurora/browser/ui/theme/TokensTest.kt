package dev.aurora.browser.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TokensTest {
    @Test fun semanticColorsMatchSpec() {
        val c = DarkAuroraColors
        assertThat(c.bg).isEqualTo(Color(0xFF0F172A))
        assertThat(c.surface).isEqualTo(Color(0xFF1B2336))
        assertThat(c.brand).isEqualTo(Color(0xFF38BDF8))
        assertThat(c.success).isEqualTo(Color(0xFF22C55E))
        assertThat(c.danger).isEqualTo(Color(0xFFEF4444))
        assertThat(c.onBrand).isEqualTo(Color(0xFF0F172A))
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

    @Test fun touchTargetMinimumIs48dp() {
        assertThat(AuroraSpacing.touchTarget.value).isAtLeast(48f)
    }
}

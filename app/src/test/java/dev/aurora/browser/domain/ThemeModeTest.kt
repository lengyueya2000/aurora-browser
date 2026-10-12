package dev.aurora.browser.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeModeTest {

    @Test fun lightAndDarkIgnoreTheSystem() {
        assertThat(ThemeMode.LIGHT.isDark(systemDark = true)).isFalse()
        assertThat(ThemeMode.LIGHT.isDark(systemDark = false)).isFalse()
        assertThat(ThemeMode.DARK.isDark(systemDark = true)).isTrue()
        assertThat(ThemeMode.DARK.isDark(systemDark = false)).isTrue()
    }

    @Test fun systemFollowsTheSystem() {
        assertThat(ThemeMode.SYSTEM.isDark(systemDark = true)).isTrue()
        assertThat(ThemeMode.SYSTEM.isDark(systemDark = false)).isFalse()
    }

    @Test fun defaultIsTheLightDesign() {
        // 1.16 的主设计是浅色「晨雾」：装完第一次打开就该看到它，
        // 而不是在深色手机上看到旧界面、还得进设置里找。
        assertThat(ThemeMode.Default).isEqualTo(ThemeMode.LIGHT)
    }

    @Test fun fromNameRoundTripsAndFallsBack() {
        ThemeMode.entries.forEach { assertThat(ThemeMode.fromName(it.name)).isEqualTo(it) }
        // 旧配置、手改过的 prefs、别家的备份都可能塞进认不出的值，不能让它崩在启动路径上。
        assertThat(ThemeMode.fromName(null)).isEqualTo(ThemeMode.Default)
        assertThat(ThemeMode.fromName("NOPE")).isEqualTo(ThemeMode.Default)
    }

    @Test fun everyModeHasALabel() {
        ThemeMode.entries.forEach { assertThat(it.label).isNotEmpty() }
    }
}

package dev.aurora.browser.ui.icons

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuroraIconsTest {
    @Test fun everyRequiredIconResolvesToADrawable() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val names = listOf(
            "arrow-left", "arrow-right", "house-simple", "squares-four", "dots-three",
            "magnifying-glass", "x", "plus", "shield-check", "lock-simple", "star",
            "clock-counter-clockwise", "download-simple", "book-open-text", "eye-slash",
            "share-network", "gear-six", "puzzle-piece", "microphone", "qr-code",
            "caret-right", "minus", "text-aa", "moon", "speaker-high", "globe",
            "trash-simple", "clock", "desktop", "translate", "image", "sparkle",
            "arrow-clockwise", "clipboard-text",
        )
        for (name in names) {
            val res = AuroraIcons.of(name)
            assertThat(res).isNotEqualTo(0)
            assertThat(ctx.getDrawable(res)).isNotNull()
        }
    }

    @Test fun unknownIconNameFailsLoudly() {
        val error = runCatching { AuroraIcons.of("no-such-icon") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}

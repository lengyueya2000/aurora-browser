package dev.aurora.browser.ui.icons

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuroraIconsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    /**
     * 遍历 `AuroraIcons.names`（即 `byName` 的全部键）而不是在测试里再抄一份名单：
     * 抄一份的话，往 `byName` 里加一个指向不存在 drawable 的名字，测试照样绿。
     */
    @Test fun everyRegisteredNameResolvesToADrawable() {
        assertThat(AuroraIcons.names).isNotEmpty()
        for (name in AuroraIcons.names) {
            val res = AuroraIcons.of(name)
            assertThat(res).isNotEqualTo(0)
            assertThat(ctx.getDrawable(res)).isNotNull()
        }
    }

    /** 抽样锁死关键图标，避免名单被整体清空时上面那条测试「空集通过」。 */
    @Test fun essentialIconsAreStillRegistered() {
        assertThat(AuroraIcons.names).containsAtLeast(
            "arrow-left", "magnifying-glass", "star", "eye-slash", "gear-six",
        )
    }

    @Test fun unknownIconNameFailsLoudly() {
        val error = runCatching { AuroraIcons.of("no-such-icon") }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}

package dev.aurora.browser.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * 冷启动底色（`windowBackground`）必须和令牌里的 `bg` 一致。
 *
 * 为什么值得一条测试：系统画启动窗口时 Compose 还没跑起来，那个颜色只能写死在 XML 里
 * （`res/values/colors.xml` 与 `res/values-night/colors.xml`），于是令牌和资源之间多了一处
 * 手工同步。改主题色时忘了改这里，表现是「点开应用先闪一下别的颜色」——很轻微、很容易被
 * 当成模拟器/机型问题放过去，但每次冷启动都会发生。
 *
 * 这里直接读源文件比对十六进制，不走资源系统：Robolectric 的资源配置会按 `night` 限定符
 * 只解析出一份，反而比不出来「两份都对」。
 */
class WindowBackgroundTest {

    private fun readColor(name: String, path: String): String {
        val file = File(path)
        assertThat(file.exists()).isTrue()
        // 只匹配 <color name="xxx">#RRGGBB</color>，注释里的示例色不会被当成值（它们
        // 不在 <color> 标签里）。
        val regex = Regex("""<color name="$name">\s*(#[0-9A-Fa-f]{6,8})\s*</color>""")
        val match = regex.find(file.readText())
        assertThat(match).isNotNull()
        return match!!.groupValues[1]
    }

    /** `#AARRGGBB`（Compose）与 `#RRGGBB`（资源）要能对上：只比后六位。 */
    private fun rgbOf(argb: String): String = argb.takeLast(6).uppercase()

    @Test fun lightWindowBackgroundMatchesLightToken() {
        assertThat(rgbOf(readColor("aurora_window_bg", "src/main/res/values/colors.xml")))
            .isEqualTo(rgbOf(LightAuroraColors.bg.toHex()))
    }

    @Test fun darkWindowBackgroundMatchesDarkToken() {
        assertThat(rgbOf(readColor("aurora_window_bg", "src/main/res/values-night/colors.xml")))
            .isEqualTo(rgbOf(DarkAuroraColors.bg.toHex()))
    }

    /** 顺带钉住「两份资源确实不一样」——不然一份抄错了也测不出来。 */
    @Test fun nightAndDayBackgroundsDiffer() {
        val day = readColor("aurora_window_bg", "src/main/res/values/colors.xml")
        val night = readColor("aurora_window_bg", "src/main/res/values-night/colors.xml")
        assertThat(day).isNotEqualTo(night)
    }

    private fun Color.toHex(): String = String.format("#%08X", toArgb())
}

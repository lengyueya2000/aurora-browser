package dev.aurora.browser

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.aurora.browser.ui.browser.BrowserScreen
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 应用只有深色一套令牌，系统栏必须固定成「深底浅字」。
        // 用默认的 auto 会在系统为浅色时把状态栏图标刷成深色，落在深色背景上等于隐形。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            AuroraTheme {
                // color = colors.bg：Surface 默认取 colorScheme.surface，
                // 与规格的页面底色 bg 不是同一个值，必须显式指定。
                Surface(modifier = Modifier.fillMaxSize(), color = Aurora.colors.bg) {
                    // 状态栏/手势条避让在 BrowserScreen 内部统一处理
                    BrowserScreen()
                }
            }
        }
    }
}

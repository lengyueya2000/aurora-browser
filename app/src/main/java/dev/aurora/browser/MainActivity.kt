package dev.aurora.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aurora.browser.ui.browser.BrowserScreen
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraRadius
import dev.aurora.browser.ui.theme.AuroraSpacing
import dev.aurora.browser.ui.theme.AuroraTheme
import dev.aurora.browser.ui.theme.DarkAuroraColors
import dev.aurora.browser.ui.theme.LightAuroraColors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题偏好放在 Application 容器里：设置页写它、这里读它，必须是同一份 StateFlow，
        // 否则切完主题要等下次启动才生效。
        val store = (application as AuroraApp).store
        setContent {
            val mode by store.themeMode.collectAsState()
            val dark = mode.isDark(isSystemInDarkTheme())

            // 系统栏图标必须跟着令牌走，不能固定成一种：浅色令牌下状态栏是近白底，
            // 沿用「深底浅字」会把图标刷成和背景一个色，等于隐形。
            // 放在 LaunchedEffect 里重复调用——偏好一变就重新应用一次。
            // 窗口底色也跟着令牌走。启动那一帧（系统画的启动窗口）用的是主题里的
            // `windowBackground`，只能跟系统的明暗走；用户在设置里**强制**的主题与系统
            // 相反时，那一下会闪一个不对的颜色。这里在组合起来之后补正，之后的重绘
            // （转屏、从后台回来）就用对的了。见 res/values/themes.xml 的注释。
            //
            // 颜色直接按 `dark` 取令牌，**不能用 `Aurora.colors`**：这里还在 `AuroraTheme`
            // 之外，那个 CompositionLocal 给的是它的默认值（深色），浅色主题下会把窗口
            // 底色设成深色——正好是这段代码要修的那个 bug 的镜像。
            val windowBg = (if (dark) DarkAuroraColors else LightAuroraColors).bg
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle(dark),
                    navigationBarStyle = systemBarStyle(dark),
                )
                window.setBackgroundDrawable(ColorDrawable(windowBg.toArgb()))
            }

            AuroraTheme(dark = dark) {
                CrashReportDialog()
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

/** 透明系统栏 + 图标颜色：深色令牌配浅色图标，浅色令牌配深色图标。 */
private fun systemBarStyle(dark: Boolean): SystemBarStyle =
    if (dark) {
        SystemBarStyle.dark(Color.TRANSPARENT)
    } else {
        // light(scrim, darkScrim)：两个都给透明，这里要的只是「图标用深色」这个标记。
        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    }

/**
 * 上次崩溃了就弹出来给用户看一眼，可以直接复制。
 *
 * 真机（各家 ROM）上的闪退在模拟器上往往复现不了，没有堆栈就只能靠猜，猜错一轮就是一次发版。
 * 用户把这段文本发过来，比任何文字描述都管用。
 */
@Composable
private fun CrashReportDialog() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(CrashLog.peek(context)) }
    val body = text ?: return

    AlertDialog(
        onDismissRequest = { CrashLog.clear(context); text = null },
        title = { Text("上次崩溃了", color = Aurora.colors.text) },
        text = {
            Column {
                Text(
                    "把下面的内容复制发给开发者，能直接定位问题。",
                    color = Aurora.colors.textSecondary,
                    fontSize = 13.sp,
                )
                Text(
                    text = body,
                    color = Aurora.colors.text,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .padding(top = AuroraSpacing.s)
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .clip(RoundedCornerShape(AuroraRadius.s))
                        .background(Aurora.colors.surface)
                        .verticalScroll(rememberScrollState())
                        .padding(AuroraSpacing.s),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { copyToClipboard(context, body) }) { Text("复制") }
        },
        dismissButton = {
            TextButton(onClick = { CrashLog.clear(context); text = null }) { Text("关闭") }
        },
    )
}

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("aurora-crash", text))
    }
}

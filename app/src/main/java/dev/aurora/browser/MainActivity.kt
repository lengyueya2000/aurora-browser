package dev.aurora.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aurora.browser.ui.browser.BrowserScreen
import dev.aurora.browser.ui.theme.Aurora
import dev.aurora.browser.ui.theme.AuroraRadius
import dev.aurora.browser.ui.theme.AuroraSpacing
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

package dev.aurora.browser.media

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 全屏播放时右上角的时间与电量。
 *
 * 输入是 `visible`：由控制层的显隐状态驱动（闲置 3.5 秒自动收起），所以这块信息
 * 也跟着一起消失——不需要自己计时，也不会和控制层的收起时机打架。
 *
 * 为什么不用系统的状态栏：全屏时系统状态栏是隐藏的（沉浸播放），用户想知道
 * 「现在几点 / 还有多少电」只能退出播放器，这块信息就是补这个缺口。
 */
@Composable
internal fun PlayerStatusOverlay(visible: Boolean) {
    if (!visible) return
    val context = LocalContext.current
    var clock by remember { mutableStateOf(currentClock()) }
    var battery by remember { mutableStateOf(readBattery(context)) }

    // 每分钟刷一次时钟。电量靠系统广播更新，不轮询。
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000)
            clock = currentClock()
        }
    }
    DisposableEffect(context) {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                battery = batteryPercent(intent)
            }
        }
        // ACTION_BATTERY_CHANGED 是 sticky 广播，注册时立刻能拿到当前电量。
        ContextCompat.registerReceiver(
            context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0x99000000)) // 半透明黑底，亮画面上也看得清
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = clock,
            color = Color.White,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        if (battery >= 0) {
            Text(
                text = "  $battery%",
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun currentClock(): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())

private fun readBattery(context: Context): Int {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return -1
    return batteryPercent(intent)
}

/** 从电池广播里算百分比；拿不到就返回 -1（那就只显示时间）。 */
private fun batteryPercent(intent: Intent): Int {
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) return -1
    return ((level * 100) / scale).coerceIn(0, 100)
}

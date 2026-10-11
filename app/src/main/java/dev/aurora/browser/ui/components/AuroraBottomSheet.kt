package dev.aurora.browser.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.theme.*

/**
 * 底部抽屉。遮罩本身承载「关闭面板」语义，点击关闭。
 * 上沿圆角 lg，顶部 34×4dp 拖拽把手。
 */
@Composable
fun AuroraBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Aurora.colors
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(c.scrim)
                .semantics { contentDescription = "关闭面板" }
                .clickable(
                    role = Role.Button,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 吞掉面板内的点击：遮罩是全屏可点的，面板若不吃事件，点在把手或留白上
                // 会穿透到遮罩，把面板误关掉。detectTapGestures 只消费事件、不产生语义，
                // 因此不会给面板加上一个多余的「按钮」角色。
                // 必须放在 .clip 之前：clip 会建立 graphics layer，而位于该 layer 内部的
                // pointerInput 在 Robolectric 下命不中（面板收不到事件，修复失效）。
                .pointerInput(Unit) { detectTapGestures { } }
                .clip(RoundedCornerShape(topStart = AuroraRadius.l, topEnd = AuroraRadius.l))
                .background(c.bgElevated)
                .padding(bottom = AuroraSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .padding(top = AuroraSpacing.s, bottom = AuroraSpacing.m)
                    .size(width = 34.dp, height = 4.dp)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .background(c.border)
            )
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = AuroraSpacing.l), content = content)
        }
    }
}

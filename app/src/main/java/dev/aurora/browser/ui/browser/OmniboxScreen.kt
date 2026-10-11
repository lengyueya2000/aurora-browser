package dev.aurora.browser.ui.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import dev.aurora.browser.ui.icons.AuroraIcons
import dev.aurora.browser.ui.theme.*

@Composable
fun OmniboxScreen(
    query: String,
    suggestions: List<String>,
    onQueryChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Aurora.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    // Page/WebView 现在常驻在下方，本覆盖层必须吞掉空白区域的点击，否则会穿透到 WebView。
    // 不透明 background 只是绘制，不是命中测试边界——只有消费型指针修饰符才能挡住事件。
    // 用 detectTapGestures（而非 clickable）：它只消费事件、不添加 Button 语义角色，且要求
    // down 未被消费，因此覆盖层自身的控件（输入框、建议行）仍能拿到点击。同 AuroraBottomSheet 的做法。
    Column(
        modifier = modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .fillMaxSize()
            .background(c.bg)
            .padding(AuroraSpacing.m),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(AuroraSpacing.touchTarget)
                .clip(RoundedCornerShape(AuroraRadius.m))
                .background(c.surface)
                .border(1.5.dp, c.brand, RoundedCornerShape(AuroraRadius.m))
                .padding(horizontal = AuroraSpacing.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
        ) {
            Icon(painterResource(AuroraIcons.Search), null, tint = c.brand, modifier = Modifier.size(18.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) Text("搜索或输入网址", color = c.textDisabled)
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(color = c.text),
                    cursorBrush = SolidColor(c.brand),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Go,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onGo = { if (query.isNotBlank()) onSubmit(query) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .semantics { contentDescription = "地址栏输入" },
                )
            }
            Box(
                modifier = Modifier
                    .size(AuroraSpacing.touchTarget)
                    .clip(RoundedCornerShape(AuroraRadius.full))
                    .clickable(
                        role = Role.Button,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss,
                    )
                    .semantics { contentDescription = "关闭地址栏" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(AuroraIcons.Close), null, tint = c.textMuted, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(AuroraSpacing.m))

        // 唯一滚动容器
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(suggestions, key = { it }) { s ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .clickable(
                            role = Role.Button,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { onSubmit(s) },
                        )
                        .semantics { contentDescription = "建议：$s" }
                        .padding(horizontal = AuroraSpacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.m),
                ) {
                    Icon(painterResource(AuroraIcons.Search), null, tint = c.textMuted, modifier = Modifier.size(19.dp))
                    Text(s, color = c.text, modifier = Modifier.weight(1f))
                    Icon(painterResource(AuroraIcons.ChevronRight), null, tint = c.textDisabled, modifier = Modifier.size(16.dp))
                }
            }
            item {
                Spacer(Modifier.height(AuroraSpacing.l))
                Row(horizontalArrangement = Arrangement.spacedBy(AuroraSpacing.s)) {
                    QuickAction(AuroraIcons.QrCode, "扫码", Modifier.weight(1f)) {}
                    QuickAction(AuroraIcons.Mic, "语音", Modifier.weight(1f)) {}
                    QuickAction(AuroraIcons.Clipboard, "粘贴并前往", Modifier.weight(1f)) {}
                    QuickAction(AuroraIcons.Incognito, "无痕", Modifier.weight(1f)) {}
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    icon: Int, label: String, modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    val c = Aurora.colors
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(AuroraRadius.m))
            .background(c.bgElevated)
            .border(1.dp, c.divider, RoundedCornerShape(AuroraRadius.m))
            .clickable(
                role = Role.Button,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = AuroraSpacing.m)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AuroraSpacing.s),
    ) {
        Icon(painterResource(icon), null, tint = c.brand, modifier = Modifier.size(21.dp))
        Text(label, color = c.textSecondary)
    }
}

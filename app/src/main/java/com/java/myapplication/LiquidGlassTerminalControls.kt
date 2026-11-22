package com.java.myapplication

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal const val KEY_ESC = "\u001B"
internal const val KEY_TAB = "\t"
internal const val KEY_PG_UP = "\u001B[5~"
internal const val KEY_PG_DOWN = "\u001B[6~"
internal const val KEY_HOME = "\u001B[H"
internal const val KEY_END = "\u001B[F"
internal const val KEY_UP = "\u001B[A"
internal const val KEY_DOWN = "\u001B[B"
internal const val KEY_LEFT = "\u001B[D"
internal const val KEY_RIGHT = "\u001B[C"

internal fun encodeTerminalText(
    text: String,
    ctrl: Boolean,
    alt: Boolean,
): String {
    if (text.isEmpty()) return text

    val withCtrl =
        if (ctrl && text.length == 1) {
            val upper = text[0].uppercaseChar()
            if (upper in 'A'..'Z') {
                (upper.code - 64).toChar().toString()
            } else {
                text
            }
        } else {
            text
        }

    return if (alt) KEY_ESC + withCtrl else withCtrl
}

@Composable
internal fun TerminalShortcutBar(
    ctrlLocked: Boolean,
    altLocked: Boolean,
    onCtrlChanged: (Boolean) -> Unit,
    onAltChanged: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onShowKeyboard: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    modifier: Modifier = Modifier,
    dark: Boolean = true,
) {
    var showClipboard by rememberSaveable { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = showClipboard) {
            GlassSurface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                shape = RoundedCornerShape(18.dp),
                strength = 0.64f,
                tint = Color.White.copy(alpha = 0.075f),
                veil = 0f,
                rimAlpha = 0.62f,
                elevation = 10.dp,
            ) {
                Row(
                    modifier = Modifier.padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ShortcutKey("Copy", "复制终端选中内容", dark = true) {
                        onCopy()
                        showClipboard = false
                    }
                    ShortcutKey("Paste", "粘贴剪贴板内容", dark = true) {
                        onPaste()
                        showClipboard = false
                    }
                }
            }
        }

        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            strength = 0.72f,
            tint = Color.White.copy(alpha = 0.085f),
            veil = 0f,
            rimAlpha = 0.72f,
            elevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                ShortcutRow {
                    ShortcutKey("Esc", "发送 Escape", dark = true) { onSend(KEY_ESC) }
                    ShortcutKey("Tab", "发送 Tab", dark = true) { onSend(KEY_TAB) }
                    ShortcutKey("PgUp", "终端向上翻页，长按连续触发", repeat = true, dark = true) { onSend(KEY_PG_UP) }
                    ShortcutKey("Home", "跳到终端行首", dark = true) { onSend(KEY_HOME) }
                    ShortcutKey("▲", "终端方向键上，长按连续触发", repeat = true, dark = true) { onSend(KEY_UP) }
                    ShortcutKey("End", "跳到终端行尾", dark = true) { onSend(KEY_END) }
                    ShortcutKey(
                        label = "⋮",
                        description = "展开复制与粘贴",
                        selected = showClipboard,
                        dark = true,
                    ) { showClipboard = !showClipboard }
                }

                ShortcutRow {
                    ShortcutKey(
                        label = "Ctrl",
                        description = "切换 Ctrl 修饰键",
                        selected = ctrlLocked,
                        dark = true,
                    ) { onCtrlChanged(!ctrlLocked) }
                    ShortcutKey(
                        label = "Alt",
                        description = "切换 Alt 修饰键",
                        selected = altLocked,
                        dark = true,
                    ) { onAltChanged(!altLocked) }
                    ShortcutKey("PgDn", "终端向下翻页，长按连续触发", repeat = true, dark = true) { onSend(KEY_PG_DOWN) }
                    ShortcutKey("◀", "终端方向键左，长按连续触发", repeat = true, dark = true) { onSend(KEY_LEFT) }
                    ShortcutKey("▼", "终端方向键下，长按连续触发", repeat = true, dark = true) { onSend(KEY_DOWN) }
                    ShortcutKey("▶", "终端方向键右，长按连续触发", repeat = true, dark = true) { onSend(KEY_RIGHT) }
                    ShortcutKey("⌨", "打开终端软键盘", dark = true) { onShowKeyboard() }
                }
            }
        }
    }
}

@Composable
private fun ShortcutRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun RowScope.ShortcutKey(
    label: String,
    description: String,
    repeat: Boolean = false,
    selected: Boolean = false,
    dark: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val press = rowPress(
        shape = shape,
        scale = 1.045f,
        strength = if (dark) 0.66f else 0.34f,
        dark = dark,
    )
    val haptic = LocalHapticFeedback.current
    val currentOnClick by rememberUpdatedState(onClick)

    Box(
        modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .then(press.modifier)
            .clip(shape)
            .terminalKeyInput(
                interaction = press.interaction,
                repeat = repeat,
                onPressChanged = {
                    if (it) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
                onClick = { currentOnClick() },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                role = Role.Button
                this.selected = selected
                stateDescription = if (selected) "已锁定" else "未锁定"
                onClick(label = description) {
                    currentOnClick()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = when {
                selected -> Color(0xFFFFFFFF)
                dark -> Color(0xFFD7DAE1)
                else -> Color(0xFFB9BDC4)
            },
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private fun Modifier.terminalKeyInput(
    interaction: MutableInteractionSource,
    repeat: Boolean,
    onPressChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
): Modifier = pointerInput(interaction, repeat) {
    var repeated = false
    detectTapGestures(
        onPress = {
            repeated = false
            val press = PressInteraction.Press(Offset.Zero)
            interaction.emit(press)
            onPressChanged(true)
            coroutineScope {
                val repeatJob = if (repeat) launch {
                    delay(380)
                    repeated = true
                    while (isActive) {
                        onClick()
                        delay(75)
                    }
                } else {
                    null
                }
                try {
                    tryAwaitRelease()
                } finally {
                    repeatJob?.cancel()
                    interaction.emit(PressInteraction.Release(press))
                    onPressChanged(false)
                }
            }
        },
        onTap = {
            if (!repeat || !repeated) onClick()
        },
    )
}

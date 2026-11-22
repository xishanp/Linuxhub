package com.java.myapplication

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import ai.lotus.liquidglass.LiquidSpringPreset
import ai.lotus.liquidglass.liquidSpring

/**
 * Shared press feedback for dashboard cards.
 *
 * Clickable cards use the normal click semantics. Display-only cards still get the
 * same spring scale without advertising a fake button to accessibility services.
 */
@Composable
internal fun LinuxHubPressable(
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    pressedScale: Float = 0.985f,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val interactionPressed by interaction.collectIsPressedAsState()
    var passivePressed by remember { mutableStateOf(false) }
    val pressed = if (onClick != null) interactionPressed else passivePressed
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = liquidSpring(
            if (pressed) LiquidSpringPreset.Snappy else LiquidSpringPreset.Smooth
        ),
        label = "linuxHubPressScale",
    )

    val pressModifier = modifier
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
            } else {
                Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        passivePressed = true
                        try {
                            waitForUpOrCancellation()
                        } finally {
                            passivePressed = false
                        }
                    }
                }
            }
        )

    Box(modifier = pressModifier, content = content)
}

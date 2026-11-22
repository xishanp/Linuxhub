package com.java.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.lotus.liquidglass.LiquidGlassBottomBar
import ai.lotus.liquidglass.LiquidGlassIconButton
import ai.lotus.liquidglass.LiquidGlassScene
import ai.lotus.liquidglass.LiquidGlassSurface
import ai.lotus.liquidglass.LiquidGlassStyle
import ai.lotus.liquidglass.LiquidGlassTab
import ai.lotus.liquidglass.LocalLiquidGlassStyle
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

internal val AppleCanvas = Color(0xFFF5F5F7)
internal val AppleSurface = Color(0xFFFFFFFF)
internal val AppleInk = Color(0xFF1D1D1F)
internal val AppleSecondary = Color(0xFF6E6E73)
internal val AppleHairline = Color(0xFFD2D2D7)
internal val AppleTerminal = Color(0xFF111112)

internal enum class AppleDockDestination {
    HOME,
    TERMINAL,
    SETTINGS,
}

/**
 * One recording scene for the entire app. The white page is the refraction source and
 * every glass surface is drawn in the overlay layer by the supplied Liquid Glass AAR.
 */
@Composable
internal fun AppleLiquidGlassScene(
    page: @Composable BoxScope.() -> Unit,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    LiquidGlassScene(
        modifier = Modifier.fillMaxSize(),
        background = {
            Box(Modifier.fillMaxSize().background(AppleCanvas)) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(
                        brush = Brush.linearGradient(
                            colors = listOf(Color.White, AppleCanvas, Color.White),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height),
                        )
                    )
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.9f), Color.Transparent),
                            center = Offset(size.width * 0.24f, size.height * 0.12f),
                            radius = size.minDimension * 0.72f,
                        ),
                        radius = size.minDimension * 0.72f,
                        center = Offset(size.width * 0.24f, size.height * 0.12f),
                    )
                }
            }
        },
        page = page,
        overlay = overlay,
    )
}

@Composable
internal fun AppleLiquidGlassDock(
    destination: AppleDockDestination,
    onHome: () -> Unit,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onDisplay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val suppliedStyle = LocalLiquidGlassStyle.current
    val foreground = if (destination == AppleDockDestination.TERMINAL) Color.White else AppleInk
    val style = suppliedStyle.copy(
        contentColor = foreground,
        lensStrength = 0.58f,
        lensScale = 1.5f,
        rimAlpha = 0.55f,
        spectrumAlpha = 0.16f,
    )
    val tabs = listOf(
        LiquidGlassTab("home", "主页") { tint -> HomeGlyph(tint) },
        LiquidGlassTab("terminal", "终端") { tint -> TerminalGlyph(tint) },
        LiquidGlassTab("settings", "设置") { tint -> SettingsGlyph(tint) },
    )
    val activeKey = when (destination) {
        AppleDockDestination.HOME -> "home"
        AppleDockDestination.TERMINAL -> "terminal"
        AppleDockDestination.SETTINGS -> "settings"
    }

    CompositionLocalProvider(LocalLiquidGlassStyle provides style) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiquidGlassBottomBar(
                tabs = tabs,
                activeKey = activeKey,
                onSelect = { key ->
                    when (key) {
                        "home" -> onHome()
                        "terminal" -> onTerminal()
                        "settings" -> onSettings()
                    }
                },
                modifier = Modifier.width(232.dp),
                contentColor = foreground,
            )

            LiquidGlassIconButton(
                onClick = onDisplay,
                modifier = Modifier.semantics {
                    contentDescription = "打开桌面"
                    role = Role.Button
                },
                size = 64.dp,
                shape = CircleShape,
                blendAmbient = true,
                veil = 0f,
                shellLens = 24.dp,
                shellHighlightAlpha = 0.90f,
                shellRimAlpha = 0.85f,
                shellSpectrumAlpha = 0f,
                shellElevation = 10.dp,
                pressScaleX = 0.17f,
                pressScaleY = 0.10f,
                lean = 4.dp,
                onPressedChange = {
                    if (it) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                },
            ) { tint -> DisplayGlyph(tint) }
        }
    }
}

@Composable
private fun HomeGlyph(color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val stroke = 1.8.dp.toPx()
        val path = Path().apply {
            moveTo(size.width * 0.14f, size.height * 0.48f)
            lineTo(size.width * 0.50f, size.height * 0.17f)
            lineTo(size.width * 0.86f, size.height * 0.48f)
            moveTo(size.width * 0.24f, size.height * 0.43f)
            lineTo(size.width * 0.24f, size.height * 0.83f)
            lineTo(size.width * 0.76f, size.height * 0.83f)
            lineTo(size.width * 0.76f, size.height * 0.43f)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
        drawLine(
            color = color,
            start = Offset(size.width * 0.43f, size.height * 0.83f),
            end = Offset(size.width * 0.43f, size.height * 0.61f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.57f, size.height * 0.61f),
            end = Offset(size.width * 0.57f, size.height * 0.83f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun TerminalGlyph(color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val stroke = 1.8.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.10f, size.height * 0.14f),
            size = Size(size.width * 0.80f, size.height * 0.72f),
            cornerRadius = CornerRadius(5.dp.toPx()),
            style = Stroke(stroke),
        )
        val path = Path().apply {
            moveTo(size.width * 0.25f, size.height * 0.36f)
            lineTo(size.width * 0.39f, size.height * 0.49f)
            lineTo(size.width * 0.25f, size.height * 0.62f)
            moveTo(size.width * 0.47f, size.height * 0.65f)
            lineTo(size.width * 0.71f, size.height * 0.65f)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun SettingsGlyph(color: Color) {
    Canvas(Modifier.size(23.dp)) {
        val stroke = 1.8.dp.toPx()
        val ys = listOf(0.25f, 0.50f, 0.75f)
        val knobs = listOf(0.36f, 0.66f, 0.44f)
        ys.zip(knobs).forEach { (y, x) ->
            drawLine(
                color = color,
                start = Offset(size.width * 0.14f, size.height * y),
                end = Offset(size.width * 0.86f, size.height * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = color,
                radius = 2.4.dp.toPx(),
                center = Offset(size.width * x, size.height * y),
            )
        }
    }
}

@Composable
private fun DisplayGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val stroke = 1.8.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.12f, size.height * 0.17f),
            size = Size(size.width * 0.76f, size.height * 0.56f),
            cornerRadius = CornerRadius(3.5.dp.toPx()),
            style = Stroke(stroke),
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.50f, size.height * 0.73f),
            end = Offset(size.width * 0.50f, size.height * 0.84f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = Offset(size.width * 0.34f, size.height * 0.84f),
            end = Offset(size.width * 0.66f, size.height * 0.84f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

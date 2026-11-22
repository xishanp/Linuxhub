package com.java.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.lotus.liquidglass.LiquidGlassSurface

@Composable
internal fun LinuxHubPageHeader(title: String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            color = AppleInk,
            fontSize = 29.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        )
    }
}

@Composable
internal fun LinuxHubBackButton(onClick: () -> Unit) {
    LinuxHubPressable(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        pressedScale = 0.92f,
    ) {
        LiquidGlassSurface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            strength = 0.62f,
            rimAlpha = 0.72f,
            elevation = 3.dp,
        ) {
            Canvas(Modifier.size(19.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = AppleInk,
                    start = Offset(size.width * 0.66f, size.height * 0.18f),
                    end = Offset(size.width * 0.34f, size.height * 0.50f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = AppleInk,
                    start = Offset(size.width * 0.34f, size.height * 0.50f),
                    end = Offset(size.width * 0.66f, size.height * 0.82f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

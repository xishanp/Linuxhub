package com.java.myapplication

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doro.terminal.shell.R

@Composable
internal fun LinuxHubLaunchSplash() {
    val ubuntuFont = FontFamily(Font(R.font.ubuntu_variable, weight = FontWeight.Normal))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.splash_linuxhub_icon),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .aspectRatio(1f),
                contentScale = ContentScale.Fit,
            )
            Text(
                text = "Ubuntu",
                modifier = Modifier.padding(top = 18.dp),
                color = Color.White,
                fontFamily = ubuntuFont,
                fontSize = 38.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = (-0.6).sp,
            )
        }
    }
}

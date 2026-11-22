package com.java.myapplication

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.lotus.liquidglass.LiquidGlassSurface
import com.doro.terminal.shell.R

private const val LinuxHubRepoUrl = "https://github.com/xishanp/Linuxhub"
private const val AuthorUrl = "https://github.com/xishanp"
private const val FeedbackEmail = "g15013815279@outlook.com"

@Composable
internal fun LinuxHubAboutPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val packageInfo = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val versionName = packageInfo.versionName.orEmpty().ifBlank { "Myth-Prometheus" }

    Box(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = 760.dp),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 150.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                AboutHeader(title = "关于 LinuxHub", onBack = onBack)
            }
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.home_linuxhub_mark),
                        contentDescription = "LinuxHub",
                        modifier = Modifier.size(148.dp),
                    )
                }
            }
            item {
                AboutGroup {
                    AboutRow("版本", versionName)
                    AboutDivider()
                    AboutRow(
                        label = "项目 GitHub",
                        value = "xishanp/Linuxhub",
                        onClick = { openUri(context, LinuxHubRepoUrl) },
                    )
                    AboutDivider()
                    AboutRow(
                        label = "作者",
                        value = "xishanp",
                        onClick = { openUri(context, AuthorUrl) },
                    )
                    AboutDivider()
                    AboutRow(
                        label = "反馈",
                        value = FeedbackEmail,
                        onClick = { sendFeedback(context) },
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "开源依赖",
                color = AppleSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(7.dp))
            Text(
                text = "Kyant0 / AndroidLiquidGlass · Apache 2.0\n" +
                    "chrisbanes / haze · Jetpack Compose",
                color = AppleSecondary.copy(alpha = 0.58f),
                fontSize = 11.sp,
                lineHeight = 17.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AboutHeader(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(66.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LinuxHubBackButton(onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            color = AppleInk,
            fontSize = 27.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        )
    }
}

@Composable
private fun AboutGroup(content: @Composable ColumnScope.() -> Unit) {
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        strength = 0.54f,
        rimAlpha = 0.52f,
        elevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun AboutRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    LinuxHubPressable(onClick = onClick, pressedScale = 0.985f) {
        Row(
            modifier = Modifier.fillMaxWidth().height(55.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = AppleSecondary,
                fontSize = 13.sp,
            )
            Text(
                text = value,
                modifier = Modifier.weight(1.35f),
                color = AppleInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
            )
            if (onClick != null) {
                Spacer(Modifier.width(7.dp))
                Text("›", color = AppleSecondary.copy(alpha = 0.72f), fontSize = 19.sp)
            }
        }
    }
}

@Composable
private fun AboutDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(AppleHairline.copy(alpha = 0.58f)))
}

private fun openUri(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun sendFeedback(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:$FeedbackEmail")
                putExtra(Intent.EXTRA_SUBJECT, "LinuxHub 反馈")
            }
        )
    }
}

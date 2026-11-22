package com.java.myapplication

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.lotus.liquidglass.LiquidGlassSurface
import com.doro.terminal.shell.R
import com.doro.terminal.shell.desktop.LinuxHubDesktopSession
import com.doro.terminal.shell.linux.ContainerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val HomeReady = Color(0xFF2E9D57)
private val HomeWarning = Color(0xFFB25000)
private val HomeHairline = AppleHairline.copy(alpha = 0.58f)

@Immutable
private data class HomeLiveState(
    val availableMemory: Long = 0L,
    val totalMemory: Long = 0L,
    val availableStorage: Long = 0L,
    val totalStorage: Long = 0L,
    val desktop: LinuxHubDesktopSession.Status? = null,
    val network: HomeNetworkState = HomeNetworkState(),
)

@Immutable
private data class HomeNetworkState(
    val connected: Boolean = false,
    val validated: Boolean = false,
    val transport: String = "离线",
)

@Composable
internal fun HomePage(
    username: String,
    config: ContainerConfig?,
    terminalCount: Int,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit,
    onOpenUsb: () -> Unit,
    onOpenLan: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val context = LocalContext.current
    var live by remember { mutableStateOf(HomeLiveState()) }
    LaunchedEffect(context) {
        while (true) {
            live = withContext(Dispatchers.IO) { readHomeLiveState(context) }
            delay(4_000)
        }
    }

    val distro = config?.distribution?.replaceFirstChar { it.uppercase() } ?: "Ubuntu"
    val version = config?.version ?: "24.04"
    val gpu = config?.gpuProfile?.label ?: "自动检测"
    val initialized = config?.initialized == true
    val abi = Build.SUPPORTED_ABIS.firstOrNull()?.uppercase() ?: "ARM64"
    val deviceName = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replaceFirstChar { it.uppercase() }
    val memoryFraction = live.memoryUsedFraction()
    val storageFraction = live.storageUsedFraction()
    val desktopRunning = live.desktop?.let {
        it.running || it.captureReady || it.firstFrameReady
    } == true
    val desktopState = when {
        desktopRunning -> "运行中"
        live.desktop?.lastError != null -> "需要检查"
        else -> "待启动"
    }
    val desktopDetail = when {
        desktopRunning && live.desktop != null -> {
            val uptime = live.desktop!!.uptimeSeconds.formatUptime()
            "${live.desktop!!.width} × ${live.desktop!!.height}" +
                if (uptime.isNotBlank()) " · 已运行 $uptime" else " · X11 已连接"
        }
        live.desktop?.lastError != null -> live.desktop!!.lastError.orEmpty()
        else -> "从右下角显示器按钮启动"
    }
    val networkReady = live.network.connected && live.network.validated
    val healthText = when {
        !initialized -> "需要初始化"
        live.desktop?.lastError != null -> "有项目需要检查"
        networkReady -> "运行正常"
        else -> "系统在线"
    }
    val healthColor = when {
        !initialized || live.desktop?.lastError != null -> HomeWarning
        else -> HomeReady
    }

    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val wide = maxWidth >= 720.dp
        val contentWidth = when {
            wide -> minOf(maxWidth, 980.dp)
            maxWidth >= 600.dp -> minOf(maxWidth, 640.dp)
            else -> maxWidth
        }
        val horizontalPadding = if (wide) 24.dp else 20.dp

        LazyColumn(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .width(contentWidth)
                .fillMaxHeight(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 20.dp,
                bottom = 164.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                HomeHeader(
                    status = healthText,
                    statusColor = healthColor,
                )
            }

            if (wide) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.weight(1.15f)) {
                            HomeEnvironmentCard(
                                distro = distro,
                                version = version,
                                username = username,
                                ready = initialized,
                                onClick = onOpenAbout,
                            )
                        }
                        Box(Modifier.weight(0.85f)) {
                            HomeServicesCard(
                                terminalCount = terminalCount,
                                desktopState = desktopState,
                                desktopDetail = desktopDetail,
                                desktopWarning = live.desktop?.lastError != null,
                                onOpenTerminal = onOpenTerminal,
                                onOpenDesktop = onOpenDesktop,
                            )
                        }
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(
                            modifier = Modifier.weight(1.15f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            HomeSectionTitle("状态概览")
                            HomeMetricsRow(
                                live = live,
                                memoryFraction = memoryFraction,
                                storageFraction = storageFraction,
                            )
                        }
                        Column(
                            modifier = Modifier.weight(0.85f),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            HomeSectionTitle("连接")
                            HomeConnectionsRow(
                                live = live,
                                onOpenUsb = onOpenUsb,
                                onOpenLan = onOpenLan,
                            )
                        }
                    }
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeSectionTitle("系统信息")
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
                                HomeSystemInfoCard(
                                    deviceName = deviceName,
                                    distro = distro,
                                    version = version,
                                    gpu = gpu,
                                    abi = abi,
                                    username = username,
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    HomeEnvironmentCard(
                        distro = distro,
                        version = version,
                        username = username,
                        ready = initialized,
                        onClick = onOpenAbout,
                    )
                }
                item {
                    Spacer(Modifier.height(2.dp))
                    HomeSectionTitle("状态概览")
                }
                item {
                    HomeMetricsRow(
                        live = live,
                        memoryFraction = memoryFraction,
                        storageFraction = storageFraction,
                    )
                }
                item {
                    HomeServicesCard(
                        terminalCount = terminalCount,
                        desktopState = desktopState,
                        desktopDetail = desktopDetail,
                        desktopWarning = live.desktop?.lastError != null,
                        onOpenTerminal = onOpenTerminal,
                        onOpenDesktop = onOpenDesktop,
                    )
                }
                item {
                    Spacer(Modifier.height(2.dp))
                    HomeSectionTitle("连接")
                }
                item {
                    HomeConnectionsRow(
                        live = live,
                        onOpenUsb = onOpenUsb,
                        onOpenLan = onOpenLan,
                    )
                }
                item {
                    Spacer(Modifier.height(2.dp))
                    HomeSectionTitle("系统信息")
                }
                item {
                    HomeSystemInfoCard(
                        deviceName = deviceName,
                        distro = distro,
                        version = version,
                        gpu = gpu,
                        abi = abi,
                        username = username,
                    )
                }
            }

            item {
                Text(
                    text = "LinuxHub · Ubuntu ${config?.version ?: "24.04"} LTS · 本地容器",
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    color = AppleSecondary.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun HomeServicesCard(
    terminalCount: Int,
    desktopState: String,
    desktopDetail: String,
    desktopWarning: Boolean,
    onOpenTerminal: () -> Unit,
    onOpenDesktop: () -> Unit,
) {
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        strength = 0.56f,
        rimAlpha = 0.54f,
        elevation = 7.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 7.dp)) {
            HomeServiceRow(
                title = "终端会话",
                value = "$terminalCount 个",
                detail = if (terminalCount > 0) "后台保持连接" else "尚未创建终端会话",
                icon = { HomeTerminalGlyph(it) },
                onClick = onOpenTerminal,
            )
            HomeDivider()
            HomeServiceRow(
                title = "Ubuntu 桌面",
                value = desktopState,
                detail = desktopDetail,
                icon = { HomeDisplayGlyph(it) },
                warning = desktopWarning,
                onClick = onOpenDesktop,
            )
        }
    }
}

@Composable
private fun HomeMetricsRow(
    live: HomeLiveState,
    memoryFraction: Float,
    storageFraction: Float,
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeMetricCard(
            label = "可用内存",
            value = live.availableMemory.asFileSize(context, "读取中"),
            detail = "共 ${live.totalMemory.asFileSize(context, "读取中")}",
            fraction = memoryFraction,
            icon = { HomeMemoryGlyph(it) },
            modifier = Modifier.weight(1f),
        )
        HomeMetricCard(
            label = "可用存储",
            value = live.availableStorage.asFileSize(context, "读取中"),
            detail = "共 ${live.totalStorage.asFileSize(context, "读取中")}",
            fraction = storageFraction,
            icon = { HomeStorageGlyph(it) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun HomeConnectionsRow(
    live: HomeLiveState,
    onOpenUsb: () -> Unit,
    onOpenLan: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HomeConnectionCard(
            label = "USB 连接",
            value = "端口转发",
            detail = "电脑浏览器 · HTTPS",
            warning = false,
            icon = { HomeUsbGlyph(it) },
            modifier = Modifier.weight(1f),
            onClick = onOpenUsb,
        )
        HomeConnectionCard(
            label = "局域网",
            value = "浏览器访问",
            detail = if (live.network.connected) "同一 Wi‑Fi · HTTPS" else "请先连接 Wi‑Fi",
            warning = !live.network.connected,
            icon = { HomeNetworkGlyph(it) },
            modifier = Modifier.weight(1f),
            onClick = onOpenLan,
        )
    }
}

@Composable
private fun HomeSystemInfoCard(
    deviceName: String,
    distro: String,
    version: String,
    gpu: String,
    abi: String,
    username: String,
) {
    LinuxHubPressable {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            strength = 0.54f,
            rimAlpha = 0.52f,
            elevation = 6.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 9.dp)) {
                HomeInfoRow("设备", deviceName.ifBlank { "Android" })
                HomeInfoRow("Ubuntu", "$distro $version LTS")
                HomeInfoRow("GPU 线路", gpu)
                HomeInfoRow("Android", "Android ${Build.VERSION.RELEASE}")
                HomeInfoRow("架构", abi)
                HomeInfoRow("用户", username, last = true)
            }
        }
    }
}

@Composable
private fun HomeHeader(
    status: String,
    statusColor: Color,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "LinuxHub",
                color = AppleInk,
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp,
            )
            Text(
                text = "Linux 容器控制台",
                color = AppleSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        LinuxHubPressable(pressedScale = 0.95f) {
            Row(
                modifier = Modifier
                    .background(AppleSurface.copy(alpha = 0.70f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                Text(status, color = AppleInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun HomeEnvironmentCard(
    distro: String,
    version: String,
    username: String,
    ready: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    LinuxHubPressable(onClick = onClick) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            strength = 0.58f,
            rimAlpha = 0.56f,
            elevation = 8.dp,
        ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(AppleSurface.copy(alpha = 0.76f), RoundedCornerShape(17.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.home_linuxhub_mark),
                        contentDescription = null,
                        modifier = Modifier.size(68.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                    Text(
                        text = "当前环境",
                        color = AppleSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                    )
                    Text(
                        text = "$distro $version LTS",
                        color = AppleInk,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (ready) "已初始化" else "待初始化",
                    color = if (ready) HomeReady else HomeWarning,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))
            HomeDivider()
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$username@ubuntu",
                    modifier = Modifier.weight(1f),
                    color = AppleInk,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                HomeTag("PRoot")
                Spacer(Modifier.size(6.dp))
                HomeTag("GNOME")
            }
        }
        }
    }
}

@Composable
private fun HomeTag(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .background(AppleSurface.copy(alpha = 0.72f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = AppleSecondary,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
    )
}

@Composable
private fun HomeMetricCard(
    label: String,
    value: String,
    detail: String,
    fraction: Float,
    icon: @Composable (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    LinuxHubPressable(modifier = modifier.height(156.dp)) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(24.dp),
            strength = 0.54f,
            rimAlpha = 0.52f,
            elevation = 6.dp,
        ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(AppleSurface.copy(alpha = 0.74f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    icon(AppleInk)
                }
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 8.dp),
                    color = AppleSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = value,
                        color = AppleInk,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "可用",
                        color = AppleSecondary,
                        fontSize = 10.sp,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = detail,
                        color = AppleSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                HomeProgressRing(fraction)
            }
        }
        }
    }
}

@Composable
private fun HomeProgressRing(fraction: Float) {
    val percentage = (fraction.coerceIn(0f, 1f) * 100).toInt()
    Box(
        modifier = Modifier
            .size(56.dp)
            .semantics { contentDescription = "已使用 $percentage%" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = AppleHairline.copy(alpha = 0.68f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = AppleInk,
                startAngle = -90f,
                sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = "$percentage%",
            color = AppleInk,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HomeServiceRow(
    title: String,
    value: String,
    detail: String,
    icon: @Composable (Color) -> Unit,
    warning: Boolean = false,
    onClick: () -> Unit,
) {
    LinuxHubPressable(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(AppleSurface.copy(alpha = 0.72f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            icon(if (warning) HomeWarning else AppleInk)
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = AppleInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = detail,
                color = if (warning) HomeWarning else AppleSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
            Text(
                text = value,
                color = if (warning) HomeWarning else AppleInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun HomeConnectionCard(
    label: String,
    value: String,
    detail: String,
    warning: Boolean,
    icon: @Composable (Color) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)
    LinuxHubPressable(
        onClick = onClick,
        modifier = modifier.height(116.dp),
    ) {
        LiquidGlassSurface(
            modifier = Modifier.fillMaxSize(),
            shape = shape,
            strength = 0.54f,
            rimAlpha = 0.52f,
            elevation = 6.dp,
        ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 15.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(AppleSurface.copy(alpha = 0.74f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    icon(if (warning) HomeWarning else AppleInk)
                }
                Text(
                    text = label,
                    modifier = Modifier.padding(start = 8.dp),
                    color = AppleSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = value,
                color = if (warning) HomeWarning else AppleInk,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                color = if (warning) HomeWarning else AppleSecondary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        }
    }
}

@Composable
private fun HomeSectionTitle(title: String, detail: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            color = AppleInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = (-0.1).sp,
        )
        if (detail != null) {
            Text(detail, color = AppleSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun HomeDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(HomeHairline))
}

@Composable
private fun HomeInfoRow(label: String, value: String, last: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().height(43.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = AppleSecondary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = AppleInk,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (!last) HomeDivider()
}

@Composable
private fun HomeMemoryGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.4.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.18f, size.height * 0.18f),
            size = Size(size.width * 0.64f, size.height * 0.64f),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(stroke),
        )
        listOf(0.34f, 0.50f, 0.66f).forEach { x ->
            drawLine(color, Offset(size.width * x, 0f), Offset(size.width * x, size.height * 0.18f), stroke)
            drawLine(color, Offset(size.width * x, size.height * 0.82f), Offset(size.width * x, size.height), stroke)
        }
        listOf(0.34f, 0.50f, 0.66f).forEach { y ->
            drawLine(color, Offset(0f, size.height * y), Offset(size.width * 0.18f, size.height * y), stroke)
            drawLine(color, Offset(size.width * 0.82f, size.height * y), Offset(size.width, size.height * y), stroke)
        }
    }
}

@Composable
private fun HomeStorageGlyph(color: Color) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.4.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.18f, size.height * 0.15f),
            size = Size(size.width * 0.64f, size.height * 0.70f),
            cornerRadius = CornerRadius(size.width * 0.16f),
            style = Stroke(stroke),
        )
        listOf(0.36f, 0.56f).forEach { y ->
            drawLine(
                color = color,
                start = Offset(size.width * 0.28f, size.height * y),
                end = Offset(size.width * 0.72f, size.height * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun HomeTerminalGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.08f, size.height * 0.14f),
            size = Size(size.width * 0.84f, size.height * 0.72f),
            cornerRadius = CornerRadius(3.dp.toPx()),
            style = Stroke(stroke),
        )
        val path = Path().apply {
            moveTo(size.width * 0.24f, size.height * 0.35f)
            lineTo(size.width * 0.39f, size.height * 0.49f)
            lineTo(size.width * 0.24f, size.height * 0.63f)
            moveTo(size.width * 0.48f, size.height * 0.65f)
            lineTo(size.width * 0.73f, size.height * 0.65f)
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun HomeDisplayGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.10f, size.height * 0.16f),
            size = Size(size.width * 0.80f, size.height * 0.58f),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(stroke),
        )
        drawLine(
            color,
            Offset(size.width * 0.50f, size.height * 0.74f),
            Offset(size.width * 0.50f, size.height * 0.86f),
            stroke,
        )
        drawLine(
            color,
            Offset(size.width * 0.34f, size.height * 0.86f),
            Offset(size.width * 0.66f, size.height * 0.86f),
            stroke,
            StrokeCap.Round,
        )
    }
}

@Composable
private fun HomeSettingsGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        val ys = listOf(0.24f, 0.50f, 0.76f)
        val knobs = listOf(0.36f, 0.66f, 0.44f)
        ys.zip(knobs).forEach { (y, x) ->
            drawLine(
                color = color,
                start = Offset(size.width * 0.12f, size.height * y),
                end = Offset(size.width * 0.88f, size.height * y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = color,
                radius = 2.2.dp.toPx(),
                center = Offset(size.width * x, size.height * y),
            )
        }
    }
}

@Composable
private fun HomeNetworkGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        val left = Offset(size.width * 0.24f, size.height * 0.27f)
        val right = Offset(size.width * 0.76f, size.height * 0.27f)
        val bottom = Offset(size.width * 0.50f, size.height * 0.76f)
        drawLine(color, left, bottom, stroke, StrokeCap.Round)
        drawLine(color, right, bottom, stroke, StrokeCap.Round)
        drawLine(color, left, right, stroke, StrokeCap.Round)
        listOf(left, right, bottom).forEach { center ->
            drawCircle(color, radius = 2.25.dp.toPx(), center = center)
        }
    }
}

@Composable
private fun HomeUsbGlyph(color: Color) {
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        val stemTop = Offset(size.width * 0.50f, size.height * 0.14f)
        val stemBottom = Offset(size.width * 0.50f, size.height * 0.82f)
        drawLine(
            color,
            stemTop,
            stemBottom,
            stroke,
            StrokeCap.Round,
        )
        val arrow = Path().apply {
            moveTo(size.width * 0.42f, size.height * 0.24f)
            lineTo(size.width * 0.58f, size.height * 0.24f)
            lineTo(size.width * 0.50f, size.height * 0.10f)
            close()
        }
        drawPath(arrow, color)
        val branches = Path().apply {
            moveTo(size.width * 0.50f, size.height * 0.60f)
            lineTo(size.width * 0.26f, size.height * 0.43f)
            lineTo(size.width * 0.26f, size.height * 0.28f)
            moveTo(size.width * 0.50f, size.height * 0.46f)
            lineTo(size.width * 0.74f, size.height * 0.30f)
            lineTo(size.width * 0.74f, size.height * 0.21f)
        }
        drawPath(branches, color, style = Stroke(stroke, cap = StrokeCap.Round))
        drawCircle(color, radius = 2.15.dp.toPx(), center = Offset(size.width * 0.26f, size.height * 0.26f))
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.69f, size.height * 0.14f),
            size = Size(size.width * 0.10f, size.height * 0.14f),
            cornerRadius = CornerRadius(0.8.dp.toPx()),
        )
        drawCircle(color, radius = 2.25.dp.toPx(), center = stemBottom)
    }
}

private fun readHomeLiveState(context: Context): HomeLiveState {
    val memoryInfo = ActivityManager.MemoryInfo()
    runCatching {
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memoryInfo)
    }
    val storageStat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val activeNetwork = connectivity?.activeNetwork
    val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
    val network = HomeNetworkState(
        connected = capabilities != null,
        validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        transport = when {
            capabilities == null -> "离线"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "已连接"
        },
    )
    return HomeLiveState(
        availableMemory = memoryInfo.availMem,
        totalMemory = memoryInfo.totalMem,
        availableStorage = storageStat?.availableBytes ?: 0L,
        totalStorage = storageStat?.totalBytes ?: 0L,
        desktop = runCatching { LinuxHubDesktopSession.status(context) }.getOrNull(),
        network = network,
    )
}

private fun HomeLiveState.memoryUsedFraction(): Float {
    if (totalMemory <= 0L) return 0f
    return ((totalMemory - availableMemory).toDouble() / totalMemory.toDouble())
        .coerceIn(0.0, 1.0)
        .toFloat()
}

private fun HomeLiveState.storageUsedFraction(): Float {
    if (totalStorage <= 0L) return 0f
    return ((totalStorage - availableStorage).toDouble() / totalStorage.toDouble())
        .coerceIn(0.0, 1.0)
        .toFloat()
}

private fun Long.asFileSize(context: Context, fallback: String = "未知"): String =
    if (this > 0L) Formatter.formatFileSize(context, this) else fallback

private fun Long.formatUptime(): String = when {
    this <= 0L -> ""
    this < 60L -> "${this} 秒"
    this < 3_600L -> "${this / 60L} 分钟"
    this < 86_400L -> "${this / 3_600L} 小时"
    else -> "${this / 86_400L} 天"
}

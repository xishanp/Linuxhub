package com.java.myapplication

import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.lotus.liquidglass.LiquidGlassSurface
import com.doro.terminal.shell.desktop.LinuxHubDesktopSession
import com.doro.terminal.shell.desktop.RemoteDesktopServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class RemoteDesktopMode {
    USB,
    LAN,
}

private enum class UsbHostPlatform {
    MACOS,
    WINDOWS,
}

private val ConnectionReady = Color(0xFF2E9D57)
private val ConnectionWarning = Color(0xFFB25000)
private val ConnectionDanger = Color(0xFFD70015)

private data class RemoteResources(
    val availableMemory: Long = 0L,
    val totalMemory: Long = 0L,
    val availableStorage: Long = 0L,
    val totalStorage: Long = 0L,
    val network: String = "读取中",
    val networkAvailable: Boolean = false,
    val desktop: LinuxHubDesktopSession.Status? = null,
)

@Composable
internal fun RemoteDesktopPage(mode: RemoteDesktopMode, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val server = remember { RemoteDesktopServer.get(context) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(server.status()) }
    var resources by remember { mutableStateOf(RemoteResources()) }
    var logs by remember { mutableStateOf(server.logTail()) }
    var starting by remember { mutableStateOf(true) }
    var desktopError by remember { mutableStateOf<String?>(null) }
    var adbEnabled by remember { mutableStateOf(readAdbEnabled(context)) }
    var usbHost by remember { mutableStateOf(UsbHostPlatform.MACOS) }

    suspend fun refreshSnapshot() {
        val snapshot = withContext(Dispatchers.IO) {
            Triple(server.status(), server.logTail(), readRemoteResources(context))
        }
        status = snapshot.first
        logs = snapshot.second
        resources = snapshot.third
        adbEnabled = readAdbEnabled(context)
    }

    LaunchedEffect(server) {
        starting = true
        val failure = withContext(Dispatchers.IO) {
            runCatching {
                if (!LinuxHubDesktopSession.status(context).running) {
                    // Keep the desktop at the device's native panel size. A
                    // lower remote-stream profile must scale this session, not
                    // restart GNOME at a smaller root and later resize it under
                    // a live Mutter session (which leaves the desktop clipped).
                    LinuxHubDesktopSession.start(context)
                }
                server.start()
            }.exceptionOrNull()
        }
        desktopError = failure?.message
        starting = false
        refreshSnapshot()
        while (true) {
            delay(1_000)
            refreshSnapshot()
        }
    }
    DisposableEffect(server) {
        onDispose { server.stop() }
    }

    val code = status.pairingCode.orEmpty()
    val lanHost = status.lanAddress ?: "手机 IP"
    val httpsPort = status.httpsPort?.toString() ?: "…"
    val lanUrl = if (status.running && status.lanAddress != null && status.httpsPort != null) {
        "https://$lanHost:$httpsPort/?code=$code"
    } else {
        "启动服务后生成手机局域网地址"
    }
    val usbOneClick = if (status.running && status.httpsPort != null && code.isNotBlank()) {
        val target = "https://127.0.0.1:18080/?code=$code"
        when (usbHost) {
            UsbHostPlatform.MACOS ->
                "adb -d forward tcp:18080 tcp:$httpsPort && open \"$target\""
            UsbHostPlatform.WINDOWS ->
                "adb -d forward tcp:18080 tcp:$httpsPort; if (\$LASTEXITCODE -eq 0) { Start-Process \"$target\" }"
        }
    } else {
        "服务启动后自动生成一键连接命令"
    }

    Box(
        Modifier.fillMaxSize().background(AppleCanvas),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(top = 8.dp)) {
            ConnectionHeader(
                title = if (mode == RemoteDesktopMode.USB) "USB 连接" else "局域网",
                onBack = onBack,
            )
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().widthIn(max = 760.dp)
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 132.dp),
                ) {
                    item {
                        RemoteStatusCard(
                            mode = mode,
                            status = status,
                            starting = starting,
                            desktopError = desktopError,
                            adbEnabled = adbEnabled,
                            onOpenDeveloperSettings = {
                                openDeveloperSettings(context)
                                adbEnabled = readAdbEnabled(context)
                            },
                        )
                    }
                    item { RemoteResourcePanel(resources) }
                    item {
                        SectionTitle(
                            if (mode == RemoteDesktopMode.USB) "USB 连接" else "连接信息",
                            if (mode == RemoteDesktopMode.USB) {
                                "先在电脑执行一键命令，转发完成后会自动打开浏览器"
                            } else {
                                "在电脑浏览器中使用下面这组地址"
                            },
                        )
                    }
                    if (mode == RemoteDesktopMode.USB) {
                        item {
                            UsbPlatformSelector(
                                selected = usbHost,
                                onSelect = { usbHost = it },
                            )
                        }
                        item {
                            ConnectionValueCard(
                                title = when (usbHost) {
                                    UsbHostPlatform.MACOS -> "macOS 一键连接（复制到终端）"
                                    UsbHostPlatform.WINDOWS -> "Windows 一键连接（复制到 PowerShell）"
                                },
                                value = usbOneClick,
                                monospace = true,
                                onCopy = { copy(context, usbOneClick) },
                            )
                        }
                        item {
                            Text(
                                when (usbHost) {
                                    UsbHostPlatform.MACOS ->
                                        "在 macOS 终端粘贴后执行。命令会先转发电脑的 18080 端口到手机，再自动打开浏览器。"
                                    UsbHostPlatform.WINDOWS ->
                                        "在 Windows PowerShell 粘贴后执行。命令会先转发电脑的 18080 端口到手机，再自动打开浏览器。"
                                },
                                color = AppleSecondary,
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    } else {
                        item {
                            ConnectionValueCard(
                                title = "局域网访问地址（电脑浏览器）",
                                value = lanUrl,
                                onCopy = { copy(context, lanUrl) },
                            )
                        }
                        item {
                            Text(
                                "电脑和手机连接同一个局域网后，直接打开上面的手机地址。地址里的 IP 由手机自动读取，不要改成 127.0.0.1。",
                                color = AppleSecondary,
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                                modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    item {
                        RemoteActions(
                            running = status.running,
                            onToggle = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        if (status.running) server.stop() else server.start()
                                    }
                                    refreshSnapshot()
                                }
                            },
                            onRefresh = {
                                scope.launch { refreshSnapshot() }
                            },
                        )
                    }
                    item { RemoteStepsCard(mode) }
                    item { RemoteLogCard(logs = logs) }
                }
            }
        }
    }
}

@Composable
private fun ConnectionHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        LinuxHubBackButton(onClick = onBack)
        Text(
            title,
            color = AppleInk,
            fontSize = 29.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        )
    }
}

@Composable
private fun SectionTitle(title: String, detail: String) {
    Column(Modifier.padding(horizontal = 4.dp)) {
        Text(title, color = AppleInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        Text(detail, color = AppleSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
private fun UsbPlatformSelector(
    selected: UsbHostPlatform,
    onSelect: (UsbHostPlatform) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "电脑系统",
            color = AppleSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            UsbPlatformOption(
                label = "macOS",
                selected = selected == UsbHostPlatform.MACOS,
                onClick = { onSelect(UsbHostPlatform.MACOS) },
                modifier = Modifier.weight(1f),
            )
            UsbPlatformOption(
                label = "Windows",
                selected = selected == UsbHostPlatform.WINDOWS,
                onClick = { onSelect(UsbHostPlatform.WINDOWS) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun UsbPlatformOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LinuxHubPressable(
        onClick = onClick,
        modifier = modifier.height(42.dp),
        pressedScale = 0.97f,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(
                if (selected) AppleInk else AppleSurface.copy(alpha = 0.72f),
                RoundedCornerShape(14.dp),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (selected) AppleCanvas else AppleInk,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun RemoteStatusCard(
    mode: RemoteDesktopMode,
    status: RemoteDesktopServer.Status,
    starting: Boolean,
    desktopError: String?,
    adbEnabled: Boolean,
    onOpenDeveloperSettings: () -> Unit,
) {
    val stateColor = when {
        desktopError != null -> ConnectionDanger
        status.running -> ConnectionReady
        else -> ConnectionWarning
    }
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        strength = 0.66f,
        rimAlpha = 0.76f,
        elevation = 8.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(9.dp).height(9.dp).background(stateColor, CircleShape))
                Spacer(Modifier.width(9.dp))
                Text(
                    when {
                        desktopError != null -> "启动失败"
                        starting -> "正在启动桌面"
                        status.running -> "服务运行中"
                        else -> "服务未运行"
                    },
                    color = AppleInk,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        status.frameReady && status.inputReady -> "画面与输入已就绪"
                        status.frameReady -> "等待输入桥"
                        else -> "等待首帧"
                    },
                    color = if (status.frameReady) AppleSecondary else ConnectionWarning,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                desktopError ?: status.lastError ?: "桌面、输入和网页服务都在手机本机运行。",
                color = if (desktopError != null || status.lastError != null) ConnectionDanger else AppleSecondary,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            )
            if (status.pairingCode != null) {
                Spacer(Modifier.height(15.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("配对码", color = AppleSecondary, fontSize = 11.sp)
                        Text(
                            status.pairingCode.chunked(3).joinToString(" "),
                            color = AppleInk,
                            fontSize = 29.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 3.sp,
                        )
                    }
                    Text(
                        "${status.clients} 个网页连接",
                        color = AppleSecondary,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
            if (mode == RemoteDesktopMode.USB) {
                Spacer(Modifier.height(13.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (adbEnabled) "系统 USB 调试已开启" else "系统 USB 调试未开启",
                        color = if (adbEnabled) ConnectionReady else ConnectionWarning,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(
                        text = "开发者选项",
                        onClick = onOpenDeveloperSettings,
                        compact = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteResourcePanel(resources: RemoteResources) {
    val context = LocalContext.current
    val memoryUsed = (resources.totalMemory - resources.availableMemory).coerceAtLeast(0L)
    val storageUsed = (resources.totalStorage - resources.availableStorage).coerceAtLeast(0L)
    val desktop = resources.desktop
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        strength = 0.60f,
        rimAlpha = 0.68f,
        elevation = 6.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 16.dp)) {
            Text("运行状态", color = AppleInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ResourceMetric(
                    label = "内存",
                    value = memoryUsed.asSize(context),
                    detail = "共 ${resources.totalMemory.asSize(context)}",
                    modifier = Modifier.weight(1f),
                )
                ResourceMetric(
                    label = "存储",
                    value = storageUsed.asSize(context),
                    detail = "共 ${resources.totalStorage.asSize(context)}",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(AppleHairline.copy(alpha = 0.48f)))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ResourceMetric(
                    label = "网络",
                    value = resources.network,
                    detail = if (resources.networkAvailable) "连接可用" else "请检查网络",
                    modifier = Modifier.weight(1f),
                )
                ResourceMetric(
                    label = "桌面",
                    value = desktop?.let { "${it.width} × ${it.height}" } ?: "未读取",
                    detail = when {
                        desktop == null -> "状态未知"
                        desktop.running || desktop.firstFrameReady -> "运行中"
                        desktop.lastError != null -> "需要检查"
                        else -> "待启动"
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun ResourceMetric(
    label: String,
    value: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, color = AppleSecondary, fontSize = 11.sp)
        Spacer(Modifier.height(3.dp))
        Text(
            value,
            color = AppleInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            detail,
            color = AppleSecondary,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConnectionValueCard(
    title: String,
    value: String,
    monospace: Boolean = false,
    onCopy: () -> Unit,
) {
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_400)
            copied = false
        }
    }
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        strength = 0.60f,
        rimAlpha = 0.68f,
        elevation = 6.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, color = AppleSecondary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(7.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    value,
                    color = AppleInk,
                    fontSize = if (monospace) 12.sp else 14.sp,
                    fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
                    modifier = Modifier.weight(1f),
                )
                ActionButton(
                    text = if (copied) "已复制" else "复制",
                    onClick = {
                        onCopy()
                        copied = true
                    },
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun RemoteActions(
    running: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionButton(
            text = if (running) "停止服务" else "启动服务",
            onClick = onToggle,
            modifier = Modifier.weight(1f),
            expand = true,
        )
        ActionButton(
            text = "刷新状态",
            onClick = onRefresh,
            modifier = Modifier.weight(1f),
            expand = true,
        )
    }
}

@Composable
private fun RemoteStepsCard(mode: RemoteDesktopMode) {
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        strength = 0.58f,
        rimAlpha = 0.66f,
        elevation = 5.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("连接步骤", color = AppleInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (mode == RemoteDesktopMode.USB) {
                    "1. 在手机开发者选项开启 USB 调试。\n2. 用 USB 连接电脑，并执行上面的端口转发命令。\n3. 在电脑浏览器打开 USB 访问地址并输入配对码。\n4. 首次访问 HTTPS 时选择“高级”→“继续访问”。"
                } else {
                    "1. 确保手机和电脑连接到同一个局域网。\n2. 浏览器打开上面的 HTTPS 地址并输入配对码。\n3. 首次访问选择“高级”→“继续访问”接受本机自签名证书。\n4. 点击画面后即可使用鼠标和键盘。"
                },
                color = AppleSecondary,
                fontSize = 12.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun RemoteLogCard(logs: String) {
    val tail = logs.lineSequence().filter { it.isNotBlank() }.toList().takeLast(8).joinToString("\n")
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        strength = 0.56f,
        rimAlpha = 0.64f,
        elevation = 5.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("最近日志", color = AppleInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(
                tail.ifBlank { "暂无日志" },
                color = AppleSecondary,
                fontSize = 10.sp,
                lineHeight = 16.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 9,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    expand: Boolean = false,
) {
    LinuxHubPressable(
        onClick = onClick,
        modifier = modifier,
        pressedScale = 0.94f,
    ) {
        LiquidGlassSurface(
            modifier = if (expand) Modifier.fillMaxWidth() else Modifier,
            shape = CircleShape,
            strength = 0.70f,
            rimAlpha = 0.82f,
            elevation = 4.dp,
        ) {
            Box(
                Modifier
                    .then(if (expand) Modifier.fillMaxWidth() else Modifier)
                    .padding(
                        horizontal = if (compact) 12.dp else 18.dp,
                        vertical = if (compact) 8.dp else 11.dp,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    color = AppleInk,
                    fontSize = if (compact) 11.sp else 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun readRemoteResources(context: Context): RemoteResources {
    val memoryInfo = ActivityManager.MemoryInfo()
    runCatching {
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(memoryInfo)
    }
    val storage = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    val network = connectivity?.activeNetwork
    val capabilities = network?.let(connectivity::getNetworkCapabilities)
    val networkName = when {
        capabilities == null -> "离线"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "移动网络"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "已连接"
    }
    return RemoteResources(
        availableMemory = memoryInfo.availMem,
        totalMemory = memoryInfo.totalMem,
        availableStorage = storage?.availableBytes ?: 0L,
        totalStorage = storage?.totalBytes ?: 0L,
        network = networkName,
        networkAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        desktop = runCatching { LinuxHubDesktopSession.status(context) }.getOrNull(),
    )
}

private fun Long.asSize(context: Context): String =
    if (this > 0L) Formatter.formatFileSize(context, this) else "—"

private fun copy(context: Context, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("LinuxHub", value))
}

private fun readAdbEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED) == 1
}.getOrDefault(false)

private fun openDeveloperSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(
                    android.net.Uri.parse("package:${context.packageName}"),
                ),
            )
        }
    }
}


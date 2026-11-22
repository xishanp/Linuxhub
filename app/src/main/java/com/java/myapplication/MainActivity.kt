package com.java.myapplication

import android.Manifest
import android.content.Intent
import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.core.app.NotificationManagerCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
// X11 remains embedded in the Compose desktop page.

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.doro.terminal.shell.R
import com.doro.terminal.shell.desktop.DesktopSessionService
import com.doro.terminal.shell.linux.GpuProfile
import com.doro.terminal.shell.linux.InitializationProgress
import com.doro.terminal.shell.linux.InstallComponent
import com.doro.terminal.shell.linux.InstallationTaskProgress
import com.doro.terminal.shell.linux.InstallationTaskStatus
import com.doro.terminal.shell.linux.LinuxAccount
import com.doro.terminal.shell.linux.LinuxContainerManager
import com.doro.terminal.shell.linux.LinuxProcessSession
import com.doro.terminal.shell.linux.SetupCatalog
import com.doro.terminal.shell.linux.TerminalLaunchSpec
import com.doro.terminal.shell.x11.LorieX11Runtime
import com.doro.terminal.shell.x11.X11RuntimeManager
import ai.lotus.liquidglass.LiquidGlassSurface
import ai.lotus.liquidglass.rememberLiquidPress

import com.java.myapplication.ui.theme.MyApplicationTheme
import com.termux.x11.LorieView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
// No external X11 activity launch dependencies.

private val Accent = AppleInk
private val PageBackground = AppleCanvas
private val MainText = AppleInk

private enum class SetupPage { PERMISSIONS, WELCOME, ACCOUNT, INSTALLING, COMPLETE, HOME, TERMINAL, SETTINGS, ABOUT, LOCAL_DESKTOP, USB_DESKTOP, LAN_DESKTOP }

class MainActivity : ComponentActivity() {
    private lateinit var containerManager: LinuxContainerManager
    @Volatile private var desktopVisible = false
    @Volatile private var pipEnabled = false
    @Volatile private var desktopInPictureInPicture = false
    @Volatile private var desktopInMultiWindow = false

    @Volatile private var desktopBackAction: (() -> Unit)? = null

    fun setDesktopBackAction(action: (() -> Unit)?) {
        desktopBackAction = action
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (desktopVisible && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                desktopBackAction?.invoke()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun updateDesktopWindowState(visible: Boolean, allowPip: Boolean) {
        val visibilityChanged = desktopVisible != visible
        desktopVisible = visible
        pipEnabled = allowPip
        if (visibilityChanged) desktopRuntime()?.setVisibleSurfaceActive(visible, this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder().setAspectRatio(currentPictureInPictureRatio())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder
                    .setAutoEnterEnabled(visible && allowPip)
                    .setSeamlessResizeEnabled(true)
            }
            setPictureInPictureParams(builder.build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (desktopVisible && pipEnabled && Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(currentPictureInPictureRatio()).build())
        }
    }

    private fun currentPictureInPictureRatio(): Rational {
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            resources.displayMetrics.widthPixels to resources.displayMetrics.heightPixels
        }
        val longSide = maxOf(width, height).coerceAtLeast(1)
        val shortSide = minOf(width, height).coerceAtLeast(1)
        val divisor = gcd(longSide, shortSide)
        return Rational(longSide / divisor, shortSide / divisor)
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        desktopInPictureInPicture = isInPictureInPictureMode
        updateDesktopFloatingMode()
        notifyDesktopMetricsChanged("pictureInPictureChanged")
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        desktopInMultiWindow = isInMultiWindowMode
        updateDesktopFloatingMode()
        notifyDesktopMetricsChanged("multiWindowChanged")
    }

    private fun updateDesktopFloatingMode() {
        desktopRuntime()?.setFloatingWindow(desktopInPictureInPicture || desktopInMultiWindow)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        notifyDesktopMetricsChanged("configurationChanged")
    }

    override fun onStart() {
        super.onStart()
        if (desktopVisible) desktopRuntime()?.setVisibleSurfaceActive(true, this)
    }

    override fun onResume() {
        super.onResume()
        if (desktopVisible) {
            desktopRuntime()?.requestVisibleRedraw("activityResumed", this)
            notifyDesktopMetricsChanged("activityResumed")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && desktopVisible) {
            window.decorView.postDelayed({
                desktopRuntime()?.requestVisibleRedraw("windowFocusGained", this)
                notifyDesktopMetricsChanged("windowFocusGained")
            }, 100L)
        }
    }

    override fun onStop() {
        if (desktopVisible) desktopRuntime()?.setVisibleSurfaceActive(false, this)
        super.onStop()
    }

    private fun desktopRuntime(): LorieX11Runtime? =
        runCatching { X11RuntimeManager.get(this).runtime as LorieX11Runtime }.getOrNull()

    private fun notifyDesktopMetricsChanged(reason: String) {
        if (desktopVisible) desktopRuntime()?.notifyWindowMetricsChanged(reason)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        containerManager = LinuxContainerManager(this)
        if (containerManager.isInitialized()) {
            // Package/profile repair can involve dpkg. Keep it off the
            // Compose main thread; terminal launch must stay responsive.
            Thread({
                runCatching { containerManager.prepareContainerRuntime() }
                    .onFailure { Log.e("LinuxHubRuntime", "启动维护失败", it) }
            }, "linuxhub-runtime-maintenance").apply {
                isDaemon = true
                start()
            }
        }
        val uiState = getSharedPreferences(
            DesktopSessionService.UI_STATE_PREFERENCES,
            MODE_PRIVATE,
        )
        val forceTerminalOnLaunch = uiState.getBoolean(
            DesktopSessionService.RETURN_TO_TERMINAL_KEY,
            false,
        )
        if (forceTerminalOnLaunch) {
            uiState.edit().remove(DesktopSessionService.RETURN_TO_TERMINAL_KEY).apply()
        }
        setContent {
            MyApplicationTheme {
                var launchSplashVisible by rememberSaveable {
                    mutableStateOf(savedInstanceState == null)
                }
                LaunchedEffect(Unit) {
                    if (launchSplashVisible) {
                        delay(1_100)
                        launchSplashVisible = false
                    }
                }
                if (launchSplashVisible) {
                    LinuxHubLaunchSplash()
                } else {
                    SetupApp(
                        initialized = containerManager.isInitialized(),
                        savedUsername = containerManager.loadConfig()?.username
                            ?: containerManager.loadInitializationState()?.username.orEmpty(),
                        interruptedMessage = containerManager.loadInitializationState()?.let {
                            it.failedMessage ?: "上次初始化还没有完成，输入密码后可以继续"
                        },
                        forceTerminalOnLaunch = forceTerminalOnLaunch,
                        initialize = ::initializeLinux
                    )
                }
            }
        }
    }

    private fun initializeLinux(
        username: String,
        password: String,
        components: Set<InstallComponent>,
        gpuProfile: GpuProfile,
        onProgress: (InitializationProgress) -> Unit,
        onComplete: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        Thread {
            runCatching {
                containerManager.initialize(username, password.toCharArray(), components, gpuProfile) { progress ->
                    runOnUiThread { onProgress(progress) }
                }
            }.onSuccess {
                runOnUiThread(onComplete)
            }.onFailure { error ->
                runOnUiThread { onFailure(error.message ?: "初始化失败") }
            }
        }.start()
    }
}

@Composable
private fun SetupApp(
    initialized: Boolean,
    savedUsername: String,
    interruptedMessage: String?,
    forceTerminalOnLaunch: Boolean,
    initialize: (
        String,
        String,
        Set<InstallComponent>,
        GpuProfile,
        (InitializationProgress) -> Unit,
        () -> Unit,
        (String) -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val terminalManager = remember { LinuxContainerManager(context) }
    val hasFileAccess = remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasFileAccess.value = result.values.all { it }
    }
    fun notificationsAllowed(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    fun batteryUnrestricted(): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)
    val hasNotifications = remember { mutableStateOf(notificationsAllowed()) }
    val batteryWhitelisted = remember { mutableStateOf(batteryUnrestricted()) }
    var selectedGpuProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedGpuProfile = GpuProfile.entries.firstOrNull { it.id == selectedGpuProfileId }
    var page by rememberSaveable {
        mutableStateOf(
            when {
                initialized -> SetupPage.HOME
                interruptedMessage != null -> SetupPage.ACCOUNT
                else -> SetupPage.WELCOME
            }
        )
    }
    var username by remember { mutableStateOf(savedUsername) }
    var password by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var progressFraction by remember { mutableFloatStateOf(0f) }
    var progressMessage by remember { mutableStateOf("正在准备初始化") }
    var installationTasks by remember { mutableStateOf(emptyList<InstallationTaskProgress>()) }
    var error by remember { mutableStateOf(interruptedMessage) }

    fun beginInitialization(user: String, pass: String, profile: GpuProfile) {
        username = user
        password = pass
        error = null
        progress = 5
        progressFraction = 0.05f
        progressMessage = "正在准备 Ubuntu"
        installationTasks = emptyList()
        page = SetupPage.INSTALLING
        initialize(
            user,
            pass,
            SetupCatalog.resolveSelection(emptySet()),
            profile,
            { state ->
                val nextFraction = (state.fraction ?: (state.percent / 100f)).coerceIn(0f, 1f)
                progress = maxOf(progress, state.percent)
                progressFraction = maxOf(progressFraction, nextFraction)
                progressMessage = state.message
                installationTasks = state.tasks
            },
            {
                password = ""
                progress = 100
                progressFraction = 1f
                page = SetupPage.COMPLETE
            },
            { message ->
                password = ""
                error = message
                if (installationTasks.isEmpty()) {
                    page = SetupPage.ACCOUNT
                } else {
                    progressMessage = "应用部署已停止，请展开失败任务查看原因"
                }
            }
        )
    }

    fun startInitialization(user: String, pass: String) {
        val profile = selectedGpuProfile
        if (profile == null) {
            error = "请选择手机芯片对应的 GPU 线路"
            page = SetupPage.PERMISSIONS
            return
        }
        beginInitialization(user, pass, profile)
    }

    LaunchedEffect(forceTerminalOnLaunch, initialized) {
        if (forceTerminalOnLaunch && initialized) {
            page = SetupPage.TERMINAL
        }
    }
    val terminalWorkspace = remember { TerminalWorkspace() }
    val containerConfig = remember { terminalManager.loadConfig() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, page) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (page == SetupPage.PERMISSIONS) {
                    hasFileAccess.value = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
                    else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    hasNotifications.value = notificationsAllowed()
                    batteryWhitelisted.value = batteryUnrestricted()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(
        enabled = page == SetupPage.SETTINGS ||
            page == SetupPage.TERMINAL ||
            page == SetupPage.ABOUT
    ) {
        page = SetupPage.HOME
    }

    AppleLiquidGlassScene(
        page = {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
                contentColor = MainText,
            ) {
                when (page) {
            SetupPage.PERMISSIONS -> PermissionPage(
                hasFileAccess = hasFileAccess.value,
                notificationsEnabled = hasNotifications.value,
                batteryWhitelisted = batteryWhitelisted.value,
                selectedGpuProfile = selectedGpuProfile,
                onGpuProfileSelected = { selectedGpuProfileId = it.id },
                onRequestFiles = {
                    if (Build.VERSION.SDK_INT >= 30) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    } else {
                        storagePermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                },
                onRequestNotifications = {
                    // targetSdk 28 does not produce a useful POST_NOTIFICATIONS
                    // runtime dialog on Android 13+. Open the per-app page so
                    // the user can grant notifications on every OEM.
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }.onFailure {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.parse("package:${context.packageName}"))
                            )
                        }
                    }
                },
                onRequestBattery = {
                    // ColorOS/realme often suppress the request-ignore dialog.
                    // Open the system battery-optimization list and let the user
                    // select LinuxHub manually.
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    }.onFailure {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                        }
                    }
                },
                onContinue = { page = SetupPage.ACCOUNT }
            )
            SetupPage.WELCOME -> WelcomePage {
                page = SetupPage.PERMISSIONS
            }
            SetupPage.ACCOUNT -> AccountPage(
                initialUsername = username,
                error = error,
                onStart = { user, pass ->
                    startInitialization(user, pass)
                }
            )
            SetupPage.INSTALLING -> InstallingPage(
                progressFraction = progressFraction,
                message = progressMessage,
                tasks = installationTasks,
                failureMessage = error,
                onReturnToAccount = {
                    page = SetupPage.ACCOUNT
                },
            )
            SetupPage.COMPLETE -> CompletePage { page = SetupPage.HOME }
            SetupPage.HOME -> HomePage(
                username = username.ifBlank { "ubuntu" },
                config = containerConfig,
                terminalCount = terminalWorkspace.sessions.size,
                onOpenTerminal = { page = SetupPage.TERMINAL },
                onOpenDesktop = { page = SetupPage.LOCAL_DESKTOP },
                onOpenUsb = { page = SetupPage.USB_DESKTOP },
                onOpenLan = { page = SetupPage.LAN_DESKTOP },
                onOpenAbout = { page = SetupPage.ABOUT },
            )
            SetupPage.TERMINAL -> TerminalPage(
                username = username.ifBlank { "ubuntu" },
                manager = terminalManager,
                workspace = terminalWorkspace,
            )
            SetupPage.SETTINGS -> TerminalSettingsPage()
            SetupPage.ABOUT -> LinuxHubAboutPage(
                onBack = { page = SetupPage.HOME },
            )
            SetupPage.LOCAL_DESKTOP -> X11DesktopPage(autoStartDesktop = true) { page = SetupPage.HOME }
            SetupPage.USB_DESKTOP -> RemoteDesktopPage(RemoteDesktopMode.USB) { page = SetupPage.HOME }
            SetupPage.LAN_DESKTOP -> RemoteDesktopPage(RemoteDesktopMode.LAN) { page = SetupPage.HOME }
                }
            }
        },
        overlay = {
            if (page == SetupPage.HOME || page == SetupPage.TERMINAL || page == SetupPage.SETTINGS) {
                val destination = when (page) {
                    SetupPage.HOME -> AppleDockDestination.HOME
                    SetupPage.TERMINAL -> AppleDockDestination.TERMINAL
                    else -> AppleDockDestination.SETTINGS
                }
                AppleLiquidGlassDock(
                    destination = destination,
                    onHome = { page = SetupPage.HOME },
                    onTerminal = { page = SetupPage.TERMINAL },
                    onSettings = { page = SetupPage.SETTINGS },
                    onDisplay = { page = SetupPage.LOCAL_DESKTOP },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp),
                )
            }
            if (page == SetupPage.TERMINAL) {
                TerminalTopBar(
                    username = username.ifBlank { "ubuntu" },
                    workspace = terminalWorkspace,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding(),
                )
            }
        }
    )
}

@Composable
private fun PageFrame(
    currentStep: Int,
    compact: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.White)) {
        val shortScreen = maxHeight < 700.dp
        val tablet = maxWidth >= 600.dp
        val landscape = maxWidth > maxHeight
        val horizontalPadding = if (tablet) 40.dp else 20.dp
        val contentWidth = when {
            maxWidth >= 1000.dp -> 720.dp
            tablet -> 600.dp
            else -> maxWidth
        }
        val topPadding = when {
            landscape -> 16.dp
            tablet -> 28.dp
            shortScreen -> 18.dp
            else -> 30.dp
        }
        val bottomPadding = if (shortScreen) 16.dp else 24.dp
        val headerGap = if (shortScreen) 20.dp else 28.dp

        Column(
            modifier = Modifier
                .width(contentWidth)
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = horizontalPadding)
                .padding(top = topPadding, bottom = bottomPadding),
            horizontalAlignment = Alignment.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.home_linuxhub_mark),
                    contentDescription = "LinuxHub",
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    text = "LinuxHub",
                    color = MainText,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(headerGap))
            content()
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(5) { index ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .background(
                        color = if (index <= currentStep) Accent else AppleHairline,
                        shape = RoundedCornerShape(50)
                    )
            )
        }
    }
}

@Composable
private fun PermissionPage(
    hasFileAccess: Boolean,
    notificationsEnabled: Boolean,
    batteryWhitelisted: Boolean,
    selectedGpuProfile: GpuProfile?,
    onGpuProfileSelected: (GpuProfile) -> Unit,
    onRequestFiles: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRequestBattery: () -> Unit,
    onContinue: () -> Unit,
) {
    PageFrame(currentStep = 1, compact = true) {
        Text("运行准备", fontSize = 30.sp, color = MainText, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Text(
            "初始化会安装 Ubuntu + GNOME，并把选定的 Adreno / Mali GPU 线路固化到容器。权限可以稍后补，但文件访问是开始初始化的必要条件。",
            color = MainText.copy(alpha = 0.78f),
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text("GPU 加速：Adreno / Mali", color = MainText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "GNOME Shell 始终保留 llvmpipe 安全合成；应用通过下面的线路使用厂商 GPU，失败会自动回退软件渲染。",
                color = MainText.copy(alpha = 0.68f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "请按手机芯片手动选择线路，不会替你自动猜测。",
                color = MainText.copy(alpha = 0.68f),
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
            Spacer(Modifier.height(8.dp))
            GpuRouteCard(
                selected = selectedGpuProfile == GpuProfile.QUALCOMM,
                title = "高通 / Adreno",
                detail = "OpenGL / Zink / Turnip / KGSL",
                onClick = { onGpuProfileSelected(GpuProfile.QUALCOMM) },
            )
            Spacer(Modifier.height(7.dp))
            GpuRouteCard(
                selected = selectedGpuProfile == GpuProfile.MEDIATEK,
                title = "天玑 / Mali",
                detail = "OpenGL / virpipe / VirGL / Mali",
                onClick = { onGpuProfileSelected(GpuProfile.MEDIATEK) },
            )
            Spacer(Modifier.height(12.dp))
            Text("运行权限", color = MainText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            PermissionCard(
                title = "所有文件访问",
                detail = "安装系统文件、共享目录和桌面资源",
                passed = hasFileAccess,
                action = if (hasFileAccess) "已授权" else "授权",
                onAction = onRequestFiles,
            )
            Spacer(Modifier.height(7.dp))
            PermissionCard(
                title = "通知权限",
                detail = "显示桌面运行状态和前台服务通知",
                passed = notificationsEnabled,
                action = if (notificationsEnabled) "已开启" else "去授权",
                onAction = onRequestNotifications,
            )
            Spacer(Modifier.height(7.dp))
            PermissionCard(
                title = "电池优化白名单",
                detail = "在系统电池优化列表里手动选择 LinuxHub",
                passed = batteryWhitelisted,
                action = if (batteryWhitelisted) "已加入" else "去设置",
                onAction = onRequestBattery,
            )
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton(
            text = when {
                !hasFileAccess && selectedGpuProfile == null -> "请选择 GPU 线路并授权所有文件"
                !hasFileAccess -> "请先授权所有文件"
                selectedGpuProfile == null -> "请选择 GPU 线路"
                else -> "继续创建 Linux 用户"
            },
            onClick = onContinue,
            enabled = hasFileAccess && selectedGpuProfile != null,
            fillWidth = true,
        )
    }
}

@Composable
private fun GpuRouteCard(
    selected: Boolean,
    title: String,
    detail: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val press = rememberLiquidPress(shape, scale = 1.025f, strength = 0.52f)
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth().then(press.modifier),
        shape = shape,
        strength = 0.48f,
        rimAlpha = 0.50f,
        elevation = 6.dp,
    ) {
        Column(
            Modifier
                .clickable(
                    interactionSource = press.interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    modifier = Modifier.weight(1f),
                    color = MainText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (selected) "已选" else "选择",
                    color = if (selected) Accent else AppleSecondary,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Text(detail, color = AppleSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun PermissionCard(
    title: String,
    detail: String,
    passed: Boolean,
    action: String,
    onAction: () -> Unit,
) {
    LiquidGlassSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        strength = 0.45f,
        rimAlpha = 0.46f,
        elevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = MainText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(detail, color = AppleSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            InlineGlassAction(
                text = action,
                onClick = onAction,
                color = if (passed) AppleSecondary else Accent,
            )
        }
    }
}

@Composable
private fun WelcomePage(onContinue: () -> Unit) {
    PageFrame(currentStep = 0, compact = true) {
        Text(
            "LinuxHub",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 32.sp,
            color = MainText,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Start
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Android 上的 Ubuntu + GNOME + GPU 加速",
            modifier = Modifier.fillMaxWidth(),
            color = MainText.copy(alpha = 0.78f),
            fontSize = 17.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Start
        )
        Spacer(Modifier.height(30.dp))
        Text(
            "Ubuntu 24.04  ·  GNOME 46  ·  Adreno / Mali 应用加速",
            modifier = Modifier.fillMaxWidth(),
            color = Accent,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            lineHeight = 24.sp,
            textAlign = TextAlign.Start
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "完整 Ubuntu 桌面一键初始化，首次初始化通常需要约 5–10 分钟，具体时间取决于设备性能。\n请预留至少 5 GB 可用空间。",
            modifier = Modifier.fillMaxWidth(),
            color = MainText,
            fontSize = 16.sp,
            lineHeight = 25.sp,
            textAlign = TextAlign.Start
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("开始初始化", onClick = onContinue)
    }
}

@Composable
private fun AccountPage(
    initialUsername: String,
    error: String?,
    onStart: (String, String) -> Unit
) {
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }

    PageFrame(currentStep = 1) {
        Text("创建 Linux 用户", fontSize = 30.sp, color = MainText, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(24.dp))
        Text("这个账户名和密码用于登录 Ubuntu，也可以安装软件。", color = MainText, fontSize = 17.sp)
        Spacer(Modifier.height(28.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("用户名") },
            singleLine = true
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmation,
            onValueChange = { confirmation = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("确认密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
        )
        val shownError = localError ?: error
        if (shownError != null) {
            Spacer(Modifier.height(12.dp))
            Text(shownError, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = "下一步",
            onClick = {
                localError = when {
                    !LinuxAccount.isValidUsername(username) -> "用户名格式无效"
                    password.length < 6 -> "密码至少需要六位"
                    password != confirmation -> "两次输入的密码不同"
                    else -> null
                }
                if (localError == null) onStart(username, password)
            },
        )
    }
}
@Composable
private fun InstallingPage(
    progressFraction: Float,
    message: String,
    tasks: List<InstallationTaskProgress>,
    failureMessage: String?,
    onReturnToAccount: () -> Unit,
) {
    val view = LocalView.current
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var activityNowMs by remember { mutableLongStateOf(System.nanoTime() / 1_000_000L) }
    var startedAt by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    DisposableEffect(view) {
        val previous = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = previous }
    }
    LaunchedEffect(Unit) {
        startedAt = SystemClock.elapsedRealtime()
        while (true) {
            now = SystemClock.elapsedRealtime()
            activityNowMs = System.nanoTime() / 1_000_000L
            delay(1_000)
        }
    }
    val elapsedSeconds = ((now - startedAt) / 1_000L).coerceAtLeast(0L)
    val elapsedText = "%02d:%02d".format(elapsedSeconds / 60L, elapsedSeconds % 60L)
    val targetFraction = progressFraction.coerceIn(0f, 1f)
    val shownFraction by animateFloatAsState(
        targetValue = targetFraction,
        animationSpec = tween(durationMillis = 700),
        label = "offline-install-progress",
    )
    val completedTasks = tasks.count { it.status == InstallationTaskStatus.SUCCESS }
    val failedTasks = tasks.filter {
        it.status == InstallationTaskStatus.FAILED || it.status == InstallationTaskStatus.TIMEOUT
    }
    val deploymentFailed = failureMessage != null || failedTasks.isNotEmpty()
    val runningTask = tasks.lastOrNull { it.status == InstallationTaskStatus.RUNNING }
    val latestActivityMs = tasks.maxOfOrNull { it.updatedAtMs } ?: 0L
    val activityAgeSeconds = if (latestActivityMs > 0L) {
        ((activityNowMs - latestActivityMs).coerceAtLeast(0L) / 1_000L)
    } else {
        null
    }
    val recentActivityText = when {
        activityAgeSeconds == null -> "最近活动：等待活动"
        activityAgeSeconds <= 3L -> "最近活动：刚刚"
        activityAgeSeconds < 60L -> "最近活动：${activityAgeSeconds} 秒前"
        activityAgeSeconds < 3_600L -> "最近活动：${activityAgeSeconds / 60L} 分钟前"
        else -> "最近活动：${activityAgeSeconds / 3_600L} 小时前"
    }
    val progressText = String.format(Locale.US, "%.1f%%", shownFraction * 100f)

    PageFrame(currentStep = 3) {
        Text("正在部署系统", fontSize = 30.sp, color = MainText, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(20.dp))
        Text(
            "完整 Ubuntu 桌面通常需要约 5–10 分钟。应用会逐项安装，列表可以上下滑动。",
            color = MainText,
            fontSize = 17.sp,
            lineHeight = 27.sp,
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("当前任务", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (tasks.isNotEmpty()) {
                Text(
                    "$completedTasks/${tasks.size}",
                    color = if (deploymentFailed) Color(0xFFC62828) else MainText.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            runningTask?.let { "正在安装 ${it.label}" } ?: message,
            color = if (deploymentFailed) Color(0xFFC62828) else MainText,
            fontSize = 17.sp,
            lineHeight = 25.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(13.dp))

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (tasks.isEmpty()) {
                item {
                    Text(message, color = MainText.copy(alpha = 0.72f), fontSize = 14.sp, lineHeight = 21.sp)
                }
            } else {
                items(items = tasks, key = { it.key }) { task ->
                    InstallationTaskRow(task)
                }
            }
        }

        if (deploymentFailed) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("看门狗：部署已停止", color = Color(0xFFC62828), fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                InlineGlassAction(
                    text = "返回账号",
                    onClick = onReturnToAccount,
                    color = Accent,
                )
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("看门狗：运行中", color = Color(0xFF2E7D32), fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                Text(recentActivityText, color = MainText.copy(alpha = 0.7f), fontSize = 14.sp)
            }
        }
        Spacer(Modifier.height(11.dp))
        LinearProgressIndicator(
            progress = { shownFraction },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = if (deploymentFailed) Color(0xFFC62828) else Accent,
            trackColor = Accent.copy(alpha = 0.14f),
        )
        Spacer(Modifier.height(9.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("部署进度 $progressText", color = MainText, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(
                if (deploymentFailed) "已停止" else "已用时 $elapsedText",
                color = MainText.copy(alpha = 0.7f),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun InstallationTaskRow(task: InstallationTaskProgress) {
    val statusColor = when (task.status) {
        InstallationTaskStatus.PENDING -> MainText.copy(alpha = 0.4f)
        InstallationTaskStatus.RUNNING -> Accent
        InstallationTaskStatus.SUCCESS -> Color(0xFF2E7D32)
        InstallationTaskStatus.FAILED -> Color(0xFFC62828)
        InstallationTaskStatus.TIMEOUT -> Color(0xFFB26A00)
    }
    val statusMark = when (task.status) {
        InstallationTaskStatus.PENDING -> "○"
        InstallationTaskStatus.RUNNING -> "●"
        InstallationTaskStatus.SUCCESS -> "✓"
        InstallationTaskStatus.FAILED -> "✕"
        InstallationTaskStatus.TIMEOUT -> "!"
    }
    val statusLabel = when (task.status) {
        InstallationTaskStatus.PENDING -> "等待"
        InstallationTaskStatus.RUNNING -> "安装中"
        InstallationTaskStatus.SUCCESS -> "已安装"
        InstallationTaskStatus.FAILED -> "失败"
        InstallationTaskStatus.TIMEOUT -> "超时"
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(statusMark, color = statusColor, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(9.dp))
            Text(
                task.label,
                modifier = Modifier.weight(1f),
                color = if (task.status == InstallationTaskStatus.PENDING) MainText.copy(alpha = 0.55f) else MainText,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(statusLabel, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (task.detail.isNotBlank() && task.status != InstallationTaskStatus.SUCCESS && task.status != InstallationTaskStatus.PENDING) {
            Text(
                task.detail.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty(),
                modifier = Modifier.padding(start = 24.dp, top = 2.dp, end = 54.dp),
                color = if (task.status == InstallationTaskStatus.FAILED) Color(0xFFC62828) else MainText.copy(alpha = 0.62f),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = if (task.status == InstallationTaskStatus.FAILED || task.status == InstallationTaskStatus.TIMEOUT) 3 else 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CompletePage(onEnter: () -> Unit) {
    PageFrame(currentStep = 4) {
        Text("Ubuntu + GNOME 已准备好", fontSize = 30.sp, color = MainText, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(26.dp))
        Text("Ubuntu + GNOME、中文环境和 Adreno / Mali GPU 线路已经配置完成。", color = MainText, fontSize = 19.sp)
        Spacer(Modifier.height(18.dp))
        Text("现在可以进入 Ubuntu。之后也可以随时从 LinuxHub 打开。\n首次进入桌面可能需要几秒钟。", color = MainText, fontSize = 17.sp)
        Spacer(Modifier.weight(1f))
        PrimaryButton("进入 Ubuntu", onClick = onEnter)
    }
}

@Composable
private fun TerminalTopBar(
    username: String,
    workspace: TerminalWorkspace,
    modifier: Modifier = Modifier,
) {
    val sessions = workspace.sessions
    val activeSession = workspace.activeSession

    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF1C1C1E)),
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                sessions.forEach { session ->
                    val tabShape = RoundedCornerShape(10.dp)
                    val tabPress = rememberLiquidPress(
                        tabShape,
                        scale = 1.018f,
                        strength = 0.42f,
                    )
                    val closeShape = CircleShape
                    val closePress = rememberLiquidPress(
                        closeShape,
                        scale = 1.08f,
                        strength = 0.50f,
                    )
                    LiquidGlassSurface(
                        modifier = Modifier
                            .width(148.dp)
                            .height(40.dp)
                            .then(tabPress.modifier),
                        shape = tabShape,
                        strength = if (session == activeSession) 0.42f else 0.28f,
                        rimAlpha = if (session == activeSession) 0.58f else 0.34f,
                        elevation = if (session == activeSession) 3.dp else 0.dp,
                    ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = tabPress.interaction,
                                indication = null,
                            ) { workspace.activeSession = session }
                            .padding(start = 12.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(4.dp)
                                    .background(
                                        if (session == activeSession) Color.White else Color.White.copy(alpha = 0.28f),
                                        CircleShape,
                                    )
                            )
                            Text(
                                "$username@ubuntu",
                                modifier = Modifier.weight(1f),
                                color = if (session == activeSession) Color.White else Color.White.copy(alpha = 0.56f),
                                fontSize = 12.sp,
                                fontWeight = if (session == activeSession) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .then(closePress.modifier)
                                .clickable(
                                    interactionSource = closePress.interaction,
                                    indication = null,
                                    enabled = sessions.size > 1,
                                ) {
                                    workspace.terminalViews.remove(session)?.close()
                                    workspace.sessions = sessions.filterNot { it == session }
                                    if (activeSession == session) {
                                        workspace.activeSession = workspace.sessions.first()
                                    }
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "×",
                                color = if (sessions.size > 1) Color.White.copy(alpha = 0.58f) else Color.White.copy(alpha = 0.20f),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Light,
                            )
                        }
                    }
                    }
                }
            }
            TerminalBarIconButton(
                onClick = {
                    workspace.terminalViews[workspace.activeSession]?.showKeyboard()
                },
                description = "打开终端键盘",
            ) {
                KeyboardGlyph(Color.White)
            }
            TerminalBarIconButton(
                onClick = {
                    val next = (sessions.maxOrNull() ?: 0) + 1
                    workspace.sessions = sessions + next
                    workspace.activeSession = next
                },
                description = "新建终端",
            ) {
                Text("+", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Light)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.10f)))
    }
}

@Composable
private fun KeyboardGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.06f, size.height * 0.20f),
            size = Size(size.width * 0.88f, size.height * 0.60f),
            cornerRadius = CornerRadius(2.4.dp.toPx()),
            style = Stroke(stroke),
        )
        val ys = listOf(0.38f, 0.53f)
        ys.forEach { y ->
            var x = 0.19f
            while (x <= 0.72f) {
                drawCircle(color, radius = 0.9.dp.toPx(), center = Offset(size.width * x, size.height * y))
                x += 0.13f
            }
        }
        drawLine(
            color = color,
            start = Offset(size.width * 0.28f, size.height * 0.68f),
            end = Offset(size.width * 0.72f, size.height * 0.68f),
            strokeWidth = stroke,
        )
    }
}

@Composable
private fun TerminalBarIconButton(
    onClick: () -> Unit,
    description: String,
    content: @Composable () -> Unit,
) {
    val shape = CircleShape
    val press = rememberLiquidPress(shape, scale = 1.08f, strength = 0.58f)
    LiquidGlassSurface(
        modifier = Modifier
            .size(42.dp)
            .then(press.modifier)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        shape = shape,
        strength = 0.48f,
        rimAlpha = 0.62f,
        elevation = 3.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = press.interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun TerminalPage(
    username: String,
    manager: LinuxContainerManager,
    workspace: TerminalWorkspace,
) {
    val context = LocalContext.current
    val activeSession = workspace.activeSession
    val terminalViews = workspace.terminalViews
    var launchSpec by remember(manager) { mutableStateOf<Result<TerminalLaunchSpec>?>(null) }
    LaunchedEffect(manager) {
        launchSpec = withContext(Dispatchers.IO) { runCatching { manager.terminalLaunchSpec() } }
    }

    fun createTerminal(id: Int, spec: TerminalLaunchSpec): DoroTerminalPane {
        return terminalViews.getOrPut(id) {
            DoroTerminalPane(
                context,
                spec.shellPath,
                spec.workingDirectory,
                spec.arguments,
                spec.environment
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AppleTerminal)
            .statusBarsPadding()
            .imePadding()
            .padding(top = 56.dp)
    ) {
        val preparedLaunchSpec = launchSpec
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            if (preparedLaunchSpec == null) {
                Text(
                    "正在准备终端…",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            } else if (preparedLaunchSpec.isSuccess) {
                androidx.compose.runtime.key(activeSession) {
                    AndroidView(
                        factory = { createTerminal(activeSession, preparedLaunchSpec.getOrThrow()) },
                        modifier = Modifier.fillMaxSize().background(AppleTerminal)
                    )
                }
            } else {
                Text(
                    "Ubuntu 启动失败：${preparedLaunchSpec.exceptionOrNull()?.message}",
                    modifier = Modifier.padding(16.dp),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                )
            }

        }
    }
}

@Composable
private fun ColumnScope.PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
) {
    val shape = RoundedCornerShape(32.dp)
    val press = rememberLiquidPress(shape, scale = 1.025f, strength = 0.78f)
    val surfaceModifier = Modifier
        .align(if (fillWidth) Alignment.CenterHorizontally else Alignment.End)
        .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
        .height(58.dp)
        .then(if (enabled) press.modifier else Modifier)

    LiquidGlassSurface(
        modifier = surfaceModifier,
        shape = shape,
        strength = 0.68f,
        rimAlpha = if (enabled) 0.74f else 0.42f,
        elevation = if (enabled) 7.dp else 2.dp,
    ) {
        Box(
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .fillMaxHeight()
                .clickable(
                    enabled = enabled,
                    interactionSource = press.interaction,
                    indication = null,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = if (enabled) AppleInk else AppleSecondary.copy(alpha = 0.58f),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun InlineGlassAction(
    text: String,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    val press = rowPress(shape, scale = 1.035f, strength = 0.48f)
    Box(
        modifier = modifier
            .then(press.modifier)
            .clickable(
                interactionSource = press.interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

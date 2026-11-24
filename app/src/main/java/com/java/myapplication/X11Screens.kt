package com.java.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import ai.lotus.liquidglass.LiquidGlassSurface
import ai.lotus.liquidglass.rememberLiquidPress
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.doro.terminal.shell.desktop.LinuxHubDesktopSession
import com.doro.terminal.shell.x11.LorieX11Runtime
import com.doro.terminal.shell.x11.X11RuntimeManager
import com.termux.x11.LorieView
import com.termux.x11.input.EmbeddedInputController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal val XAccent = AppleInk
internal val XBackground = AppleCanvas
internal val XText = AppleInk

@Composable
fun TerminalSettingsPage() {
    val context = LocalContext.current
    val prefs = remember { android.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    val displayMode = rememberStringPreference(prefs, "displayResolutionMode", "native")
    val touchMode = rememberStringPreference(prefs, "touchMode", "2")
    val availability = settingsAvailability(displayMode, touchMode)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.fillMaxSize().widthIn(max = 720.dp).statusBarsPadding().padding(top = 8.dp)
    ) {
        LinuxHubPageHeader("LinuxHub 设置")
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 130.dp)
        ) {
            SettingSection("GPU 加速")
            SwitchSetting("应用 GPU 加速（默认开启；GNOME 合成仍使用安全软件路径）", "gpuAcceleration", true, prefs)

            SettingSection("X11 显示输出")
            ChoiceSetting("分辨率模式", "displayResolutionMode", listOf("原生" to "native", "缩放" to "scaled", "精确" to "exact", "自定义" to "custom"), prefs)
            if (availability.showDisplayScale) {
                SliderSetting("显示缩放", "displayScale", 30f, 300f, 100f, prefs)
            }
            if (availability.showExactResolution) {
                ChoiceSetting("精确分辨率", "displayResolutionExact", listOf("1280×720" to "1280x720", "1280×800" to "1280x800", "1280×1024" to "1280x1024", "1600×900" to "1600x900", "1920×1080" to "1920x1080", "1920×1200" to "1920x1200"), prefs)
            }
            if (availability.showCustomResolution) {
                TextSetting("自定义分辨率", "displayResolutionCustom", "1280x800", prefs)
            }
            ChoiceSetting("过滤模式", "displayFilteringMode", listOf("最近邻" to "nearest", "双线性" to "bilinear"), prefs)
            if (availability.showDisplaySizingControls) {
                SwitchSetting("自动调整分辨率", "adjustResolution", false, prefs)
                SwitchSetting("拉伸显示", "displayStretch", false, prefs)
            }
            SwitchSetting("键盘弹出时压缩显示", "Reseed", true, prefs)
            SwitchSetting("画中画", "PIP", false, prefs)
            SwitchSetting("全屏", "fullscreen", true, prefs)
            ChoiceSetting("强制方向", "forceOrientation", listOf("自动" to "auto", "横屏" to "landscape", "竖屏" to "portrait"), prefs)
            SwitchSetting("延伸到挖孔区域", "hideCutout", true, prefs)
            SwitchSetting("保持屏幕常亮", "keepScreenOn", true, prefs)

            SettingSection("指针输入")
            ChoiceSetting("触摸模式", "touchMode", listOf("触摸板" to "1", "模拟触摸" to "2", "直接触摸" to "3"), prefs)
            if (availability.showScaleTouchpad) {
                SwitchSetting("缩放触摸板", "scaleTouchpad", true, prefs)
            }
            SwitchSetting("手写笔作为鼠标", "stylusIsMouse", false, prefs)
            SwitchSetting("捕获鼠标指针", "pointerCapture", false, prefs)
            ChoiceSetting("捕获指针变换", "transformCapturedPointer", listOf("无" to "no", "顺时针" to "c", "逆时针" to "cc", "倒置" to "ud", "自动" to "at"), prefs)
            SliderSetting("指针速度", "capturedPointerSpeedFactor", 1f, 300f, 100f, prefs)
            SwitchSetting("轻触移动", "tapToMove", false, prefs)
            SwitchSetting("忽略游戏手柄", "ignoreGamepadEvents", false, prefs)

            SettingSection("键盘输入")
            // Termux:X11's standalone extra-key bar and accessibility key filter are not
            // part of the embedded host. Their old preferences had no consumer here.
            SwitchSetting("优先使用扫描码", "preferScancodes", false, prefs)
            SwitchSetting("硬件键盘兼容模式", "hardwareKbdScancodesWorkaround", true, prefs)
            SwitchSetting("捕获 DeX Meta 键", "dexMetaKeyCapture", false, prefs)
            SwitchSetting("Esc 暂停按键拦截", "pauseKeyInterceptingWithEsc", false, prefs)
            SwitchSetting("强制字符输入", "enforceCharBasedInput", false, prefs)

            SettingSection("其他行为")
            SwitchSetting("同步剪贴板", "clipboardEnable", true, prefs)
            ChoiceSetting("上滑动作", "swipeUpAction", listOf("无动作" to "no action", "显示键盘" to "toggle soft keyboard"), prefs)
            ChoiceSetting("下滑动作", "swipeDownAction", listOf("无动作" to "no action", "显示键盘" to "toggle soft keyboard"), prefs)
            ChoiceSetting("返回键动作", "backButtonAction", listOf("显示键盘" to "toggle soft keyboard", "退出桌面" to "exit", "无动作" to "no action"), prefs)
            Spacer(Modifier.height(112.dp))
        }
    }
    }
}

@Composable
private fun rememberStringPreference(
    prefs: SharedPreferences,
    key: String,
    default: String,
): String {
    var value by remember(prefs, key) {
        mutableStateOf(prefs.getString(key, default) ?: default)
    }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == key) value = prefs.getString(key, default) ?: default
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable
private fun SettingSection(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(title, color = XAccent, fontSize = 19.sp)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SwitchSetting(
    title: String,
    key: String,
    default: Boolean,
    prefs: android.content.SharedPreferences,
    enabled: Boolean = true
) {
    var checked by remember(key) { mutableStateOf(prefs.getBoolean(key, default)) }
    val press = rememberLiquidPress(RoundedCornerShape(14.dp), scale = 1.012f, strength = 0.45f)
    Row(
        Modifier
            .fillMaxWidth()
            .then(press.modifier)
            .clickable(
                interactionSource = press.interaction,
                indication = null,
                enabled = enabled,
            ) {
                checked = !checked
                prefs.edit().putBoolean(key, checked).apply()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked, enabled = enabled, onCheckedChange = {
            checked = it
            prefs.edit().putBoolean(key, it).apply()
        })
        Text(title, color = XText, fontSize = 15.sp)
    }
}

@Composable
private fun ChoiceSetting(
    title: String,
    key: String,
    choices: List<*>,
    prefs: android.content.SharedPreferences
) {
    val options = choices.map { option ->
        if (option is Pair<*, *>) option.first.toString() to option.second.toString()
        else option.toString() to option.toString()
    }
    var value by remember(key) { mutableStateOf(prefs.getString(key, options.first().second) ?: options.first().second) }
    val press = rememberLiquidPress(RoundedCornerShape(14.dp), scale = 1.012f, strength = 0.45f)
    Row(
        Modifier
            .fillMaxWidth()
            .then(press.modifier)
            .clickable(interactionSource = press.interaction, indication = null) {
                val index = options.indexOfFirst { it.second == value }.coerceAtLeast(0)
                value = options[(index + 1) % options.size].second
                prefs.edit().putString(key, value).apply()
            }
            .padding(horizontal = 12.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(title, color = XText, fontSize = 15.sp)
        Text(options.firstOrNull { it.second == value }?.first ?: value, color = XAccent, fontSize = 15.sp)
    }
}

@Composable
private fun TextSetting(
    title: String,
    key: String,
    default: String,
    prefs: android.content.SharedPreferences
) {
    var value by remember(key) { mutableStateOf(prefs.getString(key, default) ?: default) }
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            value = next
            if (Regex("^[1-9]\\d{1,4}x[1-9]\\d{1,4}$").matches(next)) {
                prefs.edit().putString(key, next).apply()
            }
        },
        label = { Text(title) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    )
}

@Composable
private fun SliderSetting(
    title: String,
    key: String,
    min: Float,
    max: Float,
    default: Float,
    prefs: android.content.SharedPreferences
) {
    var value by remember(key) { mutableFloatStateOf(prefs.getInt(key, default.toInt()).toFloat()) }
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = XText, fontSize = 15.sp)
            Text(value.toInt().toString(), color = XAccent, fontSize = 15.sp)
        }
        Slider(value, { value = it }, valueRange = min..max, onValueChangeFinished = {
            prefs.edit().putInt(key, value.toInt()).apply()
        })
    }
}

private const val DESKTOP_LOG_TAIL_CHARS = 16 * 1024

private const val NATIVE_DISPLAY_MIGRATION = "doroNativeDisplayV2"

private fun migrateLegacyAspectOverride(prefs: SharedPreferences) {
    if (prefs.getBoolean(NATIVE_DISPLAY_MIGRATION, false)) return
    prefs.edit()
        .putString("displayResolutionMode", "native")
        .putInt("displayScale", 100)
        .putBoolean("displayStretch", false)
        .putBoolean("adjustResolution", false)
        .putBoolean("doroDesktopNaturalAspectV1", true)
        .putBoolean(NATIVE_DISPLAY_MIGRATION, true)
        .remove("doroDesktop16x10V2")
        .apply()
}

private fun awaitDesktopProcess(process: Process): Pair<Int, String> {
    val tail = StringBuilder()
    process.inputStream.bufferedReader().useLines { lines ->
        lines.forEach { line ->
            tail.append(line).append('\n')
            val overflow = tail.length - DESKTOP_LOG_TAIL_CHARS
            if (overflow > 0) tail.delete(0, overflow)
        }
    }
    return process.waitFor() to tail.toString()
}

@Composable
fun X11DesktopPage(autoStartDesktop: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val runtime = remember { X11RuntimeManager.get(context).runtime as LorieX11Runtime }
    val prefs = remember { android.preference.PreferenceManager.getDefaultSharedPreferences(context) }
    migrateLegacyAspectOverride(prefs)
    if (!prefs.getBoolean("doroPhoneKeyboardOnlyV1", false)) {
        prefs.edit()
            .putBoolean("showAdditionalKbd", false)
            .putBoolean("additionalKbdVisible", false)
            .putBoolean("showIMEWhileExternalConnected", true)
            .putBoolean("doroPhoneKeyboardOnlyV1", true)
            .apply()
    }
    if (!prefs.getBoolean("doroTrueFullscreenV2", false)) {
        prefs.edit()
            .putBoolean("fullscreen", true)
            .putBoolean("hideCutout", true)
            .putString("forceOrientation", "landscape")
            .putBoolean("doroTrueFullscreenV2", true)
            .apply()
    }
    val activity = context as? MainActivity
    var lorieView by remember { mutableStateOf<LorieView?>(null) }
    var inputController by remember { mutableStateOf<EmbeddedInputController?>(null) }
    val initialDesktopStatus = remember { LinuxHubDesktopSession.status(context) }
    var desktopRunning by remember { mutableStateOf(initialDesktopStatus.running) }
    var desktopStarting by remember { mutableStateOf(false) }
    var desktopFrameReady by remember { mutableStateOf(initialDesktopStatus.firstFrameReady) }
    var serverReady by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val desktopScope = rememberCoroutineScope()
    val desktopLaunchGuard = remember { AtomicBoolean(false) }
    val errorBackShape = RoundedCornerShape(24.dp)
    val errorBackPress = rememberLiquidPress(
        errorBackShape,
        scale = 1.025f,
        strength = 0.58f,
    )

    val launchDesktopSession = {
        if (autoStartDesktop && desktopLaunchGuard.compareAndSet(false, true)) {
            desktopStarting = true
            desktopScope.launch {
                try {
                    val process = withContext(Dispatchers.IO) {
                        LinuxHubDesktopSession.start(context)
                    }
                    desktopRunning = process.running
                    desktopFrameReady = process.firstFrameReady
                    desktopStarting = false
                    error = process.lastError
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    desktopStarting = false
                    desktopLaunchGuard.set(false)
                    error = failure.message ?: "GNOME桌面启动失败"
                }
            }
        }
    }

    val showPhoneKeyboard: () -> Unit = {
        lorieView?.let { view ->
            view.requestFocus()
            context.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    val handleDesktopBack: () -> Unit = {
        when (prefs.getString("backButtonAction", "toggle soft keyboard")) {
            "exit" -> onBack()
            "no action" -> Unit
            else -> showPhoneKeyboard()
        }
    }

    BackHandler { handleDesktopBack() }

    LaunchedEffect(runtime) {
        try {
            withContext(Dispatchers.IO) { runtime.start() }
            serverReady = true
            launchDesktopSession()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            error = failure.stackTraceToString()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val status = withContext(Dispatchers.IO) { LinuxHubDesktopSession.status(context) }
            desktopRunning = status.running
            desktopFrameReady = status.firstFrameReady
            if (status.stoppedByUser) {
                desktopLaunchGuard.set(false)
                onBack()
                return@LaunchedEffect
            }
            if (!status.running && status.lastError != null) error = status.lastError
            delay(2_000)
        }
    }

    DisposableEffect(runtime, prefs) {
        activity?.setDesktopBackAction(handleDesktopBack)
        val applyWindowPreferences = {
            val window = activity?.window
            activity?.updateDesktopWindowState(true, prefs.getBoolean("PIP", false))
            window?.setSoftInputMode(
                if (prefs.getBoolean("Reseed", true)) WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            )
            if (prefs.getBoolean("keepScreenOn", true)) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window?.attributes = window?.attributes?.also {
                    it.layoutInDisplayCutoutMode = if (prefs.getBoolean("hideCutout", true))
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                }
            }
            val fullscreen = prefs.getBoolean("fullscreen", true)
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, !fullscreen)
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (fullscreen) hide(WindowInsetsCompat.Type.systemBars())
                    else show(WindowInsetsCompat.Type.systemBars())
                }
            }
            when (prefs.getString("forceOrientation", "landscape")) {
                "landscape" -> activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                "portrait" -> activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else -> activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }

        }
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            inputController?.reloadPreferences()
            applyWindowPreferences()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        applyWindowPreferences()
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            activity?.setDesktopBackAction(null)
            activity?.updateDesktopWindowState(false, false)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity?.window?.let { window ->
                WindowCompat.setDecorFitsSystemWindows(window, true)
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                activity?.window?.attributes = activity?.window?.attributes?.also {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            desktopLaunchGuard.set(false)
            runtime.markInputControllerReady(false)
            inputController?.close()
            inputController = null
            lorieView?.let(runtime::unbindView)
            lorieView = null
        }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            error != null -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(error.orEmpty(), color = Color.White, fontSize = 12.sp)
                Spacer(Modifier.height(18.dp))
                LiquidGlassSurface(
                    modifier = Modifier
                        .height(48.dp)
                        .then(errorBackPress.modifier),
                    shape = errorBackShape,
                    strength = 0.58f,
                    rimAlpha = 0.66f,
                    elevation = 5.dp,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = errorBackPress.interaction,
                                indication = null,
                                role = androidx.compose.ui.semantics.Role.Button,
                                onClick = onBack,
                            )
                            .padding(horizontal = 22.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("返回 LinuxHub", color = Color.White)
                    }
                }
            }
            !serverReady -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("正在启动 X11…", color = Color.White, fontSize = 17.sp)
            }
            !desktopFrameReady -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (desktopStarting) "正在启动 GNOME 桌面…" else "正在等待 GNOME 首帧…",
                    color = Color.White,
                    fontSize = 17.sp,
                )
            }
            else -> AndroidView(
                factory = { ctx ->
                    LorieView(ctx).also { view ->
                        lorieView = view
                        desktopScope.launch {
                            runCatching {
                                val hostActivity = requireNotNull(activity) { "X11输入需要Activity宿主" }
                                inputController = EmbeddedInputController(hostActivity, view)
                                runtime.bindView(view, hostActivity)
                                withContext(Dispatchers.IO) { runtime.connectBoundView() }
                                runtime.markInputControllerReady(true)
                                inputController?.reloadPreferences()
                                view.requestFocus()
                            }.onFailure {
                                runtime.markInputControllerReady(false)
                                error = it.message ?: "X11 画面绑定失败"
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

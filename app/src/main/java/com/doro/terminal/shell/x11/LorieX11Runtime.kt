package com.doro.terminal.shell.x11

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.View
import com.doro.terminal.shell.desktop.DesktopFrameReadiness
import com.doro.terminal.shell.desktop.DesktopDiagnostics
import com.doro.terminal.shell.desktop.RemoteVideoProfilePolicy
import com.termux.x11.LorieView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LorieX11Runtime(
    context: Context,
    private val displayNumber: Int = 0
) : X11Runtime {
    data class RuntimeStatus(
        val state: String,
        val serviceBound: Boolean,
        val serverConnected: Boolean,
        val rendererConnected: Boolean,
        val surfaceReady: Boolean,
        val inputReady: Boolean,
        val rootFrameWidth: Int,
        val rootFrameHeight: Int,
        val firstFrameReady: Boolean,
    ) {
        val rootFrameReady: Boolean
            get() = rootFrameWidth in 1..8192 && rootFrameHeight in 1..8192

        val desktopTransportReady: Boolean
            get() = serverConnected && rendererConnected && surfaceReady && inputReady
    }

    companion object {
        private const val TAG = "LinuxHubX11"
        private const val STATUS_ENDPOINT_CREATED = 1
        private const val STATUS_SERVER_CONNECTED = 2
    }

    private val appContext = context.applicationContext
    private var view: LorieView? = null
    private var boundActivity: Activity? = null
    private var headlessView: LorieView? = null
    @Volatile private var remoteSurface: Surface? = null
    @Volatile private var remoteWidth: Int = 0
    @Volatile private var remoteHeight: Int = 0
    private var service: IBinder? = null
    private var bound = false
    private var connection: ParcelFileDescriptor? = null
    private var activeConnection: ServiceConnection? = null
    @Volatile private var attachedSurface: Surface? = null
    private var attachedWidth = 0
    private var attachedHeight = 0
    @Volatile private var inputControllerReady = false
    @Volatile private var desktopFrameReady = false
    override val display: String = ":$displayNumber"
    override var state: X11State = X11State.STOPPED
        private set

    private fun transact(code: Int, write: (Parcel) -> Unit = {}): Parcel {
        val binder = requireNotNull(service) { "X11服务未连接" }
        check(binder.isBinderAlive && binder.pingBinder()) { "X11服务进程已退出" }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(X11_DESCRIPTOR)
            write(data)
            check(binder.transact(code, data, reply, 0)) { "X11服务通信失败" }
            reply.readException()
            return reply
        } catch (failure: DeadObjectException) {
            reply.recycle()
            service = null
            state = X11State.FAILED
            throw IllegalStateException("X11服务启动时崩溃，请查看 X11ServerService 日志", failure)
        } catch (failure: RemoteException) {
            reply.recycle()
            service = null
            state = X11State.FAILED
            throw IllegalStateException("X11服务通信异常", failure)
        } catch (failure: Throwable) {
            reply.recycle()
            throw failure
        } finally {
            data.recycle()
        }
    }

    override fun start() {
        synchronized(this) {
            if (state == X11State.RUNNING_HEADLESS || state == X11State.RUNNING_ATTACHED) return
            check(state != X11State.STARTING) { "X11正在启动" }
            desktopFrameReady = false
            state = X11State.STARTING
        }
        DesktopDiagnostics.append(appContext, "x11-runtime", "start requested")
        val latch = CountDownLatch(1)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                DesktopDiagnostics.append(appContext, "x11-runtime", "service disconnected")
                service = null
                connection = null
                state = X11State.FAILED
            }
        }
        activeConnection = serviceConnection
        try {
            X11ServerService.start(appContext)
            bound = appContext.bindService(
                Intent(appContext, X11ServerService::class.java),
                serviceConnection,
                Context.BIND_AUTO_CREATE
            )
            check(bound) { "无法绑定X11服务" }
            check(latch.await(5, TimeUnit.SECONDS)) { "X11服务连接超时" }
            val reply = transact(X11_TRANSACTION_START) {
                it.writeStringArray(
                    arrayOf(display, "-ac", "-nolisten", "tcp", "-disable-gpu-present")
                )
            }
            val started = reply.readInt() != 0
            reply.recycle()
            check(started) { "X11 Server启动失败" }
            state = X11State.RUNNING_HEADLESS
            DesktopDiagnostics.append(appContext, "x11-runtime", "X11 service START returned true")
        } catch (failure: Throwable) {
            DesktopDiagnostics.append(appContext, "x11-runtime", "start failed: $failure")
            state = X11State.FAILED
            if (bound) runCatching { appContext.unbindService(serviceConnection) }
            runCatching { X11ServerService.stop(appContext) }
            bound = false
            activeConnection = null
            service = null
            throw failure
        }
    }

    /**
     * Waits for the visible Lorie surface and every transport needed to show
     * the desktop.  GNOME may start successfully while this transport is
     * still headless; that produces a live shell with a permanently black
     * SurfaceView, so callers must not launch the desktop before this gate
     * succeeds.
     */
    fun awaitVisibleTransport(timeoutMs: Long = 12_000L): RuntimeStatus {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val recoveryAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        var surfaceRecoveryAttempted = false
        var status = runtimeStatus()
        while (!status.desktopTransportReady && System.nanoTime() < deadline) {
            ensureVisibleSurfaceAttached()
            status = runtimeStatus()
            if (status.desktopTransportReady) break
            if (!status.surfaceReady && !surfaceRecoveryAttempted && System.nanoTime() >= recoveryAt) {
                surfaceRecoveryAttempted = true
                recreateVisibleSurface()
            }
            Thread.sleep(100)
        }
        if (!status.desktopTransportReady) {
            DesktopDiagnostics.append(
                appContext,
                "x11-runtime",
                "visible transport timeout state=${status.state} server=${status.serverConnected} " +
                    "renderer=${status.rendererConnected} surface=${status.surfaceReady} input=${status.inputReady}",
            )
        }
        check(status.desktopTransportReady) {
            "X11画面未就绪: state=${status.state} server=${status.serverConnected} " +
                "renderer=${status.rendererConnected} surface=${status.surfaceReady} input=${status.inputReady}"
        }
        DesktopDiagnostics.append(appContext, "x11-runtime", "visible transport ready")
        return status
    }

    /**
     * Waits until the X11 root frame contains a painted desktop, not merely a
     * connected Surface. A black Surface can otherwise look like a hung app.
     */
    fun awaitFirstDesktopFrame(timeoutMs: Long = 45_000L): RuntimeStatus {
        val startedAt = System.nanoTime()
        val deadline = startedAt + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastMetrics = DesktopFrameReadiness.Metrics(0, 0, 0.0)
        var lastFailure: Throwable? = null
        var nextLogAt = startedAt
        while (System.nanoTime() < deadline) {
            if (desktopFrameReady) return runtimeStatus()
            val frame = runCatching { captureFrame(attempts = 1) }
                .onFailure { lastFailure = it }
                .getOrNull()
            if (frame != null) {
                var sample: Bitmap? = null
                try {
                    val scale = minOf(1.0, 96.0 / maxOf(frame.width, frame.height))
                    val sampleWidth = (frame.width * scale).toInt().coerceAtLeast(1)
                    val sampleHeight = (frame.height * scale).toInt().coerceAtLeast(1)
                    sample = Bitmap.createScaledBitmap(frame, sampleWidth, sampleHeight, false)
                    val pixels = IntArray(sampleWidth * sampleHeight)
                    sample.getPixels(pixels, 0, sampleWidth, 0, 0, sampleWidth, sampleHeight)
                    lastMetrics = DesktopFrameReadiness.analyze(pixels)
                    if (lastMetrics.ready) {
                        desktopFrameReady = true
                        DesktopDiagnostics.append(
                            appContext,
                            "x11-runtime",
                            "first desktop frame ready elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000} " +
                                "visiblePermille=${lastMetrics.visiblePermille} " +
                                "averageLuma=${"%.1f".format(lastMetrics.averageLuma)}",
                        )
                        return runtimeStatus()
                    }
                } finally {
                    sample?.recycle()
                    frame.recycle()
                }
            }
            val now = System.nanoTime()
            if (now >= nextLogAt) {
                DesktopDiagnostics.append(
                    appContext,
                    "x11-runtime",
                    "waiting for first desktop frame visiblePermille=${lastMetrics.visiblePermille} " +
                        "averageLuma=${"%.1f".format(lastMetrics.averageLuma)} " +
                        "captureError=${lastFailure?.javaClass?.simpleName ?: "none"}",
                )
                nextLogAt = now + TimeUnit.SECONDS.toNanos(5)
            }
            Thread.sleep(350)
        }
        DesktopDiagnostics.append(
            appContext,
            "x11-runtime",
            "first desktop frame timeout visiblePermille=${lastMetrics.visiblePermille} " +
                "averageLuma=${"%.1f".format(lastMetrics.averageLuma)}",
        )
        throw IllegalStateException(
            "GNOME 桌面首帧超时：画面仍未出现（可见像素 ${lastMetrics.visiblePermille}‰）",
            lastFailure,
        )
    }

    fun isDesktopFrameReady(): Boolean = desktopFrameReady

    private fun ensureVisibleSurfaceAttached(): Boolean {
        val target = view ?: return false
        val surface = target.holder.surface
        if (!surface.isValid) return false
        if (state == X11State.RUNNING_HEADLESS) {
            attachSurface(
                surface,
                target.width.coerceAtLeast(1),
                target.height.coerceAtLeast(1),
                target.resources.displayMetrics.densityDpi,
            )
        }
        return attachedSurface?.isValid == true
    }

    private fun recreateVisibleSurface() {
        val target = view ?: return
        DesktopDiagnostics.append(appContext, "x11-runtime", "surface recovery requested")
        target.post {
            if (view !== target) return@post
            target.visibility = View.INVISIBLE
            target.post {
                if (view !== target) return@post
                target.visibility = View.VISIBLE
                target.requestLayout()
                target.requestRedraw("surfaceRecovery")
            }
        }
    }

    /**
     * Returns the transport state for UI/session decisions.  `RUNNING_HEADLESS`
     * is deliberately not used as proof that GNOME can start.
     */
    fun runtimeStatus(): RuntimeStatus {
        val serviceFlags = queryServiceFlags()
        val renderer = runCatching { LorieView.connected() }.getOrDefault(false)
        val frameSize = if (renderer) {
            runCatching { LorieView.captureFrameSize() }.getOrDefault(0L)
        } else 0L
        return RuntimeStatus(
            state = state.name,
            serviceBound = service != null,
            serverConnected = serviceFlags and STATUS_SERVER_CONNECTED != 0,
            rendererConnected = renderer,
            surfaceReady = attachedSurface?.isValid == true,
            inputReady = inputControllerReady,
            rootFrameWidth = (frameSize ushr 32).toInt(),
            rootFrameHeight = frameSize.toInt(),
            firstFrameReady = desktopFrameReady,
        )
    }

    fun markInputControllerReady(ready: Boolean) {
        inputControllerReady = ready
        DesktopDiagnostics.append(
            appContext,
            "x11-runtime",
            "inputControllerReady=$ready renderer=${runCatching { LorieView.connected() }.getOrDefault(false)}",
        )
    }

    private fun queryServiceFlags(): Int =
        runCatching {
            val reply = transact(X11_TRANSACTION_STATUS)
            try { reply.readInt() } finally { reply.recycle() }
        }.getOrDefault(0)

    private fun awaitServerConnection(timeoutMs: Long, label: String) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var flags = 0
        while (System.nanoTime() < deadline) {
            flags = queryServiceFlags()
            if (flags and STATUS_SERVER_CONNECTED != 0) {
                DesktopDiagnostics.append(appContext, "x11-runtime", "$label server connected flags=$flags")
                return
            }
            Thread.sleep(50)
        }
        DesktopDiagnostics.append(appContext, "x11-runtime", "$label server connection timeout flags=$flags")
        throw IllegalStateException("X11服务端未完成连接（$label），flags=$flags")
    }

    /**
     * Keeps the X server's renderer transport connected without an Activity or
     * Surface. This is the headless transport used by the local desktop session.
     */
    fun connectHeadless(width: Int = 1280, height: Int = 1024) {
        Log.i(TAG, "connectHeadless requested=${width}x$height current=${state.name} visible=${view != null}")
        if (state == X11State.STOPPED || state == X11State.FAILED) start()
        if (!LorieView.connected()) {
            if (view == null) {
                runOnMainThreadSync("无头X11视图启动超时") {
                    if (headlessView == null) headlessView = LorieView(appContext)
                }
            }

            var descriptor: ParcelFileDescriptor? = null
            for (attempt in 0 until 40) {
                val reply = transact(X11_TRANSACTION_CONNECTION)
                descriptor = if (reply.readInt() != 0)
                    ParcelFileDescriptor.CREATOR.createFromParcel(reply) else null
                reply.recycle()
                if (descriptor != null) break
                Thread.sleep(50)
            }
            connection = descriptor
            val fd = requireNotNull(connection) { "X11连接等待超时" }.detachFd()
            runOnMainThreadSync("X11连接建立超时") {
                Log.i(TAG, "Connecting X11 renderer on main Looper")
                LorieView.connect(fd)
            }
        }
        check(LorieView.connected()) { "无头X11客户端连接失败" }
        awaitServerConnection(10_000, "headless")
        if (attachedSurface?.isValid == true) {
            Log.i(TAG, "X11 transport ready; keeping visible Surface dimensions, not ${width}x$height")
        } else {
            sendHeadlessWindowSize(width, height)
        }
    }

    @Synchronized
    fun attachedSize(): Pair<Int, Int>? =
        if (state == X11State.RUNNING_ATTACHED && attachedWidth > 0 && attachedHeight > 0) {
            attachedWidth to attachedHeight
        } else {
            null
        }

    /**
     * Routes the Xlorie EGL renderer directly into a MediaCodec input Surface.
     * This is the remote-stream path: no screen capture and no CPU pixel copy.
     */
    fun attachRemoteSurface(surface: Surface, width: Int, height: Int) {
        require(width in 320..3840 && height in 240..2160) { "远程编码尺寸超出范围" }
        runOnMainThreadSync("远程编码 Surface 绑定超时") {
            check(state == X11State.RUNNING_HEADLESS || state == X11State.RUNNING_ATTACHED) {
                "X11 尚未运行，无法绑定远程编码 Surface"
            }
            if (state == X11State.RUNNING_ATTACHED) {
                view?.detachRuntimeSurface()
                attachedSurface = null
                attachedWidth = 0
                attachedHeight = 0
                state = X11State.RUNNING_HEADLESS
            }
            val target = headlessView ?: LorieView(appContext).also { headlessView = it }
            target.attachRuntimeSurface(surface, width, height, width, height)
            transact(X11_TRANSACTION_CONTINUOUS_FRAMES) { it.writeInt(1) }.recycle()
            remoteSurface = surface
            remoteWidth = width
            remoteHeight = height
            Log.i(TAG, "Attached remote H.264 Surface ${width}x$height")
        }
    }

    fun detachRemoteSurface() {
        Handler(Looper.getMainLooper()).post {
            if (remoteSurface == null) return@post
            runCatching {
                transact(X11_TRANSACTION_CONTINUOUS_FRAMES) { it.writeInt(0) }.recycle()
            }
            headlessView?.detachRuntimeSurface()
            remoteSurface = null
            remoteWidth = 0
            remoteHeight = 0
            Log.i(TAG, "Detached remote H.264 Surface")
        }
    }

    fun sendHeadlessWindowSize(width: Int, height: Int) {
        require(width in 320..3840 && height in 240..2160) { "桌面尺寸超出范围" }
        Handler(Looper.getMainLooper()).post {
            if (attachedSurface?.isValid == true) {
                Log.d(TAG, "Ignoring headless resize ${width}x$height while attached ${attachedWidth}x$attachedHeight")
                return@post
            }
            Log.i(TAG, "Applying headless resize ${width}x$height")
            // Match the output name used by the packaged xfce4-desktop profile.
            // A synthetic output name creates monitorbuiltin-headless without any
            // wallpaper defaults, so xfdesktop falls back to xfce-shapes.svg.
            LorieView.sendWindowChange(
                width,
                height,
                RemoteVideoProfilePolicy.forSize(width, height).frameRate,
                "builtin",
            )
        }
    }

    /**
     * Applies a browser viewport size and waits until Xlorie publishes the new
     * root buffer. MediaCodec must not attach a Surface for the new size while
     * the X server still reports the previous root geometry.
     */
    fun ensureHeadlessSize(width: Int, height: Int) {
        require(width in 320..RemoteVideoProfilePolicy.MAX_WIDTH &&
            height in 240..RemoteVideoProfilePolicy.MAX_HEIGHT) { "远端桌面尺寸超出范围" }
        if (state == X11State.RUNNING_ATTACHED) return
        val evenWidth = width and -2
        val evenHeight = height and -2
        val current = runCatching { LorieView.captureFrameSize() }.getOrDefault(0L)
        val currentWidth = (current ushr 32).toInt()
        val currentHeight = current.toInt()
        if (currentWidth == evenWidth && currentHeight == evenHeight) return

        sendHeadlessWindowSize(evenWidth, evenHeight)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2_000)
        while (System.nanoTime() < deadline) {
            val size = runCatching { LorieView.captureFrameSize() }.getOrDefault(0L)
            val readyWidth = (size ushr 32).toInt()
            val readyHeight = size.toInt()
            if (readyWidth == evenWidth && readyHeight == evenHeight) {
                Log.i(TAG, "Headless root resized to ${readyWidth}x$readyHeight")
                return
            }
            Thread.sleep(25)
        }
        Log.w(TAG, "Timed out waiting for headless root ${evenWidth}x$evenHeight")
    }

    fun setVisibleSurfaceActive(active: Boolean, owner: Activity) {
        Handler(Looper.getMainLooper()).post {
            if (boundActivity !== owner) return@post
            view?.setWindowVisible(active)
            if (active) view?.requestRedraw("activityVisible")
        }
    }

    fun requestVisibleRedraw(reason: String, owner: Activity) {
        Handler(Looper.getMainLooper()).post {
            if (boundActivity !== owner) return@post
            view?.requestRedraw(reason)
        }
    }

    fun notifyWindowMetricsChanged(reason: String) {
        Handler(Looper.getMainLooper()).post {
            view?.notifyWindowMetricsChanged(reason)
        }
    }

    fun setFloatingWindow(frozen: Boolean) {
        Handler(Looper.getMainLooper()).post {
            view?.freezeDimensions(frozen)
        }
    }

    private fun runOnMainThreadSync(timeoutMessage: String, action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return
        }
        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        Handler(Looper.getMainLooper()).post {
            try {
                action()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { timeoutMessage }
        failure.get()?.let { throw IllegalStateException("X11主线程操作失败", it) }
    }

    fun captureFrame(): Bitmap = captureFrame(attempts = 3)

    private fun captureFrame(attempts: Int): Bitmap {
        check(state == X11State.RUNNING_HEADLESS || state == X11State.RUNNING_ATTACHED) {
            "X11没有可截取的运行帧（state=${state.name}）"
        }
        val result = AtomicReference<Bitmap?>()
        var lastFailure: Throwable? = null
        repeat(attempts.coerceAtLeast(1)) { attempt ->
            runCatching {
                runOnMainThreadSync("X11帧捕获主线程超时") {
                    val target = view ?: headlessView ?: error("X11视图尚未创建")
                    val frameSize = LorieView.captureFrameSize()
                    val width = (frameSize ushr 32).toInt()
                    val height = frameSize.toInt()
                    check(width in 1..8192 && height in 1..8192) {
                        "X11根帧尺寸无效: ${width}x$height"
                    }
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    check(target.captureRuntimeFrame(bitmap)) {
                        "X11 native 帧缓冲读取失败"
                    }
                    result.set(bitmap)
                }
            }.onFailure {
                lastFailure = it
                if (attempt + 1 < attempts) Thread.sleep(100)
            }
            result.get()?.let { return it }
        }
        throw IllegalStateException("X11帧捕获失败", lastFailure)
    }

    @Synchronized
    fun bindView(target: LorieView, owner: Activity) {
        Log.i(TAG, "Binding visible LorieView current=${state.name} owner=${owner.javaClass.simpleName}")
        check(state != X11State.STOPPED && state != X11State.FAILED) { "X11没有运行，无法绑定画面" }
        val previous = view
        if (previous !== target) {
            previous?.setSurfaceLifecycleListener(null)
            view = target
        }
        boundActivity = owner
        target.resetWindowChangeCommit()
        target.setSurfaceLifecycleListener(object : LorieView.SurfaceLifecycleListener {
            override fun onSurfaceAvailable(surface: Surface, width: Int, height: Int) {
                if (view !== target) {
                    Log.i(TAG, "Ignoring stale Surface attach from ${owner.javaClass.simpleName}")
                    return
                }
                when (state) {
                    X11State.RUNNING_HEADLESS, X11State.RUNNING_ATTACHED ->
                        attachSurface(surface, width, height, target.resources.displayMetrics.densityDpi)
                    else -> Unit
                }
                target.requestRedraw("surfaceAvailable")
            }

            override fun onSurfaceDestroyed() {
                if (view !== target) {
                    Log.i(TAG, "Ignoring stale Surface detach from ${owner.javaClass.simpleName}")
                    return
                }
                detachSurface()
            }
        })
        target.requestRedraw("bindVisibleView")
    }

    /** Performs the blocking visible-renderer handshake outside the UI thread. */
    fun connectBoundView() {
        val target = synchronized(this) { view ?: error("X11 SurfaceView尚未绑定") }
        if (!LorieView.connected()) {
            var descriptor: ParcelFileDescriptor? = null
            for (attempt in 0 until 40) {
                val reply = transact(X11_TRANSACTION_CONNECTION)
                descriptor = if (reply.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(reply) else null
                reply.recycle()
                if (descriptor != null) break
                Thread.sleep(50)
            }
            connection = descriptor
            val fd = requireNotNull(connection) { "X11连接等待超时" }.detachFd()
            runOnMainThreadSync("X11可见连接建立超时") { LorieView.connect(fd) }
        }
        check(LorieView.connected()) { "X11客户端连接失败" }
        awaitServerConnection(5_000, "visible")
        if (target.holder.surface.isValid) {
            runOnMainThreadSync("X11 Surface绑定超时") {
                attachSurface(
                    target.holder.surface,
                    target.width.coerceAtLeast(1),
                    target.height.coerceAtLeast(1),
                    target.resources.displayMetrics.densityDpi,
                )
                target.requestRedraw("connectBoundView")
            }
        }
    }

    @Synchronized
    override fun attachSurface(surface: Surface, width: Int, height: Int, densityDpi: Int) {
        check(state == X11State.RUNNING_HEADLESS || state == X11State.RUNNING_ATTACHED)
        if (state == X11State.RUNNING_ATTACHED && attachedSurface == surface &&
            attachedWidth == width && attachedHeight == height) {
            Log.d(TAG, "Ignoring duplicate Surface attach ${width}x$height")
            return
        }
        state = X11State.ATTACHING
        try {
            Log.i(TAG, "Attaching Surface ${width}x$height density=$densityDpi")
            requireNotNull(view) { "X11 SurfaceView尚未绑定" }.attachRuntimeSurface(surface, width, height)
            attachedSurface = surface
            attachedWidth = width
            attachedHeight = height
            state = X11State.RUNNING_ATTACHED
        } catch (failure: Throwable) {
            state = X11State.RUNNING_HEADLESS
            throw failure
        }
    }

    @Synchronized
    override fun resize(width: Int, height: Int, densityDpi: Int) {
        if (state != X11State.RUNNING_ATTACHED || width <= 0 || height <= 0) return
        val target = view ?: return
        if (!target.holder.surface.isValid) return
        Log.i(TAG, "Queueing attached Surface resize ${width}x$height density=$densityDpi")
        target.updateRuntimeSurfaceSize(width, height)
        attachedWidth = width
        attachedHeight = height
    }

    @Synchronized
    fun unbindView(target: LorieView) {
        if (view !== target) {
            Log.i(TAG, "Ignoring stale view unbind; current view belongs to another Activity")
            return
        }
        Log.i(TAG, "Unbinding visible LorieView current=${state.name}")
        target.setSurfaceLifecycleListener(null)
        detachSurface()
        view = null
        boundActivity = null
    }

    @Synchronized
    override fun detachSurface() {
        if (state != X11State.RUNNING_ATTACHED && state != X11State.ATTACHING) return
        state = X11State.DETACHING
        try {
            Log.i(TAG, "Detaching Surface ${attachedWidth}x$attachedHeight")
            view?.detachRuntimeSurface()
        } finally {
            attachedSurface = null
            attachedWidth = 0
            attachedHeight = 0
            state = X11State.RUNNING_HEADLESS
        }
    }

    @Synchronized
    override fun stop() {
        Log.i(TAG, "Stopping X11 runtime current=${state.name}")
        detachSurface()
        val previousBinder = service
        val binderDeath = CountDownLatch(1)
        val deathRecipient = IBinder.DeathRecipient { binderDeath.countDown() }
        val deathLinked = previousBinder?.let { binder ->
            runCatching { binder.linkToDeath(deathRecipient, 0) }.isSuccess
        } == true
        connection?.close()
        connection = null
        view?.setSurfaceLifecycleListener(null)
        view = null
        boundActivity = null
        headlessView = null
        remoteSurface = null
        remoteWidth = 0
        remoteHeight = 0
        activeConnection?.let { if (bound) runCatching { appContext.unbindService(it) } }
        activeConnection = null
        bound = false
        service = null
        runCatching { X11ServerService.stop(appContext) }
        val isolatedProcessExited = previousBinder == null || !deathLinked ||
            binderDeath.await(2, TimeUnit.SECONDS)
        if (deathLinked) {
            runCatching { previousBinder.unlinkToDeath(deathRecipient, 0) }
        }
        check(isolatedProcessExited) {
            state = X11State.FAILED
            "X11隔离进程未在2秒内退出，拒绝复用可能损坏的原生X server状态"
        }
        state = X11State.STOPPED
        desktopFrameReady = false
    }
}

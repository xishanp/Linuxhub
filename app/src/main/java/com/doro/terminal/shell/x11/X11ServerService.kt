package com.doro.terminal.shell.x11

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.system.Os
import android.util.Log
import androidx.core.content.ContextCompat
import com.doro.terminal.shell.desktop.DesktopForegroundNotification
import com.doro.terminal.shell.desktop.DesktopDiagnostics
import com.termux.x11.CmdEntryPoint
import com.termux.x11.LorieView
import java.io.File

class X11ServerService : Service() {
    companion object {
        private const val TAG = "X11ServerService"
        private const val ACTION_START = "com.doro.terminal.shell.x11.action.START"
        private const val ACTION_STOP = "com.doro.terminal.shell.x11.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, X11ServerService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, X11ServerService::class.java))
        }
    }

    private var endpoint: CmdEntryPoint? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val binder = object : Binder() {
        init { attachInterface(null, X11_DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(X11_DESCRIPTOR)
                    true
                }
                X11_TRANSACTION_START -> {
                    data.enforceInterface(X11_DESCRIPTOR)
                    DesktopDiagnostics.ensureSessionHeader(this@X11ServerService)
                    runCatching {
                        val args = data.createStringArray() ?: emptyArray()
                        val startedAt = System.nanoTime()
                        DesktopDiagnostics.append(
                            this@X11ServerService,
                            "x11-service",
                            "START args=${args.joinToString(" ")} pid=${Process.myPid()}",
                        )
                        val prepared = X11Environment.prepare(this@X11ServerService)
                        Os.setenv("TMPDIR", prepared.tmpDir.path, true)
                        Os.setenv("XKB_CONFIG_ROOT", prepared.xkbDir.path, true)
                        DesktopDiagnostics.append(
                            this@X11ServerService,
                            "x11-service",
                            "env TMPDIR=${prepared.tmpDir.path} XKB_CONFIG_ROOT=${prepared.xkbDir.path}",
                        )
                        if (endpoint == null) {
                            var created: CmdEntryPoint? = null
                            var failure: Throwable? = null
                            val latch = java.util.concurrent.CountDownLatch(1)
                            mainHandler.post {
                                try { created = CmdEntryPoint(args) }
                                catch (error: Throwable) { failure = error }
                                finally { latch.countDown() }
                            }
                            check(latch.await(15, java.util.concurrent.TimeUnit.SECONDS)) { "X11主线程启动超时" }
                            failure?.let { throw it }
                            endpoint = requireNotNull(created)
                            DesktopDiagnostics.append(
                                this@X11ServerService,
                                "x11-service",
                                "CmdEntryPoint=${if (endpoint != null) "created" else "null"} " +
                                    "elapsedMs=${(System.nanoTime() - startedAt) / 1_000_000}",
                            )
                        }
                        true
                    }.fold(
                        onSuccess = { started ->
                            DesktopDiagnostics.append(this@X11ServerService, "x11-service", "START result=$started")
                            reply?.writeNoException()
                            reply?.writeInt(if (started) 1 else 0)
                        },
                        onFailure = { failure ->
                            DesktopDiagnostics.append(this@X11ServerService, "x11-service", "START failed: $failure")
                            Log.e(TAG, "X11 server start failed", failure)
                            reply?.writeException(IllegalStateException("X11 Server启动异常：${failure.message}", failure))
                        }
                    )
                    true
                }
                X11_TRANSACTION_CONNECTION -> {
                    data.enforceInterface(X11_DESCRIPTOR)
                    runCatching {
                        val fd = endpoint?.xConnection
                        DesktopDiagnostics.append(
                            this@X11ServerService,
                            "x11-service",
                            "CONNECTION fd=${if (fd == null) "null" else "provided"}",
                        )
                        reply?.writeNoException()
                        if (fd == null) reply?.writeInt(0) else {
                            requireNotNull(reply).writeInt(1)
                            fd.writeToParcel(reply, 0)
                        }
                    }.onFailure { failure ->
                        DesktopDiagnostics.append(this@X11ServerService, "x11-service", "CONNECTION failed: $failure")
                        Log.e(TAG, "X11 connection failed", failure)
                        reply?.writeException(IllegalStateException("X11连接异常：${failure.message}", failure))
                    }
                    true
                }
                X11_TRANSACTION_STATUS -> {
                    data.enforceInterface(X11_DESCRIPTOR)
                    val flags = (if (endpoint != null) 1 else 0) or
                        (if (CmdEntryPoint.isConnected()) 2 else 0)
                    reply?.writeNoException()
                    reply?.writeInt(flags)
                    true
                }
                X11_TRANSACTION_CONTINUOUS_FRAMES -> {
                    data.enforceInterface(X11_DESCRIPTOR)
                    val enabled = data.readInt() != 0
                    runCatching {
                        LorieView.setContinuousFrames(enabled)
                    }.fold(
                        onSuccess = {
                            DesktopDiagnostics.append(
                                this@X11ServerService,
                                "x11-service",
                                "CONTINUOUS_FRAMES enabled=$enabled",
                            )
                            reply?.writeNoException()
                            reply?.writeInt(1)
                        },
                        onFailure = { failure ->
                            Log.e(TAG, "Continuous frame mode failed", failure)
                            reply?.writeException(
                                IllegalStateException("固定帧率切换失败：${failure.message}", failure),
                            )
                        },
                    )
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        DesktopDiagnostics.ensureSessionHeader(this)
        DesktopDiagnostics.append(this, "x11-service", "service onCreate pid=${Process.myPid()}")
        DesktopForegroundNotification.startForeground(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DesktopDiagnostics.append(
            this,
            "x11-service",
            "onStartCommand action=${intent?.action ?: "null"} startId=$startId",
        )
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        DesktopForegroundNotification.startForeground(this)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DesktopDiagnostics.append(this, "x11-service", "onTaskRemoved; keeping X server alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        DesktopDiagnostics.append(this, "x11-service", "service onDestroy pid=${Process.myPid()}")
        endpoint = null
        Log.i(TAG, "Stopping isolated X11 process; a fresh process is required for the next session")
        super.onDestroy()
        // The native X server cannot be safely reinitialized after its service has
        // stopped. ColorOS also freezes this isolated process, and resuming the
        // stale JNI state can crash during the next start. Let onDestroy return,
        // then terminate the disposable process so the next bind is always clean.
        mainHandler.post { Process.killProcess(Process.myPid()) }
    }
}

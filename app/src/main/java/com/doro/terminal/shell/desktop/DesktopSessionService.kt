package com.doro.terminal.shell.desktop

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat

/**
 * Foreground owner for the main app process.
 *
 * The guest/GNOME child processes are launched from this process. Keeping this
 * process in the foreground prevents task removal from killing the session
 * owner while the user briefly leaves the desktop task.
 */
class DesktopSessionService : Service() {
    companion object {
        private const val TAG = "DesktopSessionService"
        const val UI_STATE_PREFERENCES = "linuxhub_ui_state"
        const val RETURN_TO_TERMINAL_KEY = "return_to_terminal"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DesktopSessionService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DesktopSessionService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        DesktopDiagnostics.append(this, TAG, "onCreate pid=${Process.myPid()}")
        DesktopForegroundNotification.startForeground(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DesktopDiagnostics.append(this, TAG, "onStartCommand startId=$startId")
        DesktopForegroundNotification.startForeground(this)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        DesktopDiagnostics.append(this, TAG, "onTaskRemoved; keeping desktop process alive")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        DesktopDiagnostics.append(this, TAG, "onDestroy pid=${Process.myPid()}")
        DesktopForegroundNotification.dismiss(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

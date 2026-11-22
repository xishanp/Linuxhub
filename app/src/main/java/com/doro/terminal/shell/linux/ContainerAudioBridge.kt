package com.doro.terminal.shell.linux

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.Closeable
import java.io.IOException

internal class ContainerAudioBridge(private val pipe: File) : Closeable {
    @Volatile private var generation = 0L
    private var worker: Thread? = null

    @Synchronized
    fun start() {
        if (worker?.isAlive == true) return
        pipe.parentFile?.mkdirs()
        if (pipe.exists() && !pipe.isFifo()) {
            check(pipe.delete()) { "无法清理音频管道 ${pipe.path}" }
        }
        try {
            Os.mkfifo(pipe.path, 0x1B6)
        } catch (error: ErrnoException) {
            if (error.errno != OsConstants.EEXIST) throw error
        }
        val workerGeneration = ++generation
        worker = Thread({ playLoop(workerGeneration) }, "doro-container-audio").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    override fun close() {
        generation++
        worker?.interrupt()
        wakeBlockedReader()
        worker?.join(1_500L)
        worker = null
    }

    private fun playLoop(workerGeneration: Long) {
        val sampleRate = 44100
        try {
            val minimum = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(minimum * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            val buffer = ByteArray(minimum)
            try {
                track.play()
                while (isCurrent(workerGeneration)) {
                    if (!openPipeUntilReady(workerGeneration)) break
                    try {
                        FileInputStream(pipe).use { input ->
                            while (isCurrent(workerGeneration)) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count > 0 && isCurrent(workerGeneration)) {
                                    track.write(buffer, 0, count, AudioTrack.WRITE_BLOCKING)
                                }
                            }
                        }
                    } catch (failure: IOException) {
                        if (isCurrent(workerGeneration)) {
                            Log.w(TAG, "Audio pipe read failed; waiting for the next writer", failure)
                            Thread.sleep(RETRY_DELAY_MS)
                        }
                    }
                }
            } finally {
                runCatching { track.pause() }
                runCatching { track.flush() }
                track.release()
            }
        } catch (failure: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (failure: Throwable) {
            if (isCurrent(workerGeneration)) {
                Log.e(TAG, "Audio bridge stopped without killing the app process", failure)
            }
        }
    }

    private fun openPipeUntilReady(workerGeneration: Long): Boolean {
        while (isCurrent(workerGeneration)) {
            if (pipe.isFifo()) return true
            if (pipe.exists()) {
                runCatching { pipe.delete() }
                if (!pipe.exists()) {
                    runCatching { Os.mkfifo(pipe.path, 0x1B6) }
                }
            } else {
                runCatching { Os.mkfifo(pipe.path, 0x1B6) }
            }
            Thread.sleep(RETRY_DELAY_MS)
        }
        return false
    }

    private fun wakeBlockedReader() {
        var fd: FileDescriptor? = null
        try {
            fd = Os.open(
                pipe.path,
                OsConstants.O_WRONLY or OsConstants.O_NONBLOCK,
                0,
            )
            Os.write(fd, ByteArray(1), 0, 1)
        } catch (_: ErrnoException) {
            // ENXIO means no reader is blocked. The worker will notice the
            // generation change when it is interrupted or opens the pipe.
        } catch (_: IOException) {
            // Audio is best effort and must never fail desktop shutdown.
        } finally {
            fd?.let { runCatching { Os.close(it) } }
        }
    }

    private fun isCurrent(workerGeneration: Long): Boolean = generation == workerGeneration

    private fun File.isFifo(): Boolean = runCatching {
        (Os.stat(path).st_mode and OsConstants.S_IFMT) == OsConstants.S_IFIFO
    }.getOrDefault(false)

    private companion object {
        private const val TAG = "LinuxHubAudio"
        private const val RETRY_DELAY_MS = 250L
    }
}

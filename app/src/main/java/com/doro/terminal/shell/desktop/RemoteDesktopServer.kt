package com.doro.terminal.shell.desktop

import android.content.Context
import android.os.SystemClock
import com.doro.terminal.shell.linux.LinuxContainerManager
import com.doro.terminal.shell.x11.LorieX11Runtime
import com.doro.terminal.shell.x11.X11RuntimeManager
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LAN/ADB browser gateway for the headless X11 desktop.
 *
 * The video path deliberately does not use screen capture, Bitmap or PNG.
 * Xlorie renders straight into MediaCodec's input Surface and the browser
 * decodes the resulting H.264 packets through WebCodecs.
 */
private const val VIDEO_QUEUE_CAPACITY = 16
private const val KEY_FRAME_REQUEST_INTERVAL_MS = 500L

class RemoteDesktopServer private constructor(context: Context) {
    data class Status(
        val running: Boolean,
        val port: Int?,
        val httpsPort: Int?,
        val pairingCode: String?,
        val lanAddress: String?,
        val frameReady: Boolean,
        val inputReady: Boolean,
        val clients: Int,
        val lastError: String?,
    )

    private class VideoSubscriber {
        data class Outbound(
            val message: ByteArray,
            val frameBytes: Int,
        )

        private val queue = ArrayBlockingQueue<Outbound>(VIDEO_QUEUE_CAPACITY)
        @Volatile private var waitingForKeyFrame = true

        fun reset() {
            queue.clear()
            waitingForKeyFrame = true
        }

        fun offerControl(message: ByteArray) {
            // A new decoder configuration must be followed by a keyframe. Drop
            // stale frames instead of letting them consume the low-latency path.
            queue.clear()
            waitingForKeyFrame = true
            queue.offer(Outbound(message, 0))
        }

        fun offerPacket(message: ByteArray, keyFrame: Boolean): Boolean {
            if (waitingForKeyFrame) {
                if (!keyFrame) return false
                waitingForKeyFrame = false
            }
            if (queue.offer(Outbound(message, message.size))) return true

            // The socket is behind. Discard the old GOP; sending the newest
            // inter-frame alone would leave WebCodecs with broken references.
            queue.clear()
            if (keyFrame) {
                queue.offer(Outbound(message, message.size))
                return true
            }
            waitingForKeyFrame = true
            return false
        }

        fun poll(timeout: Long, unit: TimeUnit): Outbound? = queue.poll(timeout, unit)
    }

    private val appContext = context.applicationContext
    private val manager = LinuxContainerManager(appContext)
    private val runtime = X11RuntimeManager.get(appContext).runtime as LorieX11Runtime
    private val lock = Any()
    private val inputLock = Any()
    private val clientWriteLock = Any()
    private val running = AtomicBoolean(false)
    private val clients = CopyOnWriteArraySet<Socket>()
    private val videoSubscribers = CopyOnWriteArraySet<VideoSubscriber>()
    private val logLines = ArrayDeque<String>()
    private val random = SecureRandom()
    private val lastPacketBytes = AtomicLong(0L)
    private val lastKeyFrameRequestAt = AtomicLong(0L)

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var httpsServerSocket: ServerSocket? = null
    @Volatile private var pairingCode: String? = null
    @Volatile private var inputProcess: Process? = null
    @Volatile private var inputWriter: BufferedWriter? = null
    @Volatile private var videoEncoder: RemoteH264Encoder? = null
    @Volatile private var videoConfig: RemoteVideoConfig? = null
    @Volatile private var latestKeyFrame: RemoteVideoPacket? = null
    @Volatile private var requestedVideoWidth = 0
    @Volatile private var requestedVideoHeight = 0
    @Volatile private var frameReady = false
    @Volatile private var inputReady = false
    @Volatile private var lastError: String? = null
    @Volatile private var acceptThread: Thread? = null
    @Volatile private var httpsAcceptThread: Thread? = null
    @Volatile private var videoThread: Thread? = null
    @Volatile private var inputThread: Thread? = null

    fun start(): Status = synchronized(lock) {
        if (running.get()) return status()
        lastError = null
        pairingCode = "%06d".format(random.nextInt(1_000_000))
        val socket = bindFirstAvailable()
        serverSocket = socket
        val tlsSocket = runCatching {
            bindTlsFirstAvailable(lanAddress())
        }.onFailure { failure ->
            lastError = "HTTPS 启动失败：${failure.message}"
            appendLog(lastError!!)
        }.getOrNull()
        httpsServerSocket = tlsSocket
        running.set(true)
        appendLog(
            "服务已启动 HTTP:${socket.localPort}" +
                (tlsSocket?.let { " HTTPS:${it.localPort}" } ?: "") +
                "，配对码 $pairingCode",
        )
        acceptThread = Thread({ acceptLoop(socket) }, "linuxhub-remote-accept").apply {
            isDaemon = true
            start()
        }
        httpsAcceptThread = tlsSocket?.let { tls ->
            Thread({ acceptLoop(tls) }, "linuxhub-remote-https-accept").apply {
                isDaemon = true
                start()
            }
        }
        videoThread = Thread({ videoLoop() }, "linuxhub-remote-video").apply {
            isDaemon = true
            start()
        }
        inputThread = Thread({ inputLoop() }, "linuxhub-remote-input").apply {
            isDaemon = true
            start()
        }
        status()
    }

    fun stop() {
        synchronized(lock) {
            if (!running.getAndSet(false)) return
            appendLog("服务已停止")
            runCatching { serverSocket?.close() }
            serverSocket = null
            runCatching { httpsServerSocket?.close() }
            httpsServerSocket = null
            clients.forEach { runCatching { it.close() } }
            clients.clear()
            videoSubscribers.clear()
            runCatching { inputWriter?.close() }
            inputWriter = null
            runCatching { inputProcess?.destroy() }
            inputProcess = null
            runCatching { videoEncoder?.stop() }
            videoEncoder = null
            runtime.detachRemoteSurface()
            frameReady = false
            inputReady = false
            videoConfig = null
            latestKeyFrame = null
            requestedVideoWidth = 0
            requestedVideoHeight = 0
            lastPacketBytes.set(0L)
        }
    }

    fun status(): Status = Status(
        running = running.get(),
        port = serverSocket?.localPort,
        httpsPort = httpsServerSocket?.localPort,
        pairingCode = pairingCode,
        lanAddress = lanAddress(),
        frameReady = frameReady,
        inputReady = inputReady,
        clients = clients.size,
        lastError = lastError,
    )

    fun logTail(): String = synchronized(logLines) {
        logLines.joinToString("\n")
    }

    private fun bindFirstAvailable(): ServerSocket {
        for (port in 8080..8089) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), port))
                }
            } catch (failure: Exception) {
                appendLog("端口 $port 不可用：${failure.message}")
            }
        }
        throw IllegalStateException("8080-8089 端口都被占用")
    }

    private fun bindTlsFirstAvailable(host: String?): ServerSocket {
        val context = LocalTls.serverContext(appContext, host)
        for (port in 8443..8452) {
            try {
                return context.serverSocketFactory.createServerSocket(port).apply {
                    reuseAddress = true
                }
            } catch (failure: Exception) {
                appendLog("HTTPS 端口 $port 不可用：${failure.message}")
            }
        }
        throw IllegalStateException("8443-8452 端口都被占用")
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get() && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (failure: Exception) {
                if (running.get()) appendLog("接受连接失败：${failure.message}")
                break
            }
            clients += client
            Thread({ handleClient(client) }, "linuxhub-remote-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun videoLoop() {
        while (running.get()) {
            var encoder: RemoteH264Encoder? = null
            try {
                val desktop = LinuxHubDesktopSession.status(appContext)
                var width = requestedVideoWidth.takeIf { it in 320..RemoteVideoProfilePolicy.MAX_WIDTH }
                    ?: desktop.width.takeIf { it in 320..RemoteVideoProfilePolicy.MAX_WIDTH }
                    ?: RemoteVideoProfilePolicy.DEFAULT_WIDTH
                var height = requestedVideoHeight.takeIf { it in 240..RemoteVideoProfilePolicy.MAX_HEIGHT }
                    ?: desktop.height.takeIf { it in 240..RemoteVideoProfilePolicy.MAX_HEIGHT }
                    ?: RemoteVideoProfilePolicy.DEFAULT_HEIGHT
                width = width and -2
                height = height and -2
                val profile = RemoteVideoProfilePolicy.forSize(width, height)
                encoder = RemoteH264Encoder(
                    width = width,
                    height = height,
                    bitRate = profile.bitRate,
                    frameRate = profile.frameRate,
                    onConfig = ::publishVideoConfig,
                    onPacket = ::publishVideoPacket,
                    onError = {
                        lastError = it
                        appendLog(it)
                    },
                )
                videoEncoder = encoder
                if (videoSubscribers.isNotEmpty()) encoder.setStreamingActive(true)
                val surface = encoder.start()
                runtime.ensureHeadlessSize(width, height)
                runtime.attachRemoteSurface(surface, width, height)
                requestKeyFrame(force = true)
                appendLog("H.264 硬编已就绪 ${width}x$height，${profile.bitRateLabel} / ${profile.frameRate} FPS / CBR")
                encoder.await()
                if (running.get()) appendLog("H.264 编码器已退出，准备重启")
            } catch (failure: Throwable) {
                if (running.get()) {
                    lastError = failure.message ?: failure.javaClass.simpleName
                    appendLog("H.264 编码启动失败：${failure.message}")
                }
            } finally {
                frameReady = false
                videoConfig = null
                latestKeyFrame = null
                runtime.detachRemoteSurface()
                runCatching { encoder?.stop() }
                if (videoEncoder === encoder) videoEncoder = null
            }
            if (running.get()) Thread.sleep(if (requestedVideoWidth > 0) 350 else 1_500)
        }
    }

    private fun publishVideoConfig(config: RemoteVideoConfig) {
        videoConfig = config
        appendLog("WebCodecs 配置 ${config.codec} ${config.width}x${config.height}")
        val message = config.toMessage()
        videoSubscribers.forEach { it.offerControl(message) }
    }

    private fun publishVideoPacket(packet: RemoteVideoPacket) {
        lastPacketBytes.set(packet.data.size.toLong())
        if (packet.keyFrame) latestKeyFrame = packet
        if (!frameReady) {
            frameReady = true
            appendLog("H.264 首帧已编码 ${packet.data.size} 字节，关键帧=${packet.keyFrame}")
        }
        val message = packet.toMessage()
        var dropped = false
        videoSubscribers.forEach { subscriber ->
            if (!subscriber.offerPacket(message, packet.keyFrame)) dropped = true
        }
        if (dropped) requestKeyFrame()
    }

    private fun inputLoop() {
        while (running.get()) {
            var process: Process? = null
            try {
                process = manager.startRemoteInputDaemon()
                inputProcess = process
                inputWriter = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
                inputReady = true
                val errorDrain = Thread({
                    runCatching {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { appendLog("输入进程：$it") }
                        }
                    }
                }, "linuxhub-remote-input-errors").apply {
                    isDaemon = true
                    start()
                }
                process.waitFor()
                if (running.get()) appendLog("输入进程退出 ${runCatching { process.exitValue() }.getOrNull() ?: ""}")
            } catch (failure: Exception) {
                if (running.get()) appendLog("输入进程启动失败：${failure.message}")
            } finally {
                inputReady = false
                synchronized(inputLock) {
                    runCatching { inputWriter?.close() }
                    inputWriter = null
                }
            }
            if (running.get()) Thread.sleep(1_500)
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 10_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = readLine(input) ?: return
            val requestParts = requestLine.split(' ')
            if (requestParts.size < 2) return
            val method = requestParts[0]
            val rawPath = requestParts[1]
            val path = rawPath.substringBefore('?')
            val query = parseQuery(rawPath.substringAfter('?', ""))
            val headers = readHeaders(input)

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    val html = appContext.assets.open("remote/index.html").readBytes()
                    writeHttp(output, 200, "text/html; charset=utf-8", html)
                }
                method == "GET" && path == "/status" -> {
                    val json = JSONObject()
                        .put("running", running.get())
                        .put("port", serverSocket?.localPort ?: JSONObject.NULL)
                        .put("httpsPort", httpsServerSocket?.localPort ?: JSONObject.NULL)
                        .put("frameReady", frameReady)
                        .put("inputReady", inputReady)
                        .put("videoCodec", "H.264/AVC")
                        .put("videoFrameBytes", lastPacketBytes.get())
                        .put("videoFlow", videoEncoder?.statusLine() ?: JSONObject.NULL)
                        .put("clients", clients.size)
                        .toString()
                    writeHttp(output, 200, "application/json; charset=utf-8", json.toByteArray())
                }
                method == "GET" && path == "/stream" && isWebSocket(headers) -> {
                    if (!pairingMatches(query["code"])) {
                        writeHttp(output, 401, "text/plain; charset=utf-8", "配对码错误".toByteArray())
                        return
                    }
                    openWebSocket(client, input, output, headers)
                }
                method == "GET" && path == "/frame" -> {
                    writeHttp(
                        output,
                        410,
                        "text/plain; charset=utf-8",
                        "PNG 抓屏已移除，请使用 WebSocket /stream 的 H.264 流".toByteArray(),
                    )
                }
                method == "POST" && path == "/input" -> {
                    requirePairing(query, output) ?: return
                    val body = readBody(input, headers)
                    val message = runCatching { JSONObject(body) }.getOrNull()
                    if (message == null) writeHttp(output, 400, "text/plain; charset=utf-8", "JSON 无效".toByteArray())
                    else {
                        forwardInput(message)
                        writeHttp(output, 204, "text/plain; charset=utf-8", ByteArray(0))
                    }
                }
                else -> writeHttp(output, 404, "text/plain; charset=utf-8", "Not found".toByteArray())
            }
            output.flush()
        } catch (failure: Exception) {
            if (running.get()) appendLog("客户端错误：${failure.message}")
        } finally {
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    private fun openWebSocket(
        client: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        headers: Map<String, String>,
    ) {
        val key = headers["sec-websocket-key"] ?: return
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII),
            ),
        )
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        synchronized(clientWriteLock) {
            output.write(response.toByteArray(StandardCharsets.US_ASCII))
            output.flush()
        }
        client.soTimeout = 80
        val closed = AtomicBoolean(false)
        val subscriber = VideoSubscriber()
        videoSubscribers += subscriber
        if (videoSubscribers.size == 1) {
            videoEncoder?.setStreamingActive(true)
            requestKeyFrame(force = true)
        }
        val cachedConfig = videoConfig
        val cachedKeyFrame = latestKeyFrame
        cachedConfig?.let { subscriber.offerControl(it.toMessage()) }
        cachedKeyFrame?.let { subscriber.offerPacket(it.toMessage(), true) }
        if (cachedConfig == null || cachedKeyFrame == null) requestKeyFrame()
        appendLog(
            if (cachedConfig != null && cachedKeyFrame != null) {
                "网页已连接，发送缓存 H.264 首帧 ${cachedKeyFrame.data.size} 字节"
            } else {
                "网页已连接，等待 H.264 关键帧"
            },
        )

        val writer = Thread({
            try {
                while (running.get() && !closed.get()) {
                    val outbound = subscriber.poll(100, TimeUnit.MILLISECONDS) ?: continue
                    synchronized(clientWriteLock) {
                        writeWebSocketFrame(output, 0x2, outbound.message)
                    }
                    if (outbound.frameBytes > 0) videoEncoder?.onFrameSent(outbound.frameBytes)
                }
            } catch (_: InterruptedException) {
                // A closed browser tab interrupts this thread. That is normal.
            } catch (failure: Exception) {
                if (running.get()) appendLog("H.264 推送断开：${failure.message}")
            } finally {
                closed.set(true)
                videoSubscribers.remove(subscriber)
                if (videoSubscribers.isEmpty()) videoEncoder?.setStreamingActive(false)
                runCatching { client.close() }
            }
        }, "linuxhub-remote-ws-writer").apply {
            isDaemon = true
            start()
        }
        try {
            while (running.get() && !closed.get()) {
                val frame = try {
                    readWebSocketFrame(input)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: EOFException) {
                    break
                }
                if (frame == null) break
                when (frame.first) {
                    0x1 -> {
                        val message = runCatching {
                            JSONObject(String(frame.second, StandardCharsets.UTF_8))
                        }.getOrNull()
                        if (message != null) forwardInput(message)
                    }
                    0x8 -> break
                    0x9 -> synchronized(clientWriteLock) {
                        runCatching { writeWebSocketFrame(output, 0xA, frame.second) }
                    }
                }
            }
        } finally {
            closed.set(true)
            writer.interrupt()
            videoSubscribers.remove(subscriber)
            if (videoSubscribers.isEmpty()) videoEncoder?.setStreamingActive(false)
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    private fun requestKeyFrame(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            val previous = lastKeyFrameRequestAt.get()
            if (now - previous < KEY_FRAME_REQUEST_INTERVAL_MS) return
            if (!lastKeyFrameRequestAt.compareAndSet(previous, now)) return
        } else {
            lastKeyFrameRequestAt.set(now)
        }
        val encoder = videoEncoder ?: return
        appendLog("请求 H.264 关键帧")
        encoder.requestKeyFrame()
    }

    private fun forwardInput(message: JSONObject) {
        when (message.optString("type")) {
            "ack" -> {
                val sequence = message.optLong("seq", -1L)
                if (sequence >= 0L) videoEncoder?.onClientAck(sequence)
                return
            }
            "viewport" -> {
                requestViewport(message.optInt("width"), message.optInt("height"))
                return
            }
            "quality" -> {
                requestQuality(message.optInt("width"), message.optInt("height"))
                return
            }
        }
        synchronized(inputLock) {
            val writer = inputWriter ?: run {
                appendLog("输入进程未就绪，丢弃事件")
                return
            }
            runCatching {
                writer.write(message.toString())
                writer.newLine()
                writer.flush()
            }.onFailure {
                appendLog("输入转发失败：${it.message}")
            }
        }
    }

    private fun requestViewport(sourceWidth: Int, sourceHeight: Int) {
        if (sourceWidth < 320 || sourceHeight < 240) return
        val scale = min(
            RemoteVideoProfilePolicy.MAX_WIDTH.toDouble() / sourceWidth,
            RemoteVideoProfilePolicy.MAX_HEIGHT.toDouble() / sourceHeight,
        )
        val targetWidth = ((sourceWidth * scale).roundToInt()
            .coerceIn(320, RemoteVideoProfilePolicy.MAX_WIDTH) and -2)
        val targetHeight = ((sourceHeight * scale).roundToInt()
            .coerceIn(240, RemoteVideoProfilePolicy.MAX_HEIGHT) and -2)
        applyRemoteSize(targetWidth, targetHeight, "网页窗口 ${sourceWidth}x$sourceHeight")
    }

    private fun requestQuality(width: Int, height: Int) {
        if (width < 320 || height < 240) return
        val targetWidth = (width and -2).coerceIn(320, RemoteVideoProfilePolicy.MAX_WIDTH)
        val targetHeight = (height and -2).coerceIn(240, RemoteVideoProfilePolicy.MAX_HEIGHT)
        applyRemoteSize(targetWidth, targetHeight, "网页手动选择")
    }

    private fun applyRemoteSize(targetWidth: Int, targetHeight: Int, source: String) {
        synchronized(lock) {
            if (requestedVideoWidth == targetWidth && requestedVideoHeight == targetHeight) return
            requestedVideoWidth = targetWidth
            requestedVideoHeight = targetHeight
            videoConfig = null
            latestKeyFrame = null
            frameReady = false
            videoSubscribers.forEach { it.reset() }
            appendLog("$source，切换远端画面 ${targetWidth}x$targetHeight")
            runCatching { videoEncoder?.stop() }
        }
    }

    private fun requirePairing(query: Map<String, String>, output: OutputStream): Boolean? {
        if (pairingMatches(query["code"])) return true
        writeHttp(output, 401, "text/plain; charset=utf-8", "配对码错误".toByteArray())
        return null
    }

    private fun pairingMatches(value: String?): Boolean =
        value != null && value == pairingCode

    private fun writeHttp(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        cache: Boolean = true,
    ) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            410 -> "Gone"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: ${if (cache) "no-store" else "no-store"}\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val current = input.read()
            if (current < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.US_ASCII.name())
            if (current == '\n'.code) {
                val result = bytes.toString(StandardCharsets.US_ASCII.name())
                return result.removeSuffix("\r")
            }
            bytes.write(current)
            if (bytes.size() > 16 * 1024) throw IllegalStateException("HTTP 请求头过长")
        }
    }

    private fun readHeaders(input: InputStream): Map<String, String> {
        val result = linkedMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val index = line.indexOf(':')
            if (index > 0) result[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }
        return result
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): String {
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1_000_000) ?: 0
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) break
            offset += count
        }
        return String(bytes, 0, offset, StandardCharsets.UTF_8)
    }

    private fun isWebSocket(headers: Map<String, String>): Boolean =
        headers["upgrade"]?.equals("websocket", ignoreCase = true) == true

    private fun readWebSocketFrame(input: InputStream): Pair<Int, ByteArray>? {
        val first = input.read()
        val second = input.read()
        if (first < 0 || second < 0) throw EOFException()
        val opcode = first and 0x0f
        val masked = (second and 0x80) != 0
        var length = second and 0x7f
        if (length == 126) length = (input.read() shl 8) or input.read()
        else if (length == 127) {
            var value = 0L
            repeat(8) { value = (value shl 8) or input.read().toLong() }
            require(value <= 32L * 1024 * 1024) { "WebSocket 帧过大" }
            length = value.toInt()
        }
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(length).also { input.readFully(it) }
        if (mask != null) for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        return opcode to payload
    }

    private fun writeWebSocketFrame(output: OutputStream, opcode: Int, payload: ByteArray) {
        output.write(0x80 or opcode)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 0xffff -> {
                output.write(126)
                output.write((payload.size ushr 8) and 0xff)
                output.write(payload.size and 0xff)
            }
            else -> {
                output.write(127)
                repeat(8) { shift -> output.write(((payload.size.toLong() ushr (56 - shift * 8)) and 0xff).toInt()) }
            }
        }
        output.write(payload)
        output.flush()
    }

    private fun InputStream.readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = read(buffer, offset, buffer.size - offset)
            if (count < 0) throw EOFException()
            offset += count
        }
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').filter(String::isNotBlank).associate { part ->
            val key = part.substringBefore('=')
            val value = part.substringAfter('=', "")
            decode(key) to decode(value)
        }

    private fun decode(value: String): String = java.net.URLDecoder.decode(value, StandardCharsets.UTF_8)

    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun appendLog(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 40) logLines.removeFirst()
        }
    }

    companion object {
        @Volatile private var instance: RemoteDesktopServer? = null

        fun get(context: Context): RemoteDesktopServer =
            instance ?: synchronized(this) {
                instance ?: RemoteDesktopServer(context).also { instance = it }
            }
    }
}

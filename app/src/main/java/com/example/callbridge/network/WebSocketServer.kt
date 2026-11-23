package com.example.callbridge.network

import android.util.Base64
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import com.example.callbridge.protocol.CallStateMessage
import com.example.callbridge.protocol.CommandMessage
import com.example.callbridge.protocol.CommandResultMessage
import com.example.callbridge.protocol.CommandValidationResult
import com.example.callbridge.protocol.MessageSerializer
import com.example.callbridge.protocol.ProtocolConstants
import com.example.callbridge.security.CertificatePinningManager
import com.example.callbridge.security.KeyStoreManager
import com.example.callbridge.security.SessionAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Lightweight, zero-dependency RFC 6455 TLS WebSocket server (`wss://<LOCAL_IP>:8443/bridge/v1`)
 * backed by [KeyStoreManager]'s on-device RSA-2048 X.509v3 certificate.
 *
 * Security properties:
 * - Binds strictly to the resolved local Wi-Fi/Hotspot private IPv4 interface.
 * - Requires TLS handshake using the pinned on-device certificate.
 * - Verifies WebSocket HTTP Upgrade path (`/bridge/v1`) and computes RFC 6455 `Sec-WebSocket-Accept`.
 * - Enforces session authentication on all sensitive commands via [SessionAuthenticator] / commandHandler.
 */
class WebSocketServer(
    private val keyStoreManager: KeyStoreManager,
    private val sessionAuthenticator: SessionAuthenticator,
    private val port: Int = AppConfig.DEFAULT_WSS_PORT,
    private val expectedPath: String = AppConfig.DEFAULT_WSS_PATH
) {
    private val running = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var serverSocket: SSLServerSocket? = null

    @Volatile
    private var activeClientSocket: SSLSocket? = null

    @Volatile
    private var activeClientOutput: OutputStream? = null

    private val writeLock = Any()

    private var commandHandler: (suspend (CommandMessage, String) -> CommandResultMessage)? = null

    private val _serverState = MutableStateFlow(ServerRunState.STOPPED)
    val serverState: StateFlow<ServerRunState> = _serverState.asStateFlow()

    private val _connectedClient = MutableStateFlow<ConnectedNetworkClient?>(null)
    val connectedClient: StateFlow<ConnectedNetworkClient?> = _connectedClient.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun setCommandHandler(handler: suspend (CommandMessage, String) -> CommandResultMessage) {
        commandHandler = handler
    }

    fun getCertificateFingerprint(): String = keyStoreManager.getServerCertificateFingerprint()

    fun start(bindIp: String): Boolean {
        if (running.get()) {
            Logger.d("WSS", "WebSocket TLS server already running on $bindIp:$port")
            return true
        }

        _serverState.value = ServerRunState.STARTING
        _lastError.value = null

        return try {
            val tlsMaterial = keyStoreManager.getOrCreateTlsMaterial()
            val factory = tlsMaterial.sslContext.serverSocketFactory
            val ss = (factory.createServerSocket() as SSLServerSocket).apply {
                reuseAddress = true
                enabledProtocols = supportedProtocols.filter {
                    it == "TLSv1.3" || it == "TLSv1.2"
                }.ifEmpty { enabledProtocols.toList() }.toTypedArray()
                bind(InetSocketAddress(InetAddress.getByName(bindIp), port), 4)
            }
            serverSocket = ss
            running.set(true)
            _serverState.value = ServerRunState.LISTENING
            Logger.i("WSS", "TLS WebSocket server listening on wss://$bindIp:$port$expectedPath")

            val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = serverScope
            serverScope.launch { acceptLoop(ss) }
            true
        } catch (t: Throwable) {
            _serverState.value = ServerRunState.ERROR
            _lastError.value = t.message ?: "Failed to bind TLS WebSocket server"
            Logger.e("WSS", "Failed to start TLS WebSocket server on $bindIp:$port", t)
            stop()
            false
        }
    }

    fun disconnectActiveClient() {
        closeActiveClient()
        Logger.i("WSS", "Disconnected active WebSocket client")
    }

    fun stop() {
        running.set(false)
        closeActiveClient()
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        if (_serverState.value != ServerRunState.ERROR) {
            _serverState.value = ServerRunState.STOPPED
        }
        Logger.i("WSS", "TLS WebSocket server stopped")
    }

    fun sendCallState(message: CallStateMessage): Boolean {
        val json = MessageSerializer.serializeCallState(message)
        return sendRawTextFrame(json)
    }

    fun sendRawTextFrame(text: String): Boolean {
        val out = activeClientOutput ?: return false
        return try {
            synchronized(writeLock) {
                writeWebSocketFrame(out, OPCODE_TEXT, text.toByteArray(Charsets.UTF_8), masked = false)
            }
            true
        } catch (t: Throwable) {
            Logger.w("WSS", "Failed to send WebSocket text frame: ${t.message}")
            false
        }
    }

    /**
     * Real TLS + WebSocket RFC 6455 loopback self-test verifying:
     * 1. TLS handshake succeeds and server X.509 certificate matches pinned SHA-256 fingerprint.
     * 2. HTTP Upgrade to `/bridge/v1` returns `101 Switching Protocols` with valid `Sec-WebSocket-Accept`.
     * 3. Framed `PING` JSON command returns `PONG` `COMMAND_RESULT`.
     */
    suspend fun runLoopbackProbe(hostIp: String): Boolean = withContext(Dispatchers.IO) {
        val expectedFingerprint = keyStoreManager.getServerCertificateFingerprint()
        val clientSslContext = CertificatePinningManager.createPinnedClientSslContext(expectedFingerprint)
        var socket: SSLSocket? = null
        try {
            socket = (clientSslContext.socketFactory.createSocket() as SSLSocket).apply {
                soTimeout = 4_000
                connect(InetSocketAddress(hostIp, port), 3_000)
                startHandshake()
            }
            val input = BufferedInputStream(socket.inputStream)
            val output = BufferedOutputStream(socket.outputStream)

            val wsKey = Base64.encodeToString("bridgelink-probe".toByteArray(), Base64.NO_WRAP)
            val upgradeReq = buildString {
                append("GET ").append(expectedPath).append(" HTTP/1.1\r\n")
                append("Host: ").append(hostIp).append(":").append(port).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n\r\n")
            }
            output.write(upgradeReq.toByteArray(Charsets.US_ASCII))
            output.flush()

            val headers = readHttpHeaders(input)
            val statusLine = headers.firstOrNull().orEmpty()
            if (!statusLine.contains("101")) return@withContext false

            val expectedAccept = computeWebSocketAccept(wsKey)
            val acceptHeader = headers.firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
            if (acceptHeader != expectedAccept) return@withContext false

            val pingJson = MessageSerializer.serializeCommand(
                CommandMessage(version = ProtocolConstants.VERSION, id = "wss_probe", cmd = ProtocolConstants.CMD_PING)
            )
            writeWebSocketFrame(output, OPCODE_TEXT, pingJson.toByteArray(Charsets.UTF_8), masked = true)

            val frame = readWebSocketFrame(input) ?: return@withContext false
            val responseText = frame.payload.toString(Charsets.UTF_8)
            val parsed = MessageSerializer.deserializeCommandResult(responseText)
            val ok = parsed?.success == true && parsed.message == "PONG"
            runCatching { writeWebSocketFrame(output, OPCODE_CLOSE, ByteArray(0), masked = true) }
            ok
        } catch (t: Throwable) {
            Logger.w("WSS", "TLS WebSocket loopback probe failed: ${t.message}")
            false
        } finally {
            runCatching { socket?.close() }
        }
    }

    private suspend fun acceptLoop(server: SSLServerSocket) {
        while (running.get() && scope?.isActive == true) {
            try {
                val client = server.accept() as SSLSocket
                handleClientConnection(client)
            } catch (t: Throwable) {
                if (running.get()) {
                    Logger.w("WSS", "TLS accept loop interrupted: ${t.message}")
                }
            }
        }
    }

    private suspend fun handleClientConnection(socket: SSLSocket) {
        closeActiveClient()
        val remoteIp = socket.inetAddress?.hostAddress ?: "unknown"
        val remotePort = socket.port
        Logger.i("WSS", "Incoming TLS client connection from $remoteIp:$remotePort")

        try {
            socket.soTimeout = 0
            socket.startHandshake()

            val input = BufferedInputStream(socket.inputStream)
            val output = BufferedOutputStream(socket.outputStream)

            val headers = readHttpHeaders(input)
            if (headers.isEmpty()) {
                socket.close()
                return
            }

            val requestLine = headers.first()
            val pathValid = requestLine.startsWith("GET $expectedPath ") || requestLine.startsWith("GET / ")
            val wsKey = headers.firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()

            if (!pathValid || wsKey.isNullOrBlank()) {
                val badResp = "HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"
                output.write(badResp.toByteArray(Charsets.US_ASCII))
                output.flush()
                socket.close()
                return
            }

            val acceptVal = computeWebSocketAccept(wsKey)
            val handshakeResp = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: ").append(acceptVal).append("\r\n\r\n")
            }
            output.write(handshakeResp.toByteArray(Charsets.US_ASCII))
            output.flush()

            activeClientSocket = socket
            activeClientOutput = output
            _connectedClient.value = ConnectedNetworkClient(
                remoteAddress = remoteIp,
                remotePort = remotePort,
                authenticated = sessionAuthenticator.activeSession.value != null,
                connectedAtMs = System.currentTimeMillis()
            )
            Logger.i("WSS", "WebSocket TLS session upgraded for $remoteIp:$remotePort")

            while (running.get() && !socket.isClosed) {
                val frame = readWebSocketFrame(input) ?: break
                when (frame.opcode) {
                    OPCODE_CLOSE -> {
                        synchronized(writeLock) {
                            runCatching { writeWebSocketFrame(output, OPCODE_CLOSE, ByteArray(0), masked = false) }
                        }
                        break
                    }
                    OPCODE_PING -> {
                        synchronized(writeLock) {
                            writeWebSocketFrame(output, OPCODE_PONG, frame.payload, masked = false)
                        }
                    }
                    OPCODE_TEXT -> {
                        val rawJson = frame.payload.toString(Charsets.UTF_8)
                        val result = processIncomingMessage(rawJson, remoteIp)
                        val responseJson = MessageSerializer.serializeCommandResult(result)
                        synchronized(writeLock) {
                            writeWebSocketFrame(output, OPCODE_TEXT, responseJson.toByteArray(Charsets.UTF_8), masked = false)
                        }
                        _connectedClient.value = _connectedClient.value?.copy(
                            authenticated = sessionAuthenticator.activeSession.value != null
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Logger.w("WSS", "TLS WebSocket client closed ($remoteIp): ${t.message}")
            }
        } finally {
            closeActiveClient()
        }
    }

    private suspend fun processIncomingMessage(rawJson: String, remoteIp: String): CommandResultMessage {
        return when (val validation = MessageSerializer.parseAndValidateCommand(rawJson)) {
            is CommandValidationResult.Invalid -> CommandResultMessage(
                id = validation.commandId,
                success = false,
                message = validation.reason,
                errorCode = validation.errorCode
            )
            is CommandValidationResult.Valid -> {
                val cmd = validation.command
                when (cmd.cmd) {
                    ProtocolConstants.CMD_PING -> CommandResultMessage(
                        id = cmd.id,
                        success = true,
                        message = "PONG"
                    )
                    ProtocolConstants.CMD_AUTH -> {
                        val session = sessionAuthenticator.authenticatePairedDevice(cmd.deviceId, null)
                        if (session != null) {
                            CommandResultMessage(
                                id = cmd.id,
                                success = true,
                                message = "WSS session authenticated",
                                sessionToken = session.sessionToken
                            )
                        } else {
                            val code = if (sessionAuthenticator.isInCooldown()) "AUTH_COOLDOWN" else "UNAUTHORIZED_DEVICE"
                            CommandResultMessage(
                                id = cmd.id,
                                success = false,
                                message = "Device is not paired and approved on Android",
                                errorCode = code
                            )
                        }
                    }
                    else -> {
                        val handler = commandHandler
                        if (handler != null) {
                            handler(cmd, "WSS:$remoteIp")
                        } else {
                            CommandResultMessage(
                                id = cmd.id,
                                success = false,
                                message = "Bridge command router unavailable",
                                errorCode = "HANDLER_UNAVAILABLE"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun closeActiveClient() {
        runCatching { activeClientOutput?.close() }
        runCatching { activeClientSocket?.close() }
        activeClientOutput = null
        activeClientSocket = null
        _connectedClient.value = null
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    companion object {
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_WS_FRAME_BYTES = 128 * 1024

        fun computeWebSocketAccept(secWebSocketKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest((secWebSocketKey.trim() + WS_GUID).toByteArray(Charsets.US_ASCII))
            return Base64.encodeToString(sha1, Base64.NO_WRAP)
        }

        private fun readHttpHeaders(input: InputStream): List<String> {
            val lines = mutableListOf<String>()
            val lineBuf = ByteArrayOutputStream()
            var prevByte = -1
            while (lines.size < 40) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    val raw = lineBuf.toByteArray()
                    lineBuf.reset()
                    val len = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                    val line = String(raw, 0, len, Charsets.US_ASCII)
                    if (line.isEmpty()) break
                    lines.add(line)
                } else {
                    if (lineBuf.size() > 4096) break
                    lineBuf.write(b)
                }
                prevByte = b
            }
            @Suppress("UNUSED_VALUE")
            prevByte = 0
            return lines
        }

        private fun readWebSocketFrame(input: InputStream): WsFrame? {
            val b0 = input.read()
            if (b0 == -1) return null
            val b1 = input.read()
            if (b1 == -1) return null

            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var payloadLen = (b1 and 0x7F).toLong()

            if (payloadLen == 126L) {
                val hi = input.read()
                val lo = input.read()
                if (hi == -1 || lo == -1) return null
                payloadLen = ((hi shl 8) or lo).toLong()
            } else if (payloadLen == 127L) {
                var len = 0L
                for (i in 0 until 8) {
                    val next = input.read()
                    if (next == -1) return null
                    len = (len shl 8) or (next.toLong() and 0xFFL)
                }
                payloadLen = len
            }

            if (payloadLen < 0 || payloadLen > MAX_WS_FRAME_BYTES) return null

            val maskKey = if (masked) readExact(input, 4) else null
            val payload = readExact(input, payloadLen.toInt())
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }
            return WsFrame(opcode, payload)
        }

        private fun writeWebSocketFrame(
            output: OutputStream,
            opcode: Int,
            payload: ByteArray,
            masked: Boolean
        ) {
            val out = ByteArrayOutputStream(payload.size + 14)
            out.write(0x80 or (opcode and 0x0F)) // FIN + opcode
            val maskBit = if (masked) 0x80 else 0x00
            when {
                payload.size <= 125 -> out.write(maskBit or payload.size)
                payload.size <= 65535 -> {
                    out.write(maskBit or 126)
                    out.write((payload.size ushr 8) and 0xFF)
                    out.write(payload.size and 0xFF)
                }
                else -> {
                    out.write(maskBit or 127)
                    val lenLong = payload.size.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        out.write(((lenLong ushr shift) and 0xFFL).toInt())
                    }
                }
            }
            if (masked) {
                val mask = byteArrayOf(0x37, 0x5A, 0x19, 0x6C)
                out.write(mask)
                for (i in payload.indices) {
                    out.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
                }
            } else {
                out.write(payload)
            }
            output.write(out.toByteArray())
            output.flush()
        }

        private fun readExact(input: InputStream, count: Int): ByteArray {
            val buf = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(buf, offset, count - offset)
                if (read == -1) throw EOFException("Unexpected EOF reading WebSocket frame")
                offset += read
            }
            return buf
        }
    }
}

package com.example.callbridge.network

import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import com.example.callbridge.protocol.CallStateMessage
import com.example.callbridge.protocol.CommandMessage
import com.example.callbridge.protocol.CommandResultMessage
import com.example.callbridge.protocol.CommandValidationResult
import com.example.callbridge.protocol.MessageSerializer
import com.example.callbridge.protocol.ProtocolConstants
import com.example.callbridge.security.SessionAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Local TCP newline-delimited JSON control server separated from media/audio transport.
 *
 * Binds cleanly to the active local interface (or wildcard when transitioning), validates incoming
 * commands, enforces [SessionAuthenticator] before sensitive actions, and handles disconnections safely.
 */
class LocalControlServer(
    private val sessionAuthenticator: SessionAuthenticator,
    private val port: Int = AppConfig.DEFAULT_CONTROL_PORT
) {
    private val _serverState = MutableStateFlow(ServerRunState.STOPPED)
    val serverState: StateFlow<ServerRunState> = _serverState.asStateFlow()

    private val _connectedClient = MutableStateFlow<ConnectedNetworkClient?>(null)
    val connectedClient: StateFlow<ConnectedNetworkClient?> = _connectedClient.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var activeClientSocket: Socket? = null

    @Volatile
    private var activeClientWriter: BufferedWriter? = null

    private var commandHandler: (suspend (CommandMessage, String) -> CommandResultMessage)? = null

    fun setCommandHandler(handler: suspend (CommandMessage, String) -> CommandResultMessage) {
        commandHandler = handler
    }

    fun start(bindAddress: String?): Boolean {
        if (_serverState.value == ServerRunState.LISTENING) return true

        _serverState.value = ServerRunState.STARTING
        _lastError.value = null

        val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serverScope

        return try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                val bindInet = if (!bindAddress.isNullOrBlank()) {
                    InetAddress.getByName(bindAddress)
                } else {
                    InetAddress.getByName("0.0.0.0")
                }
                bind(InetSocketAddress(bindInet, port))
            }
            serverSocket = socket
            _serverState.value = ServerRunState.LISTENING
            Logger.i("Network", "Local control server started on ${bindAddress ?: "0.0.0.0"}:$port")

            acceptJob = serverScope.launch {
                acceptLoop(socket)
            }
            true
        } catch (t: Throwable) {
            _serverState.value = ServerRunState.ERROR
            _lastError.value = t.message ?: "Failed to bind control server"
            Logger.e("Network", "Failed to start local control server on port $port", t)
            stop()
            false
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        closeActiveClient("server stopped")
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        if (_serverState.value != ServerRunState.ERROR) {
            _serverState.value = ServerRunState.STOPPED
        }
        Logger.i("Network", "Local control server stopped")
    }

    fun sendCallState(message: CallStateMessage) {
        val json = MessageSerializer.serializeCallState(message)
        scope?.launch {
            writeLineSafe(json)
        }
    }

    /**
     * Performs a genuine TCP connect + PING round-trip probe against the running server socket.
     */
    suspend fun runLoopbackProbe(host: String): Boolean = withContext(Dispatchers.IO) {
        if (_serverState.value != ServerRunState.LISTENING) return@withContext false
        return@withContext try {
            Socket().use { client ->
                client.soTimeout = 2_000
                client.connect(InetSocketAddress(host, port), 2_000)
                val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val ping = """{"version":1,"id":"probe_1","cmd":"PING"}"""
                writer.write(ping)
                writer.newLine()
                writer.flush()
                val responseLine = reader.readLine()
                val parsed = responseLine?.let { MessageSerializer.deserializeCommandResult(it) }
                parsed?.success == true && parsed.id == "probe_1"
            }
        } catch (t: Throwable) {
            Logger.w("Network", "Loopback probe failed: ${t.message}")
            false
        }
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (scope?.isActive == true && !server.isClosed) {
            try {
                val client = server.accept()
                handleClient(client)
            } catch (_: SocketException) {
                break
            } catch (t: Throwable) {
                if (!server.isClosed) {
                    Logger.w("Network", "Accept loop error: ${t.message}")
                }
            }
        }
    }

    private suspend fun handleClient(client: Socket) {
        val remoteIp = client.inetAddress?.hostAddress ?: "unknown"
        val remotePort = client.port
        closeActiveClient("replaced by new client")

        activeClientSocket = client
        var clientAuthenticated = false
        _connectedClient.value = ConnectedNetworkClient(
            remoteAddress = remoteIp,
            remotePort = remotePort,
            authenticated = false,
            connectedAtMs = System.currentTimeMillis()
        )
        Logger.i("Network", "Local network client connected from $remoteIp:$remotePort")

        try {
            client.soTimeout = 60_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8))
            activeClientWriter = writer

            while (scope?.isActive == true && !client.isClosed) {
                val line = reader.readLine() ?: break
                if (line.length > ProtocolConstants.MAX_MESSAGE_BYTES) {
                    writeLineSafe(
                        MessageSerializer.serializeCommandResult(
                            CommandResultMessage(
                                id = "unknown",
                                success = false,
                                message = "Payload exceeds max frame size",
                                errorCode = "PAYLOAD_TOO_LARGE"
                            )
                        )
                    )
                    continue
                }

                val response = handleLine(line, remoteIp) { authenticated ->
                    clientAuthenticated = authenticated
                    _connectedClient.value = _connectedClient.value?.copy(authenticated = authenticated)
                }
                writeLineSafe(MessageSerializer.serializeCommandResult(response))
            }
        } catch (t: Throwable) {
            Logger.d("Network", "Client connection closed ($remoteIp): ${t.message}")
        } finally {
            closeActiveClient("client disconnected")
        }
    }

    private suspend fun handleLine(
        rawLine: String,
        remoteIp: String,
        onAuthChanged: (Boolean) -> Unit
    ): CommandResultMessage {
        val validation = MessageSerializer.parseAndValidateCommand(rawLine)
        return when (validation) {
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
                        val validToken = sessionAuthenticator.isAuthorized(cmd.sessionToken)
                        if (validToken) {
                            onAuthChanged(true)
                            Logger.i("Network", "TCP client authenticated ($remoteIp)")
                            CommandResultMessage(
                                id = cmd.id,
                                success = true,
                                message = "Local network session authenticated"
                            )
                        } else {
                            CommandResultMessage(
                                id = cmd.id,
                                success = false,
                                message = "Valid BLE session token required for local network control",
                                errorCode = "UNAUTHORIZED"
                            )
                        }
                    }
                    else -> {
                        if (cmd.cmd in ProtocolConstants.SENSITIVE_COMMANDS &&
                            !sessionAuthenticator.isAuthorized(cmd.sessionToken)
                        ) {
                            CommandResultMessage(
                                id = cmd.id,
                                success = false,
                                message = "Unauthorized local network command",
                                errorCode = "UNAUTHORIZED"
                            )
                        } else {
                            commandHandler?.invoke(cmd, remoteIp) ?: CommandResultMessage(
                                id = cmd.id,
                                success = false,
                                message = "Command handler unavailable",
                                errorCode = "HANDLER_UNAVAILABLE"
                            )
                        }
                    }
                }
            }
        }
    }

    private fun writeLineSafe(line: String) {
        val writer = activeClientWriter ?: return
        synchronized(this) {
            runCatching {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }
        }
    }

    private fun closeActiveClient(reason: String) {
        activeClientWriter = null
        runCatching { activeClientSocket?.close() }
        activeClientSocket = null
        if (_connectedClient.value != null) {
            _connectedClient.value = null
            Logger.i("Network", "Local client closed ($reason)")
        }
    }
}

package com.example.callbridge.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import com.example.callbridge.protocol.CallStateMessage
import com.example.callbridge.protocol.CommandMessage
import com.example.callbridge.protocol.CommandResultMessage
import com.example.callbridge.protocol.EndpointPayload
import com.example.callbridge.security.SessionAuthenticator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Monitors local Wi-Fi / Hotspot connectivity changes, resolves dynamic local IPv4 addresses,
 * manages [LocalControlServer] lifecycle with exponential backoff on socket failure, and
 * publishes current [EndpointPayload] updates for BLE signaling.
 */
class LocalNetworkManager(
    context: Context,
    sessionAuthenticator: SessionAuthenticator,
    val controlPort: Int = AppConfig.DEFAULT_CONTROL_PORT,
    val devAudioUdpPort: Int = AppConfig.DEFAULT_DEV_AUDIO_UDP_PORT
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val ipResolver = LocalIpResolver(appContext)
    private val controlServer = LocalControlServer(sessionAuthenticator, controlPort)

    private var scope: CoroutineScope? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var restartJob: Job? = null
    private var backoffMs = AppConfig.INITIAL_BACKOFF_MS
    private var isStarted = false
    private var currentBoundIp: String? = null

    private val _status = MutableStateFlow(buildSnapshot(null))
    val status: StateFlow<NetworkStatusSnapshot> = _status.asStateFlow()

    fun setCommandHandler(handler: suspend (CommandMessage, String) -> CommandResultMessage) {
        controlServer.setCommandHandler(handler)
    }

    fun start() {
        if (isStarted) {
            refreshNetworkAndRebind()
            return
        }
        isStarted = true
        val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = managerScope

        managerScope.launch {
            controlServer.serverState.collect { refreshSnapshotOnly() }
        }
        managerScope.launch {
            controlServer.connectedClient.collect { refreshSnapshotOnly() }
        }

        registerConnectivityMonitor()
        refreshNetworkAndRebind()
    }

    fun stop() {
        isStarted = false
        restartJob?.cancel()
        restartJob = null
        unregisterConnectivityMonitor()
        controlServer.stop()
        currentBoundIp = null
        scope?.cancel()
        scope = null
        _status.value = buildSnapshot(ipResolver.resolveActiveLocalAddress())
    }

    fun currentEndpointPayload(): EndpointPayload? {
        val ip = _status.value.localIp ?: return null
        return EndpointPayload(
            host = ip,
            port = controlPort,
            devAudioUdpPort = devAudioUdpPort
        )
    }

    fun publishCallState(message: CallStateMessage) {
        controlServer.sendCallState(message)
    }

    fun refreshNetworkAndRebind() {
        val resolved = ipResolver.resolveActiveLocalAddress()
        _status.value = buildSnapshot(resolved)

        if (!isStarted) return

        val resolvedIp = resolved?.hostIp
        if (resolvedIp == null) {
            if (controlServer.serverState.value == ServerRunState.LISTENING) {
                Logger.w("Network", "Local network lost; pausing local control server")
                controlServer.stop()
                currentBoundIp = null
            }
            refreshSnapshotOnly()
            return
        }

        if (resolvedIp != currentBoundIp || controlServer.serverState.value != ServerRunState.LISTENING) {
            controlServer.stop()
            val ok = controlServer.start(resolvedIp)
            if (ok) {
                currentBoundIp = resolvedIp
                backoffMs = AppConfig.INITIAL_BACKOFF_MS
            } else {
                scheduleRestartWithBackoff()
            }
            refreshSnapshotOnly()
        }
    }

    /**
     * Executes a live local IP discovery and TCP control server loopback PING test.
     */
    suspend fun runSelfTest(): String {
        val resolved = ipResolver.resolveActiveLocalAddress()
        _status.value = buildSnapshot(resolved)
        if (resolved == null) {
            val msg = "No active Wi-Fi or Hotspot local IPv4 address detected"
            Logger.w("Network", "Network self-test: $msg")
            return msg
        }

        val wasListening = controlServer.serverState.value == ServerRunState.LISTENING
        if (!wasListening) {
            controlServer.start(resolved.hostIp)
            currentBoundIp = resolved.hostIp
        }

        val probeOk = controlServer.runLoopbackProbe(resolved.hostIp)
        if (!wasListening && !isStarted) {
            controlServer.stop()
            currentBoundIp = null
        }
        refreshSnapshotOnly()

        val result = if (probeOk) {
            "OK (${resolved.transportType.name} ${resolved.hostIp}:$controlPort PING verified)"
        } else {
            "Bound ${resolved.hostIp}:$controlPort (${resolved.interfaceName}), loopback probe failed"
        }
        Logger.i("Network", "Network self-test: $result")
        return result
    }

    private fun scheduleRestartWithBackoff() {
        val managerScope = scope ?: return
        restartJob?.cancel()
        val delayMs = backoffMs
        backoffMs = (backoffMs * 2).coerceAtMost(AppConfig.MAX_BACKOFF_MS)
        restartJob = managerScope.launch {
            Logger.i("Network", "Retrying local server bind in ${delayMs}ms")
            delay(delayMs)
            if (isStarted) refreshNetworkAndRebind()
        }
    }

    private fun registerConnectivityMonitor() {
        val cm = connectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Logger.i("Network", "Network available; refreshing local address")
                refreshNetworkAndRebind()
            }

            override fun onLost(network: Network) {
                Logger.w("Network", "Network lost; re-evaluating local interfaces")
                refreshNetworkAndRebind()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                refreshNetworkAndRebind()
            }
        }
        networkCallback = callback
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        }.onFailure {
            Logger.w("Network", "Could not register NetworkCallback: ${it.message}")
        }
    }

    private fun unregisterConnectivityMonitor() {
        val cm = connectivityManager ?: return
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { cm.unregisterNetworkCallback(cb) }
    }

    private fun refreshSnapshotOnly() {
        val resolved = ipResolver.resolveActiveLocalAddress()
        _status.value = buildSnapshot(resolved)
    }

    private fun buildSnapshot(resolved: ResolvedLocalAddress?): NetworkStatusSnapshot {
        return NetworkStatusSnapshot(
            transportType = resolved?.transportType ?: NetworkTransportType.NONE,
            interfaceName = resolved?.interfaceName,
            localIp = resolved?.hostIp,
            controlPort = controlPort,
            devAudioUdpPort = devAudioUdpPort,
            serverState = controlServer.serverState.value,
            connectedClient = controlServer.connectedClient.value,
            lastError = controlServer.lastError.value
        )
    }
}

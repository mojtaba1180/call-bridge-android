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
import com.example.callbridge.security.KeyStoreManager
import com.example.callbridge.security.PairingManager
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
 * runs the local TLS WebSocket server (`wss://<IP>:8443/bridge/v1`), advertises `_bridgelink._tcp.`
 * over Android NSD, and exposes [EndpointPayload] updates for BLE signaling and QR pairing.
 */
class LocalNetworkManager(
    context: Context,
    private val keyStoreManager: KeyStoreManager,
    private val pairingManager: PairingManager,
    sessionAuthenticator: SessionAuthenticator,
    val wssPort: Int = AppConfig.DEFAULT_WSS_PORT,
    val wssPath: String = AppConfig.DEFAULT_WSS_PATH,
    val devAudioUdpPort: Int = AppConfig.DEFAULT_DEV_AUDIO_UDP_PORT
) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val ipResolver = LocalIpResolver(appContext)
    private val webSocketServer = WebSocketServer(keyStoreManager, sessionAuthenticator, wssPort, wssPath, pairingManager)
    private val discoveryManager = NetworkDiscoveryManager(appContext)

    private var scope: CoroutineScope? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var restartJob: Job? = null
    private var backoffMs = AppConfig.INITIAL_BACKOFF_MS
    private var isStarted = false
    private var currentBoundIp: String? = null

    private val _status = MutableStateFlow(buildSnapshot(null))
    val status: StateFlow<NetworkStatusSnapshot> = _status.asStateFlow()

    fun setCommandHandler(handler: suspend (CommandMessage, String) -> CommandResultMessage) {
        webSocketServer.setCommandHandler(handler)
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
            webSocketServer.serverState.collect { refreshSnapshotOnly() }
        }
        managerScope.launch {
            webSocketServer.connectedClient.collect { refreshSnapshotOnly() }
        }
        managerScope.launch {
            discoveryManager.isRegistered.collect { refreshSnapshotOnly() }
        }

        registerConnectivityMonitor()
        refreshNetworkAndRebind()
    }

    fun disconnectClients() {
        webSocketServer.disconnectActiveClient()
        refreshSnapshotOnly()
    }

    fun stop() {
        isStarted = false
        restartJob?.cancel()
        restartJob = null
        unregisterConnectivityMonitor()
        discoveryManager.unregisterService()
        webSocketServer.stop()
        currentBoundIp = null
        scope?.cancel()
        scope = null
        _status.value = buildSnapshot(ipResolver.resolveActiveLocalAddress())
    }

    fun currentEndpointPayload(): EndpointPayload? {
        val ip = _status.value.localIp ?: return null
        return EndpointPayload(
            host = ip,
            port = wssPort,
            path = wssPath,
            scheme = "wss",
            certSha256 = runCatching { keyStoreManager.getServerCertificateFingerprint() }.getOrNull(),
            devAudioUdpPort = devAudioUdpPort
        )
    }

    fun publishCallState(message: CallStateMessage) {
        webSocketServer.sendCallState(message)
    }

    fun sendRawFrame(json: String): Boolean {
        return webSocketServer.sendRawTextFrame(json)
    }

    fun refreshNetworkAndRebind() {
        val resolved = ipResolver.resolveActiveLocalAddress()
        _status.value = buildSnapshot(resolved)

        if (!isStarted) return

        val resolvedIp = resolved?.hostIp
        if (resolvedIp == null) {
            if (webSocketServer.serverState.value == ServerRunState.LISTENING) {
                Logger.w("Network", "Local network lost; pausing TLS WebSocket server & NSD")
                discoveryManager.unregisterService()
                webSocketServer.stop()
                currentBoundIp = null
            }
            refreshSnapshotOnly()
            return
        }

        if (resolvedIp != currentBoundIp || webSocketServer.serverState.value != ServerRunState.LISTENING) {
            discoveryManager.unregisterService()
            webSocketServer.stop()
            val ok = webSocketServer.start(resolvedIp)
            if (ok) {
                currentBoundIp = resolvedIp
                backoffMs = AppConfig.INITIAL_BACKOFF_MS
                val fp = runCatching { keyStoreManager.getServerCertificateFingerprint() }.getOrNull()
                discoveryManager.registerService(wssPort, pairingManager.localDeviceId, fp)
            } else {
                scheduleRestartWithBackoff()
            }
            refreshSnapshotOnly()
        }
    }

    /**
     * Executes a live local IP discovery + TLS WebSocket certificate-pinned loopback PING test.
     */
    suspend fun runSelfTest(): String {
        val resolved = ipResolver.resolveActiveLocalAddress()
        _status.value = buildSnapshot(resolved)
        if (resolved == null) {
            val msg = "No active Wi-Fi or Hotspot local IPv4 address detected"
            Logger.w("Network", "Network self-test: $msg")
            return msg
        }

        val wasListening = webSocketServer.serverState.value == ServerRunState.LISTENING
        if (!wasListening) {
            webSocketServer.start(resolved.hostIp)
            currentBoundIp = resolved.hostIp
        }

        val probeOk = webSocketServer.runLoopbackProbe(resolved.hostIp)
        if (!wasListening && !isStarted) {
            webSocketServer.stop()
            currentBoundIp = null
        }
        refreshSnapshotOnly()

        val result = if (probeOk) {
            "OK (TLS wss://${resolved.hostIp}:$wssPort$wssPath pinned PING verified)"
        } else {
            "Bound wss://${resolved.hostIp}:$wssPort (${resolved.interfaceName}), TLS probe failed"
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
            Logger.i("Network", "Retrying TLS WebSocket server bind in ${delayMs}ms")
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
        val fp = runCatching { keyStoreManager.getServerCertificateFingerprint() }.getOrNull()
        return NetworkStatusSnapshot(
            transportType = resolved?.transportType ?: NetworkTransportType.NONE,
            interfaceName = resolved?.interfaceName,
            localIp = resolved?.hostIp,
            wssPort = wssPort,
            wssPath = wssPath,
            devAudioUdpPort = devAudioUdpPort,
            certSha256Fingerprint = fp,
            nsdRegistered = discoveryManager.isRegistered.value,
            serverState = webSocketServer.serverState.value,
            connectedClient = webSocketServer.connectedClient.value,
            lastError = webSocketServer.lastError.value
        )
    }
}

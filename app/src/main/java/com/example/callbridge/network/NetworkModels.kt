package com.example.callbridge.network

enum class NetworkTransportType {
    WIFI,
    HOTSPOT,
    ETHERNET,
    OTHER_LOCAL,
    NONE
}

enum class ServerRunState {
    STOPPED,
    STARTING,
    LISTENING,
    ERROR
}

data class ResolvedLocalAddress(
    val hostIp: String,
    val interfaceName: String,
    val transportType: NetworkTransportType
)

data class ConnectedNetworkClient(
    val remoteAddress: String,
    val remotePort: Int,
    val authenticated: Boolean,
    val connectedAtMs: Long
)

data class NetworkStatusSnapshot(
    val transportType: NetworkTransportType,
    val interfaceName: String?,
    val localIp: String?,
    val wssPort: Int,
    val wssPath: String,
    val devAudioUdpPort: Int,
    val certSha256Fingerprint: String?,
    val nsdRegistered: Boolean,
    val serverState: ServerRunState,
    val connectedClient: ConnectedNetworkClient?,
    val lastError: String?
) {
    val wssEndpointUrl: String?
        get() = localIp?.let { "wss://$it:$wssPort$wssPath" }
}

package com.example.callbridge.protocol

/**
 * Local TLS WebSocket & discovery endpoint advertised to the paired iPhone over BLE signaling.
 */
data class EndpointPayload(
    val host: String,
    val port: Int,
    val path: String = "/bridge/v1",
    val scheme: String = "wss",
    val certSha256: String? = null,
    val devAudioUdpPort: Int? = null
)

/**
 * Outbound CALL_STATE notification payload sent via BLE EVENT_STREAM and local TLS WebSocket.
 */
data class CallStateMessage(
    val version: Int = ProtocolConstants.VERSION,
    val type: String = ProtocolConstants.TYPE_CALL_STATE,
    val state: String,
    val callId: String?,
    val number: String?,
    val displayName: String?,
    val direction: String?,
    val subscriptionId: Int? = null,
    val startTime: Long? = null,
    val endpoint: EndpointPayload?,
    val audioCapability: String,
    val callControlSupported: Boolean,
    val timestamp: Long
) : BridgeMessage

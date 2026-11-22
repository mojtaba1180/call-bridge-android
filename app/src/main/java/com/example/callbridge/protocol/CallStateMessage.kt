package com.example.callbridge.protocol

/**
 * Local network endpoint advertised to the paired iPhone over BLE signaling.
 */
data class EndpointPayload(
    val host: String,
    val port: Int,
    val devAudioUdpPort: Int? = null
)

/**
 * Outbound CALL_STATE notification payload sent via CALL_STATE_CHAR (READ / NOTIFY).
 */
data class CallStateMessage(
    val version: Int = ProtocolConstants.VERSION,
    val type: String = ProtocolConstants.TYPE_CALL_STATE,
    val state: String,
    val callId: String?,
    val number: String?,
    val displayName: String?,
    val direction: String?,
    val endpoint: EndpointPayload?,
    val audioCapability: String,
    val callControlSupported: Boolean,
    val timestamp: Long
) : BridgeMessage

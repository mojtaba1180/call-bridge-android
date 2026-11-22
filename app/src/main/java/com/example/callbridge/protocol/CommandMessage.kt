package com.example.callbridge.protocol

/**
 * Inbound command sent by the iPhone over COMMAND_CHAR or the authenticated local TCP control channel.
 */
data class CommandMessage(
    val version: Int,
    val id: String,
    val cmd: String,
    val number: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val pinCode: String? = null,
    val sessionToken: String? = null,
    val signature: String? = null,
    val timestamp: Long? = null
) : BridgeMessage

/**
 * Outbound acknowledgement / execution response correlating to an incoming [CommandMessage.id].
 */
data class CommandResultMessage(
    val version: Int = ProtocolConstants.VERSION,
    val type: String = ProtocolConstants.TYPE_COMMAND_RESULT,
    val id: String,
    val success: Boolean,
    val message: String,
    val errorCode: String? = null,
    val capabilityStatus: String? = null,
    val sessionToken: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) : BridgeMessage

/**
 * Outbound pairing state notification sent during the explicit-approval handshake.
 */
data class PairingStatusMessage(
    val version: Int = ProtocolConstants.VERSION,
    val type: String = ProtocolConstants.TYPE_PAIRING_STATUS,
    val status: String,
    val deviceId: String?,
    val challengeNonce: String? = null,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
) : BridgeMessage

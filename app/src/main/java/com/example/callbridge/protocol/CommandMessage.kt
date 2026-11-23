package com.example.callbridge.protocol

import com.example.callbridge.contacts.BridgeContact

/**
 * Inbound command sent by the iPhone over BLE COMMAND/PAIRING characteristics
 * or over the authenticated local TLS WebSocket channel (`wss://<IP>:8443/bridge/v1`).
 */
data class CommandMessage(
    val version: Int,
    val id: String,
    val cmd: String,
    val number: String? = null,
    val deviceId: String? = null,
    val deviceName: String? = null,
    val pinCode: String? = null,
    val pairingToken: String? = null,
    val sessionToken: String? = null,
    val idempotencyKey: String? = null,
    val signature: String? = null,
    val timestamp: Long? = null,
    val subscriptionId: Int? = null,
    val smsBody: String? = null,
    val contactId: String? = null,
    val expectedFingerprint: String? = null,
    val contact: BridgeContact? = null,
    val confirmed: Boolean = false,
    val syncEntity: String? = null,
    val syncPageNumber: Int? = null,
    val syncPageSize: Int? = null,
    val syncCursorRecordId: String? = null
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
    val dataJson: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) : BridgeMessage

/**
 * Outbound pairing state notification sent during the explicit-approval QR/PIN handshake.
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

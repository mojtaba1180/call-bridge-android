package com.example.callbridge.protocol

import com.example.callbridge.core.AppConfig

/**
 * Stable wire-protocol constants shared between the Android Companion and future iOS (CoreBluetooth + CallKit) client.
 */
object ProtocolConstants {
    const val VERSION = AppConfig.PROTOCOL_VERSION

    // Envelope message types
    const val TYPE_CALL_STATE = "CALL_STATE"
    const val TYPE_COMMAND_RESULT = "COMMAND_RESULT"
    const val TYPE_PAIRING_CHALLENGE = "PAIRING_CHALLENGE"
    const val TYPE_PAIRING_STATUS = "PAIRING_STATUS"
    const val TYPE_HEARTBEAT = "HEARTBEAT"

    // Incoming command verbs
    const val CMD_ACCEPT = "ACCEPT"
    const val CMD_REJECT = "REJECT"
    const val CMD_HANGUP = "HANGUP"
    const val CMD_DIAL = "DIAL"
    const val CMD_PAIR_REQUEST = "PAIR_REQUEST"
    const val CMD_PAIR_VERIFY = "PAIR_VERIFY"
    const val CMD_AUTH = "AUTH"
    const val CMD_PING = "PING"
    const val CMD_GET_STATUS = "GET_STATUS"

    val VALID_COMMANDS = setOf(
        CMD_ACCEPT,
        CMD_REJECT,
        CMD_HANGUP,
        CMD_DIAL,
        CMD_PAIR_REQUEST,
        CMD_PAIR_VERIFY,
        CMD_AUTH,
        CMD_PING,
        CMD_GET_STATUS
    )

    val SENSITIVE_COMMANDS = setOf(
        CMD_ACCEPT,
        CMD_REJECT,
        CMD_HANGUP,
        CMD_DIAL
    )

    // BLE notification/write framing header (4 bytes):
    // [0] = Magic byte (0xCB), [1] = Message ID (0..255), [2] = Frame index, [3] = Total frames
    const val FRAME_MAGIC: Byte = 0xCB.toByte()
    const val FRAME_HEADER_SIZE = 4
    const val MAX_FRAME_COUNT = 64

    // Maximum allowed length of a single JSON message
    const val MAX_MESSAGE_BYTES = 8 * 1024
}

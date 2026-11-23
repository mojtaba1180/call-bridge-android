package com.example.callbridge.protocol

import com.example.callbridge.core.AppConfig

/**
 * Stable wire-protocol constants shared between the BridgeLink Android Companion
 * and future iOS (CoreBluetooth + CallKit + URLSessionWebSocketTask) client.
 */
object ProtocolConstants {
    const val VERSION = AppConfig.PROTOCOL_VERSION

    // Envelope message types
    const val TYPE_COMMAND = "COMMAND"
    const val TYPE_EVENT = "EVENT"
    const val TYPE_CALL_STATE = "CALL_STATE"
    const val TYPE_COMMAND_RESULT = "COMMAND_RESULT"
    const val TYPE_SYNC_PAGE = "SYNC_PAGE"
    const val TYPE_SYNC_ACK = "SYNC_ACK"
    const val TYPE_PAIRING_CHALLENGE = "PAIRING_CHALLENGE"
    const val TYPE_PAIRING_STATUS = "PAIRING_STATUS"
    const val TYPE_HEARTBEAT = "HEARTBEAT"

    // BridgeLink v1 canonical command verbs
    const val CMD_CALL_ACCEPT = "CALL_ACCEPT"
    const val CMD_CALL_REJECT = "CALL_REJECT"
    const val CMD_CALL_HANGUP = "CALL_HANGUP"
    const val CMD_CALL_DIAL = "CALL_DIAL"
    const val CMD_SYNC_START = "SYNC_START"
    const val CMD_SYNC_ACK = "SYNC_ACK"
    const val CMD_CONTACT_UPSERT = "CONTACT_UPSERT"
    const val CMD_CONTACT_DELETE = "CONTACT_DELETE"
    const val CMD_SMS_SEND = "SMS_SEND"
    const val CMD_PAIR_REQUEST = "PAIR_REQUEST"
    const val CMD_PAIR_VERIFY = "PAIR_VERIFY"
    const val CMD_AUTH = "AUTH"
    const val CMD_PING = "PING"
    const val CMD_GET_STATUS = "GET_STATUS"

    // Backward-compatible CallBridge v1 aliases
    const val CMD_ACCEPT = "ACCEPT"
    const val CMD_REJECT = "REJECT"
    const val CMD_HANGUP = "HANGUP"
    const val CMD_DIAL = "DIAL"

    val VALID_COMMANDS = setOf(
        CMD_CALL_ACCEPT,
        CMD_CALL_REJECT,
        CMD_CALL_HANGUP,
        CMD_CALL_DIAL,
        CMD_ACCEPT,
        CMD_REJECT,
        CMD_HANGUP,
        CMD_DIAL,
        CMD_SYNC_START,
        CMD_SYNC_ACK,
        CMD_CONTACT_UPSERT,
        CMD_CONTACT_DELETE,
        CMD_SMS_SEND,
        CMD_PAIR_REQUEST,
        CMD_PAIR_VERIFY,
        CMD_AUTH,
        CMD_PING,
        CMD_GET_STATUS
    )

    val SENSITIVE_COMMANDS = setOf(
        CMD_CALL_ACCEPT,
        CMD_CALL_REJECT,
        CMD_CALL_HANGUP,
        CMD_CALL_DIAL,
        CMD_ACCEPT,
        CMD_REJECT,
        CMD_HANGUP,
        CMD_DIAL,
        CMD_SYNC_START,
        CMD_SYNC_ACK,
        CMD_CONTACT_UPSERT,
        CMD_CONTACT_DELETE,
        CMD_SMS_SEND,
        CMD_GET_STATUS
    )

    // 8-byte BLE framed transport header (matches BRIDGELINK_PROTOCOL.md):
    // [0]    = magic (0xB1)
    // [1]    = protocol version (1)
    // [2..3] = uint16 big-endian messageId (duplicate detection)
    // [4]    = chunkIndex (0..totalChunks-1)
    // [5]    = totalChunks (1..64)
    // [6..7] = uint16 big-endian payloadLength of this chunk
    const val FRAME_MAGIC: Byte = 0xB1.toByte()
    const val FRAME_HEADER_SIZE = 8
    const val MAX_FRAME_COUNT = 64
    const val DUPLICATE_MSG_WINDOW = 64

    // Maximum allowed length of a single JSON command message
    const val MAX_MESSAGE_BYTES = 32 * 1024
}

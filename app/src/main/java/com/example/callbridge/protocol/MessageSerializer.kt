package com.example.callbridge.protocol

import org.json.JSONException
import org.json.JSONObject

/**
 * Pure Android org.json serializer, deserializer, and strict validator for the CallBridge protocol.
 * Zero reflection or heavy third-party serialization dependencies; compatible across API 21–34.
 */
object MessageSerializer {

    private val phoneNumberRegex = Regex("^\\+?[0-9*#\\-() ]{3,24}$")

    fun serializeCallState(message: CallStateMessage): String {
        val json = JSONObject().apply {
            put("version", message.version)
            put("type", message.type)
            put("state", message.state)
            put("callId", message.callId ?: JSONObject.NULL)
            put("number", message.number ?: JSONObject.NULL)
            put("displayName", message.displayName ?: JSONObject.NULL)
            put("direction", message.direction ?: JSONObject.NULL)
            if (message.endpoint != null) {
                put("endpoint", JSONObject().apply {
                    put("host", message.endpoint.host)
                    put("port", message.endpoint.port)
                    message.endpoint.devAudioUdpPort?.let { put("devAudioUdpPort", it) }
                })
            } else {
                put("endpoint", JSONObject.NULL)
            }
            put("audioCapability", message.audioCapability)
            put("callControlSupported", message.callControlSupported)
            put("timestamp", message.timestamp)
        }
        return json.toString()
    }

    fun deserializeCallState(rawJson: String): CallStateMessage? {
        return try {
            val json = JSONObject(rawJson)
            val endpointObj = json.optJSONObject("endpoint")
            val endpoint = endpointObj?.let {
                val host = it.optString("host", "")
                val port = it.optInt("port", -1)
                if (host.isNotBlank() && port > 0) {
                    val udpPort = if (it.has("devAudioUdpPort") && !it.isNull("devAudioUdpPort")) {
                        it.optInt("devAudioUdpPort")
                    } else null
                    EndpointPayload(host = host, port = port, devAudioUdpPort = udpPort)
                } else null
            }
            CallStateMessage(
                version = json.optInt("version", ProtocolConstants.VERSION),
                type = json.optString("type", ProtocolConstants.TYPE_CALL_STATE),
                state = json.getString("state"),
                callId = json.optNullableString("callId"),
                number = json.optNullableString("number"),
                displayName = json.optNullableString("displayName"),
                direction = json.optNullableString("direction"),
                endpoint = endpoint,
                audioCapability = json.getString("audioCapability"),
                callControlSupported = json.optBoolean("callControlSupported", false),
                timestamp = json.getLong("timestamp")
            )
        } catch (_: JSONException) {
            null
        }
    }

    fun serializeCommand(command: CommandMessage): String {
        val json = JSONObject().apply {
            put("version", command.version)
            put("id", command.id)
            put("cmd", command.cmd)
            command.number?.let { put("number", it) }
            command.deviceId?.let { put("deviceId", it) }
            command.deviceName?.let { put("deviceName", it) }
            command.pinCode?.let { put("pinCode", it) }
            command.sessionToken?.let { put("sessionToken", it) }
            command.signature?.let { put("signature", it) }
            command.timestamp?.let { put("timestamp", it) }
        }
        return json.toString()
    }

    fun serializeCommandResult(result: CommandResultMessage): String {
        val json = JSONObject().apply {
            put("version", result.version)
            put("type", result.type)
            put("id", result.id)
            put("success", result.success)
            put("message", result.message)
            result.errorCode?.let { put("errorCode", it) }
            result.capabilityStatus?.let { put("capabilityStatus", it) }
            result.sessionToken?.let { put("sessionToken", it) }
            put("timestamp", result.timestamp)
        }
        return json.toString()
    }

    fun deserializeCommandResult(rawJson: String): CommandResultMessage? {
        return try {
            val json = JSONObject(rawJson)
            CommandResultMessage(
                version = json.optInt("version", ProtocolConstants.VERSION),
                type = json.optString("type", ProtocolConstants.TYPE_COMMAND_RESULT),
                id = json.getString("id"),
                success = json.getBoolean("success"),
                message = json.getString("message"),
                errorCode = json.optNullableString("errorCode"),
                capabilityStatus = json.optNullableString("capabilityStatus"),
                sessionToken = json.optNullableString("sessionToken"),
                timestamp = json.optLong("timestamp", 0L)
            )
        } catch (_: JSONException) {
            null
        }
    }

    fun serializePairingStatus(status: PairingStatusMessage): String {
        val json = JSONObject().apply {
            put("version", status.version)
            put("type", status.type)
            put("status", status.status)
            put("deviceId", status.deviceId ?: JSONObject.NULL)
            status.challengeNonce?.let { put("challengeNonce", it) }
            put("message", status.message)
            put("timestamp", status.timestamp)
        }
        return json.toString()
    }

    /**
     * Parses and strictly validates an inbound command JSON payload.
     */
    fun parseAndValidateCommand(rawJson: String): CommandValidationResult {
        if (rawJson.isBlank()) {
            return CommandValidationResult.Invalid("unknown", "EMPTY_PAYLOAD", "Command payload is empty")
        }
        if (rawJson.toByteArray(Charsets.UTF_8).size > ProtocolConstants.MAX_MESSAGE_BYTES) {
            return CommandValidationResult.Invalid("unknown", "PAYLOAD_TOO_LARGE", "Command exceeds size limit")
        }

        val json = try {
            JSONObject(rawJson)
        } catch (_: JSONException) {
            return CommandValidationResult.Invalid("unknown", "INVALID_JSON", "Malformed JSON payload")
        }

        val id = json.optNullableString("id")?.trim().orEmpty()
        if (id.isEmpty() || id.length > 64) {
            return CommandValidationResult.Invalid("unknown", "MISSING_COMMAND_ID", "Valid command 'id' is required")
        }

        val version = json.optInt("version", -1)
        if (version != ProtocolConstants.VERSION) {
            return CommandValidationResult.Invalid(
                id,
                "UNSUPPORTED_VERSION",
                "Protocol version $version is not supported (expected ${ProtocolConstants.VERSION})"
            )
        }

        val cmd = json.optNullableString("cmd")?.trim()?.uppercase().orEmpty()
        if (cmd !in ProtocolConstants.VALID_COMMANDS) {
            return CommandValidationResult.Invalid(id, "UNKNOWN_COMMAND", "Unsupported command: $cmd")
        }

        val number = json.optNullableString("number")?.trim()
        if (cmd == ProtocolConstants.CMD_DIAL) {
            if (number.isNullOrBlank() || !isValidDialNumber(number)) {
                return CommandValidationResult.Invalid(
                    id,
                    "INVALID_PHONE_NUMBER",
                    "DIAL command requires a valid phone number"
                )
            }
        }

        val deviceId = json.optNullableString("deviceId")?.trim()
        val deviceName = json.optNullableString("deviceName")?.trim()
        val pinCode = json.optNullableString("pinCode")?.trim()
        val sessionToken = json.optNullableString("sessionToken")?.trim()
        val signature = json.optNullableString("signature")?.trim()
        val timestamp = if (json.has("timestamp") && !json.isNull("timestamp")) {
            json.optLong("timestamp", -1L).takeIf { it > 0L }
        } else null

        if (cmd == ProtocolConstants.CMD_PAIR_REQUEST && deviceId.isNullOrBlank()) {
            return CommandValidationResult.Invalid(
                id,
                "MISSING_DEVICE_ID",
                "PAIR_REQUEST requires deviceId"
            )
        }

        if (cmd == ProtocolConstants.CMD_PAIR_VERIFY && (deviceId.isNullOrBlank() || pinCode.isNullOrBlank())) {
            return CommandValidationResult.Invalid(
                id,
                "MISSING_PAIRING_CREDENTIALS",
                "PAIR_VERIFY requires deviceId and pinCode"
            )
        }

        return CommandValidationResult.Valid(
            CommandMessage(
                version = version,
                id = id,
                cmd = cmd,
                number = number,
                deviceId = deviceId,
                deviceName = deviceName,
                pinCode = pinCode,
                sessionToken = sessionToken,
                signature = signature,
                timestamp = timestamp
            )
        )
    }

    fun isValidDialNumber(number: String): Boolean {
        val trimmed = number.trim()
        if (!phoneNumberRegex.matches(trimmed)) return false
        val digitsCount = trimmed.count { it.isDigit() }
        return digitsCount in 3..18
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return value as? String
    }
}

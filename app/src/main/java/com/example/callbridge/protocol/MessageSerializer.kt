package com.example.callbridge.protocol

import com.example.callbridge.contacts.BridgeContact
import com.example.callbridge.contacts.ContactEmail
import com.example.callbridge.contacts.ContactPhone
import com.example.callbridge.utils.BridgePhoneNumberUtils
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Pure Android org.json serializer, deserializer, and strict validator for the BridgeLink v1 protocol.
 * Supports both flat command objects and unified `{ version, id, type, payload: {...} }` envelopes.
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
            put("subscriptionId", message.subscriptionId ?: JSONObject.NULL)
            put("startTime", message.startTime ?: JSONObject.NULL)
            if (message.endpoint != null) {
                put("endpoint", JSONObject().apply {
                    put("host", message.endpoint.host)
                    put("port", message.endpoint.port)
                    put("path", message.endpoint.path)
                    put("scheme", message.endpoint.scheme)
                    message.endpoint.certSha256?.let { put("certSha256", it) }
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
                    EndpointPayload(
                        host = host,
                        port = port,
                        path = it.optString("path", "/bridge/v1"),
                        scheme = it.optString("scheme", "wss"),
                        certSha256 = it.optNullableString("certSha256"),
                        devAudioUdpPort = udpPort
                    )
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
                subscriptionId = json.optNullableInt("subscriptionId"),
                startTime = json.optNullableLong("startTime"),
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
            command.pairingToken?.let { put("token", it) }
            command.sessionToken?.let { put("sessionToken", it) }
            command.idempotencyKey?.let { put("idempotencyKey", it) }
            command.signature?.let { put("signature", it) }
            command.timestamp?.let { put("timestamp", it) }
            command.subscriptionId?.let { put("subscriptionId", it) }
            command.smsBody?.let { put("body", it) }
            command.contactId?.let { put("contactId", it) }
            command.expectedFingerprint?.let { put("expectedFingerprint", it) }
            if (command.confirmed) put("confirmed", true)
            command.syncEntity?.let { put("entity", it) }
            command.syncPageNumber?.let { put("pageNumber", it) }
            command.syncPageSize?.let { put("pageSize", it) }
            command.syncCursorRecordId?.let { put("cursor", it) }
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
            result.dataJson?.let { put("data", it) }
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
                dataJson = json.optNullableString("data"),
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
     * Supports both top-level command fields and nested `payload` envelopes.
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

        val payload = json.optJSONObject("payload") ?: json

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

        val cmd = (json.optNullableString("cmd") ?: payload.optNullableString("cmd") ?: payload.optNullableString("command"))
            ?.trim()?.uppercase().orEmpty()
        if (cmd !in ProtocolConstants.VALID_COMMANDS) {
            return CommandValidationResult.Invalid(id, "UNKNOWN_COMMAND", "Unsupported command: $cmd")
        }

        val number = (payload.optNullableString("number") ?: payload.optNullableString("destination") ?: json.optNullableString("number"))?.trim()
        if (cmd == ProtocolConstants.CMD_DIAL || cmd == ProtocolConstants.CMD_CALL_DIAL) {
            if (number.isNullOrBlank() || !isValidDialNumber(number)) {
                return CommandValidationResult.Invalid(
                    id,
                    "INVALID_PHONE_NUMBER",
                    "DIAL command requires a valid phone number"
                )
            }
        }

        val smsBody = (payload.optNullableString("body") ?: payload.optNullableString("smsBody"))?.trim()
        if (cmd == ProtocolConstants.CMD_SMS_SEND) {
            if (number.isNullOrBlank() || !isValidDialNumber(number)) {
                return CommandValidationResult.Invalid(
                    id,
                    "INVALID_PHONE_NUMBER",
                    "SMS_SEND requires a valid destination number"
                )
            }
            if (smsBody.isNullOrBlank()) {
                return CommandValidationResult.Invalid(
                    id,
                    "EMPTY_SMS_BODY",
                    "SMS_SEND requires a non-empty body"
                )
            }
        }

        val deviceId = (payload.optNullableString("deviceId") ?: json.optNullableString("deviceId"))?.trim()
        val deviceName = (payload.optNullableString("deviceName") ?: json.optNullableString("deviceName"))?.trim()
        val pinCode = (payload.optNullableString("pinCode") ?: json.optNullableString("pinCode"))?.trim()
        val pairingToken = (payload.optNullableString("token") ?: json.optNullableString("token"))?.trim()
        val sessionToken = (json.optNullableString("sessionToken") ?: payload.optNullableString("sessionToken"))?.trim()
        val idempotencyKey = (json.optNullableString("idempotencyKey") ?: payload.optNullableString("idempotencyKey"))?.trim()
        val signature = (json.optNullableString("signature") ?: payload.optNullableString("signature"))?.trim()
        val timestamp = json.optNullableLong("timestamp") ?: payload.optNullableLong("timestamp")
        val subscriptionId = payload.optNullableInt("subscriptionId") ?: json.optNullableInt("subscriptionId")
        val contactId = (payload.optNullableString("contactId") ?: json.optNullableString("contactId"))?.trim()
        val expectedFingerprint = (payload.optNullableString("expectedFingerprint") ?: json.optNullableString("expectedFingerprint"))?.trim()
        val confirmed = payload.optBoolean("confirmed", json.optBoolean("confirmed", false))
        val contactObj = payload.optJSONObject("contact") ?: json.optJSONObject("contact")
        val parsedContact = contactObj?.let { parseBridgeContact(it, contactId) }

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

        if (cmd == ProtocolConstants.CMD_CONTACT_UPSERT && parsedContact == null) {
            return CommandValidationResult.Invalid(
                id,
                "MISSING_CONTACT_PAYLOAD",
                "CONTACT_UPSERT requires a valid 'contact' object with displayName"
            )
        }

        if (cmd == ProtocolConstants.CMD_CONTACT_DELETE && contactId.isNullOrBlank()) {
            return CommandValidationResult.Invalid(
                id,
                "MISSING_CONTACT_ID",
                "CONTACT_DELETE requires contactId"
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
                pairingToken = pairingToken,
                sessionToken = sessionToken,
                idempotencyKey = idempotencyKey,
                signature = signature,
                timestamp = timestamp,
                subscriptionId = subscriptionId,
                smsBody = smsBody,
                contactId = contactId ?: parsedContact?.contactId?.takeIf { it.isNotBlank() },
                expectedFingerprint = expectedFingerprint,
                contact = parsedContact,
                confirmed = confirmed,
                syncEntity = payload.optNullableString("entity") ?: json.optNullableString("entity"),
                syncPageNumber = payload.optNullableInt("pageNumber") ?: json.optNullableInt("pageNumber"),
                syncPageSize = payload.optNullableInt("pageSize") ?: json.optNullableInt("pageSize"),
                syncCursorRecordId = payload.optNullableString("cursor") ?: json.optNullableString("cursor")
            )
        )
    }

    fun isValidDialNumber(number: String): Boolean {
        val trimmed = number.trim()
        if (!phoneNumberRegex.matches(trimmed)) return false
        val digitsCount = trimmed.count { it.isDigit() }
        return digitsCount in 3..18
    }

    private fun parseBridgeContact(obj: JSONObject, fallbackId: String?): BridgeContact? {
        val displayName = obj.optNullableString("displayName")?.trim().orEmpty()
        if (displayName.isBlank()) return null

        val phones = mutableListOf<ContactPhone>()
        val phonesArr: JSONArray? = obj.optJSONArray("phones")
        if (phonesArr != null) {
            for (i in 0 until phonesArr.length()) {
                val pObj = phonesArr.optJSONObject(i) ?: continue
                val raw = pObj.optNullableString("number")?.trim().orEmpty()
                if (raw.isNotBlank()) {
                    phones.add(
                        ContactPhone(
                            number = raw,
                            normalizedNumber = BridgePhoneNumberUtils.normalizeForMatching(raw),
                            label = pObj.optNullableString("label") ?: "MOBILE"
                        )
                    )
                }
            }
        }

        val emails = mutableListOf<ContactEmail>()
        val emailsArr: JSONArray? = obj.optJSONArray("emails")
        if (emailsArr != null) {
            for (i in 0 until emailsArr.length()) {
                val eObj = emailsArr.optJSONObject(i) ?: continue
                val addr = eObj.optNullableString("address")?.trim().orEmpty()
                if (addr.isNotBlank()) {
                    emails.add(
                        ContactEmail(
                            address = addr,
                            label = eObj.optNullableString("label") ?: "HOME"
                        )
                    )
                }
            }
        }

        return BridgeContact(
            contactId = obj.optNullableString("id")?.trim() ?: fallbackId.orEmpty(),
            lookupKey = obj.optNullableString("lookupKey"),
            firstName = obj.optNullableString("firstName"),
            lastName = obj.optNullableString("lastName"),
            displayName = displayName,
            phones = phones,
            emails = emails,
            organization = obj.optNullableString("organization"),
            hasPhoto = obj.optBoolean("hasPhoto", false),
            updatedAtMs = obj.optLong("updatedAt", System.currentTimeMillis())
        )
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val value = opt(key)
        return value as? String
    }

    private fun JSONObject.optNullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getInt(key) }.getOrNull()
    }

    private fun JSONObject.optNullableLong(key: String): Long? {
        if (!has(key) || isNull(key)) return null
        return runCatching { getLong(key) }.getOrNull()?.takeIf { it > 0L }
    }
}

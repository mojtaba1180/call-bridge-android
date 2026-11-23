package com.example.callbridge.protocol

import com.example.callbridge.contacts.ContactResult
import com.example.callbridge.contacts.ContactsRepository
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import com.example.callbridge.database.dao.PendingCommandDao
import com.example.callbridge.database.entities.PendingCommandEntity
import com.example.callbridge.security.SessionAuthenticator
import com.example.callbridge.sms.SmsBridgeManager
import com.example.callbridge.sync.SyncEngine
import com.example.callbridge.telephony.CallManager
import org.json.JSONObject

/**
 * Central authenticated command dispatcher shared by both BLE GATT (`COMMAND`) and local
 * TLS WebSocket (`wss://<IP>:8443/bridge/v1`).
 *
 * Enforces:
 * - Session authentication + session expiration
 * - Replay timestamp window validation (`+/- 120s`)
 * - Request idempotency tracking (in-memory + Room `PendingCommandDao`)
 * - Honest capability reporting for Telecom, Contacts, Sync, and Creator-compliant SMS handoff
 */
class CommandRouter(
    private val sessionAuthenticator: SessionAuthenticator,
    private val callManager: CallManager,
    private val contactsRepository: ContactsRepository,
    private val smsBridgeManager: SmsBridgeManager,
    private val syncEngine: SyncEngine,
    private val pendingCommandDao: PendingCommandDao,
    private val publishStatusCallback: () -> Unit,
    private val syncPagePublisher: suspend (String) -> Boolean
) {

    suspend fun routeCommand(
        command: CommandMessage,
        source: String,
        bleAddressForSessionCheck: String? = null
    ): CommandResultMessage {
        // 1. Authorization & replay checks for sensitive commands
        if (command.cmd in ProtocolConstants.SENSITIVE_COMMANDS) {
            if (!sessionAuthenticator.isAuthorized(command.sessionToken, bleAddressForSessionCheck)) {
                Logger.w("Security", "Blocked unauthorized command ${command.cmd} from $source")
                return CommandResultMessage(
                    id = command.id,
                    success = false,
                    message = "Authentication required before executing ${command.cmd}",
                    errorCode = "UNAUTHORIZED"
                )
            }
            if (!sessionAuthenticator.isTimestampFresh(command.timestamp)) {
                Logger.w("Security", "Rejected stale/replayed command ${command.cmd} from $source")
                return CommandResultMessage(
                    id = command.id,
                    success = false,
                    message = "Command timestamp outside allowed replay window (+/- 120s)",
                    errorCode = "REPLAY_TIMESTAMP_EXPIRED"
                )
            }
        }

        // 2. Idempotency guard for state-mutating commands
        val idemKey = command.idempotencyKey?.takeIf { it.isNotBlank() }
        if (idemKey != null && command.cmd in IDEMPOTENT_COMMANDS) {
            val previouslyStored = runCatching { pendingCommandDao.findByIdempotencyKey(idemKey) }.getOrNull()
            if (previouslyStored != null || !sessionAuthenticator.claimIdempotencyKey(idemKey)) {
                Logger.i("Protocol", "Duplicate command ${command.cmd} ignored (idempotencyKey matched)")
                return CommandResultMessage(
                    id = command.id,
                    success = true,
                    message = "Duplicate command ignored via idempotency key",
                    capabilityStatus = "IDEMPOTENT_REPLAY"
                )
            }
            runCatching {
                pendingCommandDao.upsert(
                    PendingCommandEntity(
                        commandId = command.id,
                        idempotencyKey = idemKey,
                        commandVerb = command.cmd,
                        status = "PENDING",
                        resultMessage = null,
                        createdAtMs = System.currentTimeMillis(),
                        completedAtMs = null
                    )
                )
            }
        }

        // 3. Dispatch command verb
        val result = when (command.cmd) {
            ProtocolConstants.CMD_PING -> CommandResultMessage(
                id = command.id,
                success = true,
                message = "PONG"
            )

            ProtocolConstants.CMD_CALL_ACCEPT, ProtocolConstants.CMD_ACCEPT -> {
                val res = callManager.answerCall()
                CommandResultMessage(
                    id = command.id,
                    success = res.success,
                    message = res.errorMessage,
                    errorCode = res.errorCode,
                    capabilityStatus = res.capabilityStatus.name
                )
            }

            ProtocolConstants.CMD_CALL_REJECT, ProtocolConstants.CMD_REJECT -> {
                val res = callManager.rejectCall()
                CommandResultMessage(
                    id = command.id,
                    success = res.success,
                    message = res.errorMessage,
                    errorCode = res.errorCode,
                    capabilityStatus = res.capabilityStatus.name
                )
            }

            ProtocolConstants.CMD_CALL_HANGUP, ProtocolConstants.CMD_HANGUP -> {
                val res = callManager.endCall()
                CommandResultMessage(
                    id = command.id,
                    success = res.success,
                    message = res.errorMessage,
                    errorCode = res.errorCode,
                    capabilityStatus = res.capabilityStatus.name
                )
            }

            ProtocolConstants.CMD_CALL_DIAL, ProtocolConstants.CMD_DIAL -> {
                val res = callManager.dialNumber(command.number.orEmpty(), command.subscriptionId)
                CommandResultMessage(
                    id = command.id,
                    success = res.success,
                    message = res.errorMessage,
                    errorCode = res.errorCode,
                    capabilityStatus = res.capabilityStatus.name
                )
            }

            ProtocolConstants.CMD_GET_STATUS -> {
                publishStatusCallback()
                CommandResultMessage(
                    id = command.id,
                    success = true,
                    message = "BridgeLink status snapshot published",
                    capabilityStatus = callManager.capability.value.name
                )
            }

            ProtocolConstants.CMD_SYNC_START -> {
                val pageSize = command.syncPageSize ?: AppConfig.DEFAULT_SYNC_PAGE_SIZE
                val snap = syncEngine.syncAndroidDataToIphone(
                    pageSize = pageSize,
                    pagePublisher = syncPagePublisher
                )
                val ok = snap.lastError == null
                CommandResultMessage(
                    id = command.id,
                    success = ok,
                    message = if (ok) "Sync completed (${snap.contactsSynced} contacts, ${snap.callHistorySynced} calls)" else snap.lastError.orEmpty(),
                    errorCode = if (ok) null else "SYNC_FAILED",
                    capabilityStatus = snap.state.name
                )
            }

            ProtocolConstants.CMD_SYNC_ACK -> {
                val entity = command.syncEntity.orEmpty()
                val pageNum = command.syncPageNumber ?: 1
                if (entity.isBlank()) {
                    CommandResultMessage(
                        id = command.id,
                        success = false,
                        message = "SYNC_ACK requires entity name",
                        errorCode = "MISSING_SYNC_ENTITY"
                    )
                } else {
                    syncEngine.acknowledgePage(
                        entityType = entity,
                        pageNumber = pageNum,
                        lastRecordId = command.syncCursorRecordId,
                        count = pageNum
                    )
                    CommandResultMessage(
                        id = command.id,
                        success = true,
                        message = "Acknowledged $entity page $pageNum"
                    )
                }
            }

            ProtocolConstants.CMD_CONTACT_UPSERT -> {
                val incoming = command.contact
                if (incoming == null) {
                    CommandResultMessage(
                        id = command.id,
                        success = false,
                        message = "Missing contact payload",
                        errorCode = "MISSING_CONTACT_PAYLOAD"
                    )
                } else {
                    val res = if (incoming.contactId.isNotBlank()) {
                        contactsRepository.updateContactWithConflictCheck(
                            contactId = incoming.contactId,
                            updated = incoming,
                            expectedFingerprint = command.expectedFingerprint
                        )
                    } else {
                        contactsRepository.createContact(incoming)
                    }
                    when (res) {
                        is ContactResult.Success -> CommandResultMessage(
                            id = command.id,
                            success = true,
                            message = res.message,
                            dataJson = JSONObject().apply {
                                put("contactId", res.contact?.contactId ?: JSONObject.NULL)
                                put("fingerprint", res.fingerprint ?: JSONObject.NULL)
                            }.toString()
                        )
                        is ContactResult.Conflict -> CommandResultMessage(
                            id = command.id,
                            success = false,
                            message = res.message,
                            errorCode = "CONTACT_CONFLICT",
                            dataJson = JSONObject().apply {
                                put("contactId", res.conflict.contactId)
                                put("actualAndroidFingerprint", res.conflict.actualAndroidFingerprint)
                            }.toString()
                        )
                        is ContactResult.Failure -> CommandResultMessage(
                            id = command.id,
                            success = false,
                            message = res.message,
                            errorCode = res.errorCode
                        )
                    }
                }
            }

            ProtocolConstants.CMD_CONTACT_DELETE -> {
                val res = contactsRepository.deleteContactConfirmed(
                    contactId = command.contactId.orEmpty(),
                    userConfirmed = command.confirmed
                )
                when (res) {
                    is ContactResult.Success -> CommandResultMessage(
                        id = command.id,
                        success = true,
                        message = res.message
                    )
                    is ContactResult.Conflict -> CommandResultMessage(
                        id = command.id,
                        success = false,
                        message = res.message,
                        errorCode = "CONTACT_CONFLICT"
                    )
                    is ContactResult.Failure -> CommandResultMessage(
                        id = command.id,
                        success = false,
                        message = res.message,
                        errorCode = res.errorCode
                    )
                }
            }

            ProtocolConstants.CMD_SMS_SEND -> {
                val smsRes = smsBridgeManager.enqueueSmsRequest(
                    destination = command.number.orEmpty(),
                    body = command.smsBody.orEmpty(),
                    subscriptionId = command.subscriptionId,
                    idempotencyKey = idemKey ?: command.id
                )
                CommandResultMessage(
                    id = command.id,
                    success = smsRes.state.name != "FAILED",
                    message = smsRes.message,
                    errorCode = if (smsRes.state.name == "FAILED") "SMS_QUEUE_REJECTED" else null,
                    capabilityStatus = smsRes.state.name,
                    dataJson = JSONObject().apply {
                        put("outboxId", smsRes.outboxId)
                        put("requiresUserHandoff", smsRes.requiresUserHandoff)
                        put("duplicateIgnored", smsRes.duplicateIgnored)
                    }.toString()
                )
            }

            else -> CommandResultMessage(
                id = command.id,
                success = false,
                message = "Unhandled command ${command.cmd} from $source",
                errorCode = "UNHANDLED_COMMAND"
            )
        }

        if (idemKey != null && command.cmd in IDEMPOTENT_COMMANDS) {
            runCatching {
                pendingCommandDao.upsert(
                    PendingCommandEntity(
                        commandId = command.id,
                        idempotencyKey = idemKey,
                        commandVerb = command.cmd,
                        status = if (result.success) "COMPLETED" else "FAILED",
                        resultMessage = result.message,
                        createdAtMs = System.currentTimeMillis(),
                        completedAtMs = System.currentTimeMillis()
                    )
                )
            }
        }
        return result
    }

    companion object {
        private val IDEMPOTENT_COMMANDS = setOf(
            ProtocolConstants.CMD_CALL_DIAL,
            ProtocolConstants.CMD_DIAL,
            ProtocolConstants.CMD_CONTACT_UPSERT,
            ProtocolConstants.CMD_CONTACT_DELETE,
            ProtocolConstants.CMD_SMS_SEND
        )
    }
}

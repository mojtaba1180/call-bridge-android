package com.example.callbridge.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import com.example.callbridge.core.Logger
import com.example.callbridge.database.dao.SmsOutboxDao
import com.example.callbridge.database.entities.SmsOutboxEntity
import com.example.callbridge.device.SimManager
import com.example.callbridge.protocol.MessageSerializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Play Creator Program-compliant SMS companion coordinator.
 *
 * Because `READ_SMS`, `RECEIVE_SMS`, and `SEND_SMS` are prohibited on the Google Play Creator Program,
 * this module:
 * - Never declares prohibited background SMS permissions in AndroidManifest.xml.
 * - Maintains an idempotent Room [SmsOutboxDao] queue so repeated remote commands are never duplicated.
 * - Validates phone numbers and explicit multi-SIM selection via [SimManager].
 * - Provides a zero-permission system handoff (`ACTION_SENDTO` `smsto:`) for user-visible sending.
 * - Never logs raw SMS message bodies or full phone numbers.
 */
class SmsBridgeManager(
    context: Context,
    private val smsOutboxDao: SmsOutboxDao,
    private val simManager: SimManager
) {
    private val appContext = context.applicationContext

    val outboxEntries: Flow<List<SmsOutboxEntity>> = smsOutboxDao.observeRecentOutbox()

    private val _status = MutableStateFlow(evaluateStatus(0, null))
    val status: StateFlow<SmsStatusSnapshot> = _status.asStateFlow()

    fun isDefaultSmsApp(): Boolean {
        return runCatching {
            Telephony.Sms.getDefaultSmsPackage(appContext) == appContext.packageName
        }.getOrDefault(false)
    }

    suspend fun refreshStatus(): SmsStatusSnapshot {
        simManager.refresh()
        val count = runCatching { smsOutboxDao.countAll() }.getOrDefault(0)
        val snap = evaluateStatus(count, _status.value.lastOutboxState)
        _status.value = snap
        return snap
    }

    /**
     * Queues an outgoing SMS request with strict idempotency protection and phone/SIM validation.
     * In Play Creator mode, marks the entry as [SmsOutboxState.HANDOFF_REQUIRED] so the user can
     * confirm and dispatch it through Android's zero-permission system messaging chooser.
     */
    suspend fun enqueueSmsRequest(
        destination: String,
        body: String,
        subscriptionId: Int?,
        idempotencyKey: String
    ): SmsSendResult {
        val cleanNumber = destination.trim()
        val cleanKey = idempotencyKey.ifBlank { UUID.randomUUID().toString() }

        val existing = smsOutboxDao.findByIdempotencyKey(cleanKey)
        if (existing != null) {
            Logger.i("SMS", "Duplicate SMS request ignored (idempotencyKey matched)")
            return SmsSendResult(
                outboxId = existing.outboxId,
                idempotencyKey = cleanKey,
                state = runCatching { SmsOutboxState.valueOf(existing.state) }.getOrDefault(SmsOutboxState.QUEUED),
                duplicateIgnored = true,
                requiresUserHandoff = existing.state == SmsOutboxState.HANDOFF_REQUIRED.name,
                message = "Duplicate SMS request ignored via idempotency key"
            )
        }

        if (!MessageSerializer.isValidDialNumber(cleanNumber)) {
            return SmsSendResult(
                outboxId = "",
                idempotencyKey = cleanKey,
                state = SmsOutboxState.FAILED,
                duplicateIgnored = false,
                requiresUserHandoff = false,
                message = "Invalid destination phone number"
            )
        }

        if (body.isBlank()) {
            return SmsSendResult(
                outboxId = "",
                idempotencyKey = cleanKey,
                state = SmsOutboxState.FAILED,
                duplicateIgnored = false,
                requiresUserHandoff = false,
                message = "SMS body cannot be empty"
            )
        }

        if (simManager.requiresExplicitSimSelection(subscriptionId)) {
            return SmsSendResult(
                outboxId = "",
                idempotencyKey = cleanKey,
                state = SmsOutboxState.FAILED,
                duplicateIgnored = false,
                requiresUserHandoff = false,
                message = "Multiple SIM cards detected; explicit SIM subscriptionId is required"
            )
        }

        val now = System.currentTimeMillis()
        val outboxId = "sms_${UUID.randomUUID().toString().take(8)}"
        val entity = SmsOutboxEntity(
            outboxId = outboxId,
            idempotencyKey = cleanKey,
            destinationMasked = Logger.maskPhoneNumber(cleanNumber),
            subscriptionId = subscriptionId ?: simManager.selectedSubscriptionId.value,
            state = SmsOutboxState.HANDOFF_REQUIRED.name,
            errorMessage = "Play Creator mode: confirm send via system SMS chooser",
            createdAtMs = now,
            updatedAtMs = now
        )
        smsOutboxDao.insertIgnore(entity)
        _status.value = evaluateStatus(smsOutboxDao.countAll(), SmsOutboxState.HANDOFF_REQUIRED.name)
        Logger.i("SMS", "Queued SMS outbox item $outboxId for ${entity.destinationMasked} (system handoff)")

        return SmsSendResult(
            outboxId = outboxId,
            idempotencyKey = cleanKey,
            state = SmsOutboxState.HANDOFF_REQUIRED,
            duplicateIgnored = false,
            requiresUserHandoff = true,
            message = "Queued in outbox; zero-permission system SMS handoff ready"
        )
    }

    /**
     * Creates a zero-permission system SMS handoff intent (`ACTION_SENDTO` with `smsto:`).
     */
    fun createSystemSmsHandoffIntent(destination: String, body: String): Intent {
        return Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(destination.trim())}")
            putExtra("sms_body", body)
        }
    }

    suspend fun markDispatchedToSystem(outboxId: String) {
        smsOutboxDao.updateState(
            outboxId = outboxId,
            state = SmsOutboxState.DISPATCHED_TO_SYSTEM.name,
            error = null,
            updatedAtMs = System.currentTimeMillis()
        )
        _status.value = evaluateStatus(smsOutboxDao.countAll(), SmsOutboxState.DISPATCHED_TO_SYSTEM.name)
        Logger.i("SMS", "SMS outbox item $outboxId handed off to system messaging app")
    }

    private fun evaluateStatus(outboxCount: Int, lastState: String?): SmsStatusSnapshot {
        val sims = simManager.subscriptions.value
        val selected = sims.firstOrNull { it.subscriptionId == simManager.selectedSubscriptionId.value }
        val simLabel = when {
            sims.isEmpty() -> "Unavailable / Grant Phone State"
            selected != null -> "${selected.displayName} (${selected.carrierName})"
            else -> "${sims.size} SIMs (Select explicitly)"
        }
        return SmsStatusSnapshot(
            mode = SmsBridgeMode.CREATOR_COMPLIANT_HANDOFF,
            defaultSmsRoleHeld = isDefaultSmsApp(),
            backgroundSmsPermissionsAllowed = false,
            outboxTotalCount = outboxCount,
            activeSimCount = sims.size,
            selectedSimLabel = simLabel,
            lastOutboxState = lastState
        )
    }
}

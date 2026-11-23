package com.example.callbridge.callhistory

import com.example.callbridge.core.Logger
import com.example.callbridge.database.dao.CallHistoryDao
import com.example.callbridge.database.entities.CallHistorySyncMetadataEntity
import com.example.callbridge.database.entities.ObservedCallHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Play Creator Program-compliant Call History repository.
 *
 * Avoids the prohibited `READ_CALL_LOG` permission by persisting calls observed directly
 * through BridgeLink's active Telephony / InCallService session into Room, supporting
 * pagination, tombstones, and sync metadata for iOS.
 */
class CallHistoryRepository(
    private val callHistoryDao: CallHistoryDao
) {
    val recentCalls: Flow<List<CallHistoryRecord>> =
        callHistoryDao.observeRecentCalls().map { list -> list.map { it.toModel() } }

    val observedCallCount: Flow<Int> = callHistoryDao.observeCallCount()

    suspend fun recordObservedCall(
        callId: String,
        rawPhoneNumber: String?,
        displayName: String?,
        callType: ObservedCallType,
        startedAtMs: Long,
        durationSec: Long,
        subscriptionId: Int?
    ) {
        val masked = Logger.maskPhoneNumber(rawPhoneNumber)
        val entity = ObservedCallHistoryEntity(
            callId = callId,
            phoneNumberMasked = masked,
            displayName = displayName,
            callType = callType.name,
            startedAtMs = startedAtMs,
            durationSec = durationSec.coerceAtLeast(0L),
            subscriptionId = subscriptionId,
            isDeletedTombstone = false
        )
        callHistoryDao.upsertCall(entity)
        Logger.i("CallHistory", "Recorded observed call ($callType, ${durationSec}s)")
    }

    suspend fun getPage(limit: Int, offset: Int): List<CallHistoryRecord> {
        return callHistoryDao.getPage(limit, offset).map { it.toModel() }
    }

    suspend fun countActive(): Int = callHistoryDao.countActive()

    suspend fun markSynced(callIds: List<String>) {
        val now = System.currentTimeMillis()
        callIds.forEach { id ->
            callHistoryDao.upsertSyncMeta(
                CallHistorySyncMetadataEntity(
                    callRecordId = id,
                    syncedAtMs = now,
                    isDeletedTombstone = false
                )
            )
        }
    }

    suspend fun deleteWithTombstone(callId: String) {
        callHistoryDao.markDeletedTombstone(callId)
        callHistoryDao.upsertSyncMeta(
            CallHistorySyncMetadataEntity(
                callRecordId = callId,
                syncedAtMs = System.currentTimeMillis(),
                isDeletedTombstone = true
            )
        )
    }

    suspend fun getTombstoneIds(): List<String> =
        callHistoryDao.getTombstones().map { it.callRecordId }

    private fun ObservedCallHistoryEntity.toModel(): CallHistoryRecord = CallHistoryRecord(
        callId = callId,
        phoneNumberMasked = phoneNumberMasked,
        displayName = displayName,
        callType = runCatching { ObservedCallType.valueOf(callType) }.getOrDefault(ObservedCallType.UNKNOWN),
        startedAtMs = startedAtMs,
        durationSec = durationSec,
        subscriptionId = subscriptionId
    )
}

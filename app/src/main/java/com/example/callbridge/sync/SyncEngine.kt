package com.example.callbridge.sync

import com.example.callbridge.callhistory.CallHistoryRepository
import com.example.callbridge.contacts.ContactsRepository
import com.example.callbridge.core.AppConfig
import com.example.callbridge.core.Logger
import com.example.callbridge.database.dao.SmsOutboxDao
import com.example.callbridge.database.dao.SyncCursorDao
import com.example.callbridge.database.dao.SyncSessionDao
import com.example.callbridge.database.entities.SyncCursorEntity
import com.example.callbridge.database.entities.SyncSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class SyncRunState {
    IDLE,
    SYNCING,
    COMPLETED,
    FAILED
}

data class SyncStatusSnapshot(
    val state: SyncRunState = SyncRunState.IDLE,
    val lastSessionId: String? = null,
    val lastSyncTimestampMs: Long? = null,
    val contactsSynced: Int = 0,
    val callHistorySynced: Int = 0,
    val smsOutboxSynced: Int = 0,
    val activePageProgress: String = "0 / 0",
    val lastError: String? = null
)

/**
 * Paginated synchronization coordinator implementing "Sync Android Data to iPhone".
 *
 * Uses bounded page sizes (default 150) so large contact lists or call histories are never loaded
 * into RAM at once, persists per-entity [SyncCursorEntity] checkpoints in Room, handles tombstones,
 * and emits structured `SYNC_PAGE` JSON envelopes to the active local transport callback.
 */
class SyncEngine(
    private val contactsRepository: ContactsRepository,
    private val callHistoryRepository: CallHistoryRepository,
    private val smsOutboxDao: SmsOutboxDao,
    private val syncCursorDao: SyncCursorDao,
    private val syncSessionDao: SyncSessionDao
) {
    private val _status = MutableStateFlow(SyncStatusSnapshot())
    val status: StateFlow<SyncStatusSnapshot> = _status.asStateFlow()

    /**
     * Executes a full or incremental paginated sync session and delivers every serialized page
     * through [pagePublisher] (e.g., Local TLS WebSocket Server).
     */
    suspend fun syncAndroidDataToIphone(
        pageSize: Int = AppConfig.DEFAULT_SYNC_PAGE_SIZE,
        pagePublisher: suspend (String) -> Boolean
    ): SyncStatusSnapshot = withContext(Dispatchers.IO) {
        val boundedPageSize = pageSize.coerceIn(25, AppConfig.MAX_SYNC_PAGE_SIZE)
        val sessionId = "sync_${UUID.randomUUID().toString().take(8)}"
        val startedAt = System.currentTimeMillis()

        _status.value = _status.value.copy(
            state = SyncRunState.SYNCING,
            lastSessionId = sessionId,
            lastError = null
        )
        syncSessionDao.upsert(
            SyncSessionEntity(
                sessionId = sessionId,
                status = "RUNNING",
                startedAtMs = startedAt,
                completedAtMs = null
            )
        )
        Logger.i("Sync", "Sync session $sessionId started (pageSize=$boundedPageSize)")

        try {
            val contactsTotal = syncContactsPaginated(sessionId, boundedPageSize, pagePublisher)
            val callsTotal = syncCallHistoryPaginated(sessionId, boundedPageSize, pagePublisher)
            val smsOutboxCount = smsOutboxDao.countAll()
            publishSmsOutboxSummary(sessionId, smsOutboxCount, pagePublisher)

            val completedAt = System.currentTimeMillis()
            syncSessionDao.upsert(
                SyncSessionEntity(
                    sessionId = sessionId,
                    status = "COMPLETED",
                    startedAtMs = startedAt,
                    completedAtMs = completedAt,
                    contactsSynced = contactsTotal,
                    callHistorySynced = callsTotal,
                    smsSynced = smsOutboxCount
                )
            )

            val done = SyncStatusSnapshot(
                state = SyncRunState.COMPLETED,
                lastSessionId = sessionId,
                lastSyncTimestampMs = completedAt,
                contactsSynced = contactsTotal,
                callHistorySynced = callsTotal,
                smsOutboxSynced = smsOutboxCount,
                activePageProgress = "Done ($contactsTotal contacts, $callsTotal calls)",
                lastError = null
            )
            _status.value = done
            Logger.i("Sync", "Sync session $sessionId completed (contacts=$contactsTotal, calls=$callsTotal)")
            done
        } catch (t: Throwable) {
            Logger.e("Sync", "Sync session $sessionId failed", t)
            syncSessionDao.upsert(
                SyncSessionEntity(
                    sessionId = sessionId,
                    status = "FAILED",
                    startedAtMs = startedAt,
                    completedAtMs = System.currentTimeMillis(),
                    errorMessage = t.message
                )
            )
            val failed = _status.value.copy(
                state = SyncRunState.FAILED,
                lastError = t.message ?: "Synchronization failed"
            )
            _status.value = failed
            failed
        }
    }

    suspend fun acknowledgePage(entityType: String, pageNumber: Int, lastRecordId: String?, count: Int) {
        val now = System.currentTimeMillis()
        syncCursorDao.upsert(
            SyncCursorEntity(
                entityType = entityType,
                lastSyncedTimestampMs = now,
                lastSyncedRecordId = lastRecordId,
                lastPageNumber = pageNumber,
                totalSyncedCount = count,
                updatedAtMs = now
            )
        )
    }

    private suspend fun syncContactsPaginated(
        sessionId: String,
        pageSize: Int,
        publisher: suspend (String) -> Boolean
    ): Int {
        var offset = 0
        var pageNum = 0
        var syncedCount = 0

        while (true) {
            val page = contactsRepository.getContactsPage(limit = pageSize, offset = offset)
            if (page.isEmpty()) break
            pageNum++

            val itemsArray = JSONArray()
            page.forEach { c ->
                itemsArray.put(JSONObject().apply {
                    put("id", c.contactId)
                    put("lookupKey", c.lookupKey ?: JSONObject.NULL)
                    put("displayName", c.displayName)
                    put("firstName", c.firstName ?: JSONObject.NULL)
                    put("lastName", c.lastName ?: JSONObject.NULL)
                    put("organization", c.organization ?: JSONObject.NULL)
                    put("fingerprint", c.computeFingerprint())
                    put("updatedAt", c.updatedAtMs)
                    put("phones", JSONArray().apply {
                        c.phones.forEach { p ->
                            put(JSONObject().apply {
                                put("number", p.number)
                                put("normalized", p.normalizedNumber)
                                put("label", p.label)
                            })
                        }
                    })
                    put("emails", JSONArray().apply {
                        c.emails.forEach { e ->
                            put(JSONObject().apply {
                                put("address", e.address)
                                put("label", e.label)
                            })
                        }
                    })
                })
            }

            val tombstones = if (pageNum == 1) contactsRepository.getTombstoneIds() else emptyList()
            val envelope = buildSyncPageEnvelope(
                sessionId = sessionId,
                entity = ENTITY_CONTACTS,
                pageNumber = pageNum,
                items = itemsArray,
                tombstones = tombstones,
                hasMore = page.size == pageSize
            )
            publisher(envelope)
            contactsRepository.recordSyncedFingerprints(page)
            syncedCount += page.size
            acknowledgePage(ENTITY_CONTACTS, pageNum, page.lastOrNull()?.contactId, syncedCount)
            _status.value = _status.value.copy(activePageProgress = "Contacts p$pageNum ($syncedCount)")

            if (page.size < pageSize) break
            offset += pageSize
        }
        return syncedCount
    }

    private suspend fun syncCallHistoryPaginated(
        sessionId: String,
        pageSize: Int,
        publisher: suspend (String) -> Boolean
    ): Int {
        var offset = 0
        var pageNum = 0
        var syncedCount = 0

        while (true) {
            val page = callHistoryRepository.getPage(limit = pageSize, offset = offset)
            if (page.isEmpty()) break
            pageNum++

            val itemsArray = JSONArray()
            page.forEach { r ->
                itemsArray.put(JSONObject().apply {
                    put("callId", r.callId)
                    put("phoneNumberMasked", r.phoneNumberMasked)
                    put("displayName", r.displayName ?: JSONObject.NULL)
                    put("callType", r.callType.name)
                    put("startedAt", r.startedAtMs)
                    put("durationSec", r.durationSec)
                    put("subscriptionId", r.subscriptionId ?: JSONObject.NULL)
                })
            }

            val tombstones = if (pageNum == 1) callHistoryRepository.getTombstoneIds() else emptyList()
            val envelope = buildSyncPageEnvelope(
                sessionId = sessionId,
                entity = ENTITY_CALL_HISTORY,
                pageNumber = pageNum,
                items = itemsArray,
                tombstones = tombstones,
                hasMore = page.size == pageSize
            )
            publisher(envelope)
            callHistoryRepository.markSynced(page.map { it.callId })
            syncedCount += page.size
            acknowledgePage(ENTITY_CALL_HISTORY, pageNum, page.lastOrNull()?.callId, syncedCount)
            _status.value = _status.value.copy(activePageProgress = "Calls p$pageNum ($syncedCount)")

            if (page.size < pageSize) break
            offset += pageSize
        }
        return syncedCount
    }

    private suspend fun publishSmsOutboxSummary(
        sessionId: String,
        outboxCount: Int,
        publisher: suspend (String) -> Boolean
    ) {
        val envelope = JSONObject().apply {
            put("version", AppConfig.PROTOCOL_VERSION)
            put("id", "${sessionId}_sms_meta")
            put("type", "SYNC_PAGE")
            put("timestamp", System.currentTimeMillis())
            put("payload", JSONObject().apply {
                put("sessionId", sessionId)
                put("entity", ENTITY_SMS_OUTBOX)
                put("mode", "CREATOR_COMPLIANT_HANDOFF")
                put("outboxCount", outboxCount)
                put("hasMore", false)
            })
        }.toString()
        publisher(envelope)
        acknowledgePage(ENTITY_SMS_OUTBOX, 1, null, outboxCount)
    }

    companion object {
        const val ENTITY_CONTACTS = "CONTACTS"
        const val ENTITY_CALL_HISTORY = "CALL_HISTORY"
        const val ENTITY_SMS_OUTBOX = "SMS_OUTBOX"

        fun buildSyncPageEnvelope(
            sessionId: String,
            entity: String,
            pageNumber: Int,
            items: JSONArray,
            tombstones: List<String>,
            hasMore: Boolean
        ): String {
            return JSONObject().apply {
                put("version", AppConfig.PROTOCOL_VERSION)
                put("id", "${sessionId}_${entity}_p$pageNumber")
                put("type", "SYNC_PAGE")
                put("timestamp", System.currentTimeMillis())
                put("payload", JSONObject().apply {
                    put("sessionId", sessionId)
                    put("entity", entity)
                    put("pageNumber", pageNumber)
                    put("hasMore", hasMore)
                    put("items", items)
                    put("tombstones", JSONArray(tombstones))
                })
            }.toString()
        }
    }
}

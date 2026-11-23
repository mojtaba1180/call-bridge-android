package com.example.callbridge.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.callbridge.database.entities.CallHistorySyncMetadataEntity
import com.example.callbridge.database.entities.ContactSyncMetadataEntity
import com.example.callbridge.database.entities.DeviceSettingsEntity
import com.example.callbridge.database.entities.ObservedCallHistoryEntity
import com.example.callbridge.database.entities.PairedDeviceEntity
import com.example.callbridge.database.entities.PendingCommandEntity
import com.example.callbridge.database.entities.SmsOutboxEntity
import com.example.callbridge.database.entities.SyncCursorEntity
import com.example.callbridge.database.entities.SyncSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PairedDeviceDao {
    @Query("SELECT * FROM paired_devices WHERE revoked = 0 ORDER BY pairedAtMs DESC LIMIT 1")
    fun observeActivePairedDevice(): Flow<PairedDeviceEntity?>

    @Query("SELECT * FROM paired_devices WHERE revoked = 0 ORDER BY pairedAtMs DESC LIMIT 1")
    suspend fun getActivePairedDevice(): PairedDeviceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(device: PairedDeviceEntity)

    @Query("UPDATE paired_devices SET revoked = 1 WHERE deviceId = :deviceId")
    suspend fun markRevoked(deviceId: String)

    @Query("DELETE FROM paired_devices")
    suspend fun clearAll()
}

@Dao
interface SyncCursorDao {
    @Query("SELECT * FROM sync_cursors")
    fun observeAllCursors(): Flow<List<SyncCursorEntity>>

    @Query("SELECT * FROM sync_cursors WHERE entityType = :entityType LIMIT 1")
    suspend fun getCursor(entityType: String): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_cursors")
    suspend fun clearAll()
}

@Dao
interface SyncSessionDao {
    @Query("SELECT * FROM sync_sessions ORDER BY startedAtMs DESC LIMIT 1")
    fun observeLatestSession(): Flow<SyncSessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SyncSessionEntity)
}

@Dao
interface SmsOutboxDao {
    @Query("SELECT * FROM sms_outbox ORDER BY createdAtMs DESC LIMIT 50")
    fun observeRecentOutbox(): Flow<List<SmsOutboxEntity>>

    @Query("SELECT * FROM sms_outbox WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): SmsOutboxEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entry: SmsOutboxEntity): Long

    @Query("UPDATE sms_outbox SET state = :state, errorMessage = :error, updatedAtMs = :updatedAtMs WHERE outboxId = :outboxId")
    suspend fun updateState(outboxId: String, state: String, error: String?, updatedAtMs: Long)

    @Query("SELECT COUNT(*) FROM sms_outbox")
    suspend fun countAll(): Int
}

@Dao
interface PendingCommandDao {
    @Query("SELECT * FROM pending_commands WHERE idempotencyKey = :key LIMIT 1")
    suspend fun findByIdempotencyKey(key: String): PendingCommandEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(command: PendingCommandEntity)
}

@Dao
interface ContactSyncMetadataDao {
    @Query("SELECT * FROM contact_sync_metadata")
    suspend fun getAll(): List<ContactSyncMetadataEntity>

    @Query("SELECT * FROM contact_sync_metadata WHERE contactId = :contactId LIMIT 1")
    suspend fun getById(contactId: String): ContactSyncMetadataEntity?

    @Query("SELECT * FROM contact_sync_metadata WHERE isDeletedTombstone = 1")
    suspend fun getTombstones(): List<ContactSyncMetadataEntity>

    @Query("SELECT COUNT(*) FROM contact_sync_metadata WHERE hasConflict = 1")
    fun observeConflictCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ContactSyncMetadataEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ContactSyncMetadataEntity)
}

@Dao
interface CallHistoryDao {
    @Query("SELECT * FROM observed_call_history WHERE isDeletedTombstone = 0 ORDER BY startedAtMs DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<ObservedCallHistoryEntity>

    @Query("SELECT * FROM observed_call_history WHERE isDeletedTombstone = 0 ORDER BY startedAtMs DESC LIMIT 50")
    fun observeRecentCalls(): Flow<List<ObservedCallHistoryEntity>>

    @Query("SELECT COUNT(*) FROM observed_call_history WHERE isDeletedTombstone = 0")
    fun observeCallCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM observed_call_history WHERE isDeletedTombstone = 0")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCall(entity: ObservedCallHistoryEntity)

    @Query("UPDATE observed_call_history SET isDeletedTombstone = 1 WHERE callId = :callId")
    suspend fun markDeletedTombstone(callId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncMeta(meta: CallHistorySyncMetadataEntity)

    @Query("SELECT * FROM call_history_sync_metadata WHERE isDeletedTombstone = 1")
    suspend fun getTombstones(): List<CallHistorySyncMetadataEntity>
}

@Dao
interface DeviceSettingsDao {
    @Query("SELECT * FROM device_settings WHERE `key` = :key LIMIT 1")
    suspend fun get(key: String): DeviceSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: DeviceSettingsEntity)
}

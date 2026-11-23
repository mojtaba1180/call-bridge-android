package com.example.callbridge.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "paired_devices")
data class PairedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val deviceName: String,
    val bleAddress: String?,
    val sharedSecretHash: String,
    val certFingerprint: String,
    val pairedAtMs: Long,
    val lastSeenAtMs: Long,
    val revoked: Boolean = false
)

@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val entityType: String, // CONTACTS, CALL_HISTORY, SMS_OUTBOX
    val lastSyncedTimestampMs: Long,
    val lastSyncedRecordId: String?,
    val lastPageNumber: Int,
    val totalSyncedCount: Int,
    val updatedAtMs: Long
)

@Entity(tableName = "sync_sessions")
data class SyncSessionEntity(
    @PrimaryKey val sessionId: String,
    val status: String, // RUNNING, COMPLETED, FAILED, CANCELLED
    val startedAtMs: Long,
    val completedAtMs: Long?,
    val contactsSynced: Int = 0,
    val callHistorySynced: Int = 0,
    val smsSynced: Int = 0,
    val conflictCount: Int = 0,
    val errorMessage: String? = null
)

@Entity(
    tableName = "sms_outbox",
    indices = [Index(value = ["idempotencyKey"], unique = true)]
)
data class SmsOutboxEntity(
    @PrimaryKey val outboxId: String,
    val idempotencyKey: String,
    val destinationMasked: String,
    val subscriptionId: Int?,
    val state: String, // QUEUED, HANDOFF_REQUIRED, DISPATCHED_TO_SYSTEM, FAILED, DELIVERY_UNKNOWN
    val errorMessage: String?,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

@Entity(
    tableName = "pending_commands",
    indices = [Index(value = ["idempotencyKey"], unique = true)]
)
data class PendingCommandEntity(
    @PrimaryKey val commandId: String,
    val idempotencyKey: String,
    val commandVerb: String,
    val status: String, // PENDING, COMPLETED, FAILED
    val resultMessage: String?,
    val createdAtMs: Long,
    val completedAtMs: Long?
)

@Entity(tableName = "contact_sync_metadata")
data class ContactSyncMetadataEntity(
    @PrimaryKey val contactId: String,
    val lookupKey: String?,
    val contentFingerprint: String,
    val version: Long,
    val lastUpdatedMs: Long,
    val isDeletedTombstone: Boolean = false,
    val hasConflict: Boolean = false
)

@Entity(tableName = "call_history_sync_metadata")
data class CallHistorySyncMetadataEntity(
    @PrimaryKey val callRecordId: String,
    val syncedAtMs: Long,
    val isDeletedTombstone: Boolean = false
)

/**
 * Creator-Program-compliant local call history observed directly by BridgeLink's own
 * Telephony/InCallService layer (avoids prohibited READ_CALL_LOG permission).
 */
@Entity(tableName = "observed_call_history")
data class ObservedCallHistoryEntity(
    @PrimaryKey val callId: String,
    val phoneNumberMasked: String,
    val displayName: String?,
    val callType: String, // INCOMING, OUTGOING, MISSED, REJECTED, UNKNOWN
    val startedAtMs: Long,
    val durationSec: Long,
    val subscriptionId: Int?,
    val isDeletedTombstone: Boolean = false
)

@Entity(tableName = "device_settings")
data class DeviceSettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAtMs: Long
)

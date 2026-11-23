package com.example.callbridge.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.callbridge.database.dao.CallHistoryDao
import com.example.callbridge.database.dao.ContactSyncMetadataDao
import com.example.callbridge.database.dao.DeviceSettingsDao
import com.example.callbridge.database.dao.PairedDeviceDao
import com.example.callbridge.database.dao.PendingCommandDao
import com.example.callbridge.database.dao.SmsOutboxDao
import com.example.callbridge.database.dao.SyncCursorDao
import com.example.callbridge.database.dao.SyncSessionDao
import com.example.callbridge.database.entities.CallHistorySyncMetadataEntity
import com.example.callbridge.database.entities.ContactSyncMetadataEntity
import com.example.callbridge.database.entities.DeviceSettingsEntity
import com.example.callbridge.database.entities.ObservedCallHistoryEntity
import com.example.callbridge.database.entities.PairedDeviceEntity
import com.example.callbridge.database.entities.PendingCommandEntity
import com.example.callbridge.database.entities.SmsOutboxEntity
import com.example.callbridge.database.entities.SyncCursorEntity
import com.example.callbridge.database.entities.SyncSessionEntity

/**
 * Local Room operational & synchronization metadata store for BridgeLink.
 * Android system Providers (ContactsContract, Telecom) remain authoritative for raw user records.
 */
@Database(
    entities = [
        PairedDeviceEntity::class,
        SyncCursorEntity::class,
        SyncSessionEntity::class,
        SmsOutboxEntity::class,
        PendingCommandEntity::class,
        ContactSyncMetadataEntity::class,
        CallHistorySyncMetadataEntity::class,
        ObservedCallHistoryEntity::class,
        DeviceSettingsEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BridgeDatabase : RoomDatabase() {

    abstract fun pairedDeviceDao(): PairedDeviceDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun syncSessionDao(): SyncSessionDao
    abstract fun smsOutboxDao(): SmsOutboxDao
    abstract fun pendingCommandDao(): PendingCommandDao
    abstract fun contactSyncMetadataDao(): ContactSyncMetadataDao
    abstract fun callHistoryDao(): CallHistoryDao
    abstract fun deviceSettingsDao(): DeviceSettingsDao

    companion object {
        private const val DB_NAME = "bridgelink_operational.db"

        /**
         * Explicit forward-migration registry so future schema updates never lose operational cursors.
         */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            // Future migrations (e.g., MIGRATION_1_2) register here cleanly.
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    // Placeholder forward schema migration anchor
                }
            }
        )

        @Volatile
        private var instance: BridgeDatabase? = null

        fun getInstance(context: Context): BridgeDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    BridgeDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(*ALL_MIGRATIONS)
                    .build()
                    .also { instance = it }
            }
        }
    }
}

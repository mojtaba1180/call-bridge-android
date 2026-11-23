package com.example.callbridge.contacts

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import com.example.callbridge.core.Logger
import com.example.callbridge.database.dao.ContactSyncMetadataDao
import com.example.callbridge.database.entities.ContactSyncMetadataEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Coordinates authoritative Android ContactsContract access with Room sync metadata,
 * ContentObserver change tracking, SHA-256 fingerprint conflict detection, and tombstones.
 */
class ContactsRepository(
    context: Context,
    private val dataSource: ContactsProviderDataSource,
    private val metadataDao: ContactSyncMetadataDao
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var observer: ContentObserver? = null
    private var onContactsChangedCallback: (() -> Unit)? = null

    private val _status = MutableStateFlow(
        ContactsStatusSnapshot(
            readGranted = dataSource.hasReadPermission(),
            writeGranted = dataSource.hasWritePermission(),
            totalContacts = 0,
            lastSyncedAtMs = null,
            pendingConflicts = 0,
            observerActive = false
        )
    )
    val status: StateFlow<ContactsStatusSnapshot> = _status.asStateFlow()

    fun startObserving(onChanged: () -> Unit) {
        onContactsChangedCallback = onChanged
        if (observer != null || !dataSource.hasReadPermission()) {
            refreshStatus()
            return
        }
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                Logger.i("Contacts", "ContactsContract ContentObserver detected change")
                refreshStatus()
                onContactsChangedCallback?.invoke()
            }
        }
        runCatching {
            appContext.contentResolver.registerContentObserver(
                ContactsContract.Contacts.CONTENT_URI,
                true,
                obs
            )
            observer = obs
            Logger.i("Contacts", "Contacts ContentObserver registered")
        }
        refreshStatus()
    }

    fun stopObserving() {
        observer?.let {
            runCatching { appContext.contentResolver.unregisterContentObserver(it) }
        }
        observer = null
        refreshStatus()
    }

    fun refreshStatus() {
        scope.launch {
            val readOk = dataSource.hasReadPermission()
            val writeOk = dataSource.hasWritePermission()
            val count = if (readOk) dataSource.getContactCount() else 0
            val conflicts = runCatching { metadataDao.getAll().count { it.hasConflict } }.getOrDefault(0)
            _status.value = _status.value.copy(
                readGranted = readOk,
                writeGranted = writeOk,
                totalContacts = count,
                pendingConflicts = conflicts,
                observerActive = observer != null
            )
        }
    }

    suspend fun getContactsPage(limit: Int, offset: Int): List<BridgeContact> {
        return dataSource.getContacts(limit = limit, offset = offset)
    }

    suspend fun getContact(id: String): BridgeContact? = dataSource.getContactById(id)

    suspend fun createContact(contact: BridgeContact): ContactResult {
        val dup = dataSource.findPotentialDuplicateByPhone(contact.phones)
        if (dup != null) {
            return ContactResult.Failure(
                errorCode = "DUPLICATE_PHONE_DETECTED",
                message = "A contact with a matching phone number already exists (id=${dup.contactId})"
            )
        }
        val result = dataSource.createContact(contact)
        if (result is ContactResult.Success && result.contact != null && result.fingerprint != null) {
            metadataDao.upsert(
                ContactSyncMetadataEntity(
                    contactId = result.contact.contactId,
                    lookupKey = result.contact.lookupKey,
                    contentFingerprint = result.fingerprint,
                    version = 1L,
                    lastUpdatedMs = System.currentTimeMillis(),
                    isDeletedTombstone = false,
                    hasConflict = false
                )
            )
            refreshStatus()
        }
        return result
    }

    /**
     * Applies a validated contact update received from iOS, checking expected fingerprint first
     * so concurrent Android edits are never silently overwritten.
     */
    suspend fun updateContactWithConflictCheck(
        contactId: String,
        updated: BridgeContact,
        expectedFingerprint: String?
    ): ContactResult {
        val existing = dataSource.getContactById(contactId)
            ?: return ContactResult.Failure("CONTACT_NOT_FOUND", "Contact $contactId does not exist on Android")

        val actualFingerprint = existing.computeFingerprint()
        if (!expectedFingerprint.isNullOrBlank() && expectedFingerprint != actualFingerprint) {
            metadataDao.upsert(
                ContactSyncMetadataEntity(
                    contactId = contactId,
                    lookupKey = existing.lookupKey,
                    contentFingerprint = actualFingerprint,
                    version = (metadataDao.getById(contactId)?.version ?: 0L) + 1L,
                    lastUpdatedMs = System.currentTimeMillis(),
                    isDeletedTombstone = false,
                    hasConflict = true
                )
            )
            refreshStatus()
            Logger.w("Contacts", "Contact update conflict detected (Android authoritative)")
            return ContactResult.Conflict(
                conflict = ContactConflict(
                    contactId = contactId,
                    expectedFingerprint = expectedFingerprint,
                    actualAndroidFingerprint = actualFingerprint,
                    androidContact = existing
                ),
                message = "Conflict detected: Android contact changed since last sync"
            )
        }

        val res = dataSource.updateContactDisplayNameAndPrimaryPhone(contactId, updated)
        if (res is ContactResult.Success && res.contact != null && res.fingerprint != null) {
            val prevVer = metadataDao.getById(contactId)?.version ?: 0L
            metadataDao.upsert(
                ContactSyncMetadataEntity(
                    contactId = contactId,
                    lookupKey = res.contact.lookupKey,
                    contentFingerprint = res.fingerprint,
                    version = prevVer + 1L,
                    lastUpdatedMs = System.currentTimeMillis(),
                    isDeletedTombstone = false,
                    hasConflict = false
                )
            )
            refreshStatus()
        }
        return res
    }

    /**
     * Requires explicit confirmation flag and records a synchronized tombstone in Room
     * so iOS can cleanly mirror the deletion without silently removing unrelated contacts.
     */
    suspend fun deleteContactConfirmed(contactId: String, userConfirmed: Boolean): ContactResult {
        if (!userConfirmed) {
            return ContactResult.Failure(
                errorCode = "CONFIRMATION_REQUIRED",
                message = "Explicit user confirmation is required before deleting an Android contact"
            )
        }
        val res = dataSource.deleteContact(contactId)
        if (res is ContactResult.Success) {
            val prevVer = metadataDao.getById(contactId)?.version ?: 0L
            metadataDao.upsert(
                ContactSyncMetadataEntity(
                    contactId = contactId,
                    lookupKey = null,
                    contentFingerprint = "",
                    version = prevVer + 1L,
                    lastUpdatedMs = System.currentTimeMillis(),
                    isDeletedTombstone = true,
                    hasConflict = false
                )
            )
            refreshStatus()
        }
        return res
    }

    suspend fun recordSyncedFingerprints(contacts: List<BridgeContact>) {
        val now = System.currentTimeMillis()
        val entities = contacts.map { contact ->
            val prev = metadataDao.getById(contact.contactId)
            ContactSyncMetadataEntity(
                contactId = contact.contactId,
                lookupKey = contact.lookupKey,
                contentFingerprint = contact.computeFingerprint(),
                version = (prev?.version ?: 0L) + 1L,
                lastUpdatedMs = now,
                isDeletedTombstone = false,
                hasConflict = false
            )
        }
        metadataDao.upsertAll(entities)
        _status.value = _status.value.copy(lastSyncedAtMs = now)
    }

    suspend fun getTombstoneIds(): List<String> = metadataDao.getTombstones().map { it.contactId }
}

package com.example.callbridge.contacts

import java.security.MessageDigest

data class ContactPhone(
    val number: String,
    val normalizedNumber: String,
    val label: String
)

data class ContactEmail(
    val address: String,
    val label: String
)

data class BridgeContact(
    val contactId: String,
    val lookupKey: String?,
    val firstName: String?,
    val lastName: String?,
    val displayName: String,
    val phones: List<ContactPhone>,
    val emails: List<ContactEmail>,
    val organization: String?,
    val hasPhoto: Boolean,
    val updatedAtMs: Long
) {
    /**
     * Deterministic SHA-256 content fingerprint used for incremental delta detection
     * and bidirectional conflict checks.
     */
    fun computeFingerprint(): String {
        val canonical = buildString {
            append(displayName.trim()).append('|')
            append(firstName?.trim().orEmpty()).append('|')
            append(lastName?.trim().orEmpty()).append('|')
            append(organization?.trim().orEmpty()).append('|')
            phones.sortedBy { it.normalizedNumber }.forEach {
                append(it.normalizedNumber).append(':').append(it.label).append(',')
            }
            append('|')
            emails.sortedBy { it.address.lowercase() }.forEach {
                append(it.address.lowercase()).append(':').append(it.label).append(',')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

enum class ContactConflictPolicy {
    ANDROID_AUTHORITATIVE,
    REQUIRE_USER_CONFIRMATION
}

data class ContactConflict(
    val contactId: String,
    val expectedFingerprint: String,
    val actualAndroidFingerprint: String,
    val androidContact: BridgeContact
)

sealed class ContactResult {
    data class Success(
        val contact: BridgeContact?,
        val fingerprint: String?,
        val message: String
    ) : ContactResult()

    data class Conflict(
        val conflict: ContactConflict,
        val message: String
    ) : ContactResult()

    data class Failure(
        val errorCode: String,
        val message: String
    ) : ContactResult()
}

data class ContactsStatusSnapshot(
    val readGranted: Boolean,
    val writeGranted: Boolean,
    val totalContacts: Int,
    val lastSyncedAtMs: Long?,
    val pendingConflicts: Int,
    val observerActive: Boolean
)

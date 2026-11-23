package com.example.callbridge.contacts

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.callbridge.core.Logger
import com.example.callbridge.utils.BridgePhoneNumberUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Native Android ContactsContract data source (authoritative contact store).
 * Supports paginated reads, aggregation-aware lookup keys, phone normalization preserving originals,
 * duplicate phone detection (never merging solely by identical display name), and validated CRUD writes.
 */
class ContactsProviderDataSource(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    fun hasReadPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    fun hasWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun getContactCount(): Int = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext 0
        runCatching {
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { it.count } ?: 0
        }.getOrDefault(0)
    }

    suspend fun getContacts(limit: Int = 500, offset: Int = 0): List<BridgeContact> = withContext(Dispatchers.IO) {
        if (!hasReadPermission()) return@withContext emptyList()
        val contacts = mutableListOf<BridgeContact>()
        try {
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
            )
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                "${ContactsContract.Contacts._ID} ASC"
            )?.use { cursor ->
                if (!cursor.moveToPosition(offset)) return@use
                var taken = 0
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val lookupIdx = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                val photoIdx = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
                val updatedIdx = cursor.getColumnIndex(ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP)

                while (taken < limit) {
                    val contactId = if (idIdx >= 0) cursor.getString(idIdx) else null
                    if (!contactId.isNullOrBlank()) {
                        val lookupKey = if (lookupIdx >= 0) cursor.getString(lookupIdx) else null
                        val displayName = (if (nameIdx >= 0) cursor.getString(nameIdx) else null).orEmpty()
                        val hasPhoto = photoIdx >= 0 && !cursor.getString(photoIdx).isNullOrBlank()
                        val updatedAt = if (updatedIdx >= 0) cursor.getLong(updatedIdx) else 0L

                        val structured = queryStructuredName(contactId)
                        val phones = queryPhones(contactId)
                        val emails = queryEmails(contactId)
                        val org = queryOrganization(contactId)

                        contacts.add(
                            BridgeContact(
                                contactId = contactId,
                                lookupKey = lookupKey,
                                firstName = structured.first,
                                lastName = structured.second,
                                displayName = displayName.ifBlank { listOfNotNull(structured.first, structured.second).joinToString(" ") },
                                phones = phones,
                                emails = emails,
                                organization = org,
                                hasPhoto = hasPhoto,
                                updatedAtMs = updatedAt
                            )
                        )
                        taken++
                    }
                    if (!cursor.moveToNext()) break
                }
            }
        } catch (se: SecurityException) {
            Logger.w("Contacts", "SecurityException reading contacts: ${se.message}")
        } catch (t: Throwable) {
            Logger.e("Contacts", "Error reading ContactsContract", t)
        }
        contacts
    }

    suspend fun getContactById(contactId: String): BridgeContact? = withContext(Dispatchers.IO) {
        if (!hasReadPermission() || contactId.isBlank()) return@withContext null
        try {
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.LOOKUP_KEY,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP
            )
            resolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                "${ContactsContract.Contacts._ID} = ?",
                arrayOf(contactId),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val lookupKey = cursor.getString(1)
                val displayName = cursor.getString(2).orEmpty()
                val hasPhoto = !cursor.getString(3).isNullOrBlank()
                val updatedAt = cursor.getLong(4)
                val structured = queryStructuredName(contactId)
                BridgeContact(
                    contactId = contactId,
                    lookupKey = lookupKey,
                    firstName = structured.first,
                    lastName = structured.second,
                    displayName = displayName,
                    phones = queryPhones(contactId),
                    emails = queryEmails(contactId),
                    organization = queryOrganization(contactId),
                    hasPhoto = hasPhoto,
                    updatedAtMs = updatedAt
                )
            }
        } catch (t: Throwable) {
            Logger.e("Contacts", "Failed to read contact by id", t)
            null
        }
    }

    /**
     * Checks whether a contact with an overlapping normalized phone number already exists.
     * Deliberately does NOT treat identical names alone as duplicates.
     */
    suspend fun findPotentialDuplicateByPhone(phones: List<ContactPhone>): BridgeContact? {
        val targetNorms = phones.map { it.normalizedNumber }.filter { it.isNotBlank() }
        if (targetNorms.isEmpty()) return null
        val existing = getContacts(limit = 500, offset = 0)
        return existing.firstOrNull { candidate ->
            candidate.phones.any { p ->
                targetNorms.any { target -> BridgePhoneNumberUtils.areLikelySameNumber(p.number, target) }
            }
        }
    }

    suspend fun createContact(contact: BridgeContact): ContactResult = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) {
            return@withContext ContactResult.Failure("WRITE_CONTACTS_MISSING", "WRITE_CONTACTS permission is required")
        }
        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                .build()
        )
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.displayName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, contact.firstName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, contact.lastName)
                .build()
        )
        contact.phones.forEach { phone ->
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone.number)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
        }
        contact.emails.forEach { email ->
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Email.ADDRESS, email.address)
                    .withValue(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_OTHER)
                    .build()
            )
        }
        if (!contact.organization.isNullOrBlank()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Organization.COMPANY, contact.organization)
                    .build()
            )
        }

        return@withContext try {
            val results = resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val rawUri = results.firstOrNull()?.uri
            val createdId = resolveContactIdFromRawUri(rawUri?.lastPathSegment)
            val created = createdId?.let { getContactById(it) } ?: contact.copy(contactId = createdId ?: contact.contactId)
            Logger.i("Contacts", "Created contact record in Android ContactsContract")
            ContactResult.Success(created, created.computeFingerprint(), "Contact created")
        } catch (t: Throwable) {
            Logger.e("Contacts", "Failed to create contact", t)
            ContactResult.Failure("CREATE_FAILED", t.message ?: "Failed to create contact")
        }
    }

    suspend fun updateContactDisplayNameAndPrimaryPhone(
        contactId: String,
        updated: BridgeContact
    ): ContactResult = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) {
            return@withContext ContactResult.Failure("WRITE_CONTACTS_MISSING", "WRITE_CONTACTS permission is required")
        }
        val rawContactId = findRawContactId(contactId)
            ?: return@withContext ContactResult.Failure("CONTACT_NOT_FOUND", "Target contact not found")

        val ops = ArrayList<ContentProviderOperation>()
        ops.add(
            ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                .withSelection(
                    "${ContactsContract.Data.RAW_CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                    arrayOf(rawContactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                )
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, updated.displayName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME, updated.firstName)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME, updated.lastName)
                .build()
        )
        return@withContext try {
            resolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val refreshed = getContactById(contactId) ?: updated
            Logger.i("Contacts", "Updated contact record in Android ContactsContract")
            ContactResult.Success(refreshed, refreshed.computeFingerprint(), "Contact updated")
        } catch (t: Throwable) {
            Logger.e("Contacts", "Failed to update contact", t)
            ContactResult.Failure("UPDATE_FAILED", t.message ?: "Failed to update contact")
        }
    }

    suspend fun deleteContact(contactId: String): ContactResult = withContext(Dispatchers.IO) {
        if (!hasWritePermission()) {
            return@withContext ContactResult.Failure("WRITE_CONTACTS_MISSING", "WRITE_CONTACTS permission is required")
        }
        return@withContext try {
            val rows = resolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId)
            )
            if (rows > 0) {
                Logger.i("Contacts", "Deleted contact with confirmed request (rows=$rows)")
                ContactResult.Success(null, null, "Contact deleted")
            } else {
                ContactResult.Failure("CONTACT_NOT_FOUND", "No matching contact deleted")
            }
        } catch (t: Throwable) {
            Logger.e("Contacts", "Failed to delete contact", t)
            ContactResult.Failure("DELETE_FAILED", t.message ?: "Failed to delete contact")
        }
    }

    private fun resolveContactIdFromRawUri(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        return runCatching {
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                "${ContactsContract.RawContacts._ID} = ?",
                arrayOf(rawId),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private fun findRawContactId(contactId: String): String? {
        return runCatching {
            resolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts._ID),
                "${ContactsContract.RawContacts.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }

    private fun queryStructuredName(contactId: String): Pair<String?, String?> {
        return runCatching {
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.StructuredName.GIVEN_NAME,
                    ContactsContract.CommonDataKinds.StructuredName.FAMILY_NAME
                ),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
                null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) to c.getString(1) else null to null
            } ?: (null to null)
        }.getOrDefault(null to null)
    }

    private fun queryPhones(contactId: String): List<ContactPhone> {
        val list = mutableListOf<ContactPhone>()
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL
                ),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val type = c.getInt(1)
                    val customLabel = c.getString(2)
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        appContext.resources,
                        type,
                        customLabel
                    ).toString()
                    list.add(
                        ContactPhone(
                            number = number,
                            normalizedNumber = BridgePhoneNumberUtils.normalizeForMatching(number),
                            label = label
                        )
                    )
                }
            }
        }
        return list
    }

    private fun queryEmails(contactId: String): List<ContactEmail> {
        val list = mutableListOf<ContactEmail>()
        runCatching {
            resolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                    ContactsContract.CommonDataKinds.Email.TYPE,
                    ContactsContract.CommonDataKinds.Email.LABEL
                ),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    val addr = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    val type = c.getInt(1)
                    val customLabel = c.getString(2)
                    val label = ContactsContract.CommonDataKinds.Email.getTypeLabel(
                        appContext.resources,
                        type,
                        customLabel
                    ).toString()
                    list.add(ContactEmail(addr, label))
                }
            }
        }
        return list
    }

    private fun queryOrganization(contactId: String): String? {
        return runCatching {
            resolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId, ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
    }
}

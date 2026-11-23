package com.example.callbridge

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.callbridge.callhistory.CallHistoryRepository
import com.example.callbridge.callhistory.ObservedCallType
import com.example.callbridge.contacts.BridgeContact
import com.example.callbridge.contacts.ContactPhone
import com.example.callbridge.database.BridgeDatabase
import com.example.callbridge.database.entities.SmsOutboxEntity
import com.example.callbridge.database.entities.SyncCursorEntity
import com.example.callbridge.network.LocalIpResolver
import com.example.callbridge.network.NetworkTransportType
import com.example.callbridge.network.ResolvedLocalAddress
import com.example.callbridge.utils.BridgePhoneNumberUtils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactsSyncAndDatabaseTest {

    private lateinit var db: BridgeDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BridgeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun phoneNumberNormalizationAndContactFingerprintDetectEdits() {
        assertEquals("+989123456789", BridgePhoneNumberUtils.normalizeForMatching("+98 (912) 345-6789"))
        assertTrue(BridgePhoneNumberUtils.areLikelySameNumber("+989123456789", "09123456789"))

        val c1 = BridgeContact(
            contactId = "10",
            lookupKey = "k10",
            firstName = "Sara",
            lastName = "Ahmadi",
            displayName = "Sara Ahmadi",
            phones = listOf(ContactPhone("+989123456789", "+989123456789", "MOBILE")),
            emails = emptyList(),
            organization = "BridgeLink",
            hasPhoto = false,
            updatedAtMs = 1000L
        )
        val c2 = c1.copy(displayName = "Sara A. Updated")

        assertEquals(64, c1.computeFingerprint().length)
        assertNotEquals(c1.computeFingerprint(), c2.computeFingerprint())
    }

    @Test
    fun roomDatabase_enforcesSmsOutboxIdempotencyAndCallHistoryPaginationWithTombstones() = runBlocking {
        // 1. SMS Outbox idempotency
        val outboxDao = db.smsOutboxDao()
        val first = SmsOutboxEntity(
            outboxId = "sms_1",
            idempotencyKey = "idem-sms-1",
            destinationMasked = "***6789",
            subscriptionId = 1,
            state = "HANDOFF_REQUIRED",
            errorMessage = null,
            createdAtMs = 1000L,
            updatedAtMs = 1000L
        )
        val duplicate = first.copy(outboxId = "sms_2")
        outboxDao.insertIgnore(first)
        outboxDao.insertIgnore(duplicate)
        assertEquals(1, outboxDao.countAll())
        assertEquals("sms_1", outboxDao.findByIdempotencyKey("idem-sms-1")?.outboxId)

        // 2. Sync cursor persistence
        val cursorDao = db.syncCursorDao()
        cursorDao.upsert(
            SyncCursorEntity(
                entityType = "CONTACTS",
                lastSyncedTimestampMs = 5000L,
                lastSyncedRecordId = "c_150",
                lastPageNumber = 2,
                totalSyncedCount = 150,
                updatedAtMs = 5000L
            )
        )
        val loadedCursor = cursorDao.getCursor("CONTACTS")
        assertNotNull(loadedCursor)
        assertEquals(2, loadedCursor!!.lastPageNumber)

        // 3. Observed Call History pagination & tombstone
        val repo = CallHistoryRepository(db.callHistoryDao())
        for (i in 1..5) {
            repo.recordObservedCall(
                callId = "call_$i",
                rawPhoneNumber = "+1555010000$i",
                displayName = "Caller $i",
                callType = ObservedCallType.INCOMING,
                startedAtMs = 10_000L + i * 1000L,
                durationSec = 30L * i,
                subscriptionId = 1
            )
        }
        assertEquals(5, repo.countActive())
        val page1 = repo.getPage(limit = 2, offset = 0)
        assertEquals(2, page1.size)
        assertEquals("call_5", page1[0].callId)

        repo.deleteWithTombstone("call_5")
        assertEquals(4, repo.countActive())
        assertTrue(repo.getTombstoneIds().contains("call_5"))
    }

    @Test
    fun localIpResolver_prioritizesHotspotAndWifiOverCellularAndVpn() {
        val candidates = listOf(
            ResolvedLocalAddress("10.0.0.5", "rmnet_data0", NetworkTransportType.OTHER_LOCAL),
            ResolvedLocalAddress("10.8.0.2", "tun0", NetworkTransportType.OTHER_LOCAL),
            ResolvedLocalAddress("192.168.1.44", "wlan0", NetworkTransportType.WIFI),
            ResolvedLocalAddress("192.168.43.1", "ap0", NetworkTransportType.HOTSPOT)
        )
        val best = LocalIpResolver.selectBestCandidate(candidates)
        assertNotNull(best)
        assertEquals("ap0", best!!.interfaceName)
        assertEquals("192.168.43.1", best.hostIp)
    }
}

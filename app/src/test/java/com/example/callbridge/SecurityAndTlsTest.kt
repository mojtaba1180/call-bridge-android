package com.example.callbridge

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.callbridge.network.WebSocketServer
import com.example.callbridge.security.CertificatePinningManager
import com.example.callbridge.security.KeyStoreManager
import com.example.callbridge.security.PairingManager
import com.example.callbridge.security.PairingQrPayload
import com.example.callbridge.security.SessionAuthenticator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SecurityAndTlsTest {

    @Test
    fun selfSignedX509Certificate_generatesValidDerAndPinningMatches() {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val keyPair = kpg.generateKeyPair()
        val cert = KeyStoreManager.generateSelfSignedX509Cert(keyPair, "BridgeLink Test")

        cert.checkValidity()
        cert.verify(keyPair.public)

        val fingerprint = KeyStoreManager.computeSha256Fingerprint(cert)
        assertEquals(95, fingerprint.length) // 32 bytes * 2 hex chars + 31 colons
        assertTrue(CertificatePinningManager.verifyCertificatePin(cert, fingerprint))
        assertFalse(CertificatePinningManager.verifyCertificatePin(cert, "00:11:22:33"))
    }

    @Test
    fun pairingQrPayload_andExplicitApprovalFlow_enforcesOneTimeTokenAndPin() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ksManager = KeyStoreManager(context)
        val pairingManager = PairingManager(context, ksManager)
        pairingManager.unpair()

        val qr = pairingManager.openPairingWindow(currentLocalIp = "192.168.1.50")
        val json = qr.toJsonString()
        val parsed = PairingQrPayload.fromJsonString(json)
        assertNotNull(parsed)
        assertEquals("192.168.1.50", parsed!!.wssHost)
        assertEquals(8443, parsed.wssPort)
        assertEquals(ksManager.getServerCertificateFingerprint(), parsed.certSha256)

        // Wrong token must be rejected
        val rejected = pairingManager.registerIncomingPairRequest(
            deviceId = "iphone-1",
            deviceName = "iPhone 16 Pro",
            bleAddress = "11:22:33:44:55:66",
            suppliedToken = "wrong-token"
        )
        assertNull(rejected)

        // Valid token enters pending user approval (NOT automatically trusted)
        val pending = pairingManager.registerIncomingPairRequest(
            deviceId = "iphone-1",
            deviceName = "iPhone 16 Pro",
            bleAddress = "11:22:33:44:55:66",
            suppliedToken = qr.token
        )
        assertNotNull(pending)
        assertFalse(pairingManager.isDevicePaired("iphone-1"))

        // Explicit user approval persists pairing
        val approved = pairingManager.approvePendingPairing()
        assertNotNull(approved)
        assertTrue(pairingManager.isDevicePaired("iphone-1"))
    }

    @Test
    fun sessionAuthenticator_enforcesExpirationReplayWindowIdempotencyAndCooldown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pairingManager = PairingManager(context)
        pairingManager.unpair()
        val auth = SessionAuthenticator(pairingManager)

        // Unpaired device fails repeatedly and triggers brute-force cooldown
        val baseTime = 1_700_000_000_000L
        repeat(5) {
            assertNull(auth.authenticatePairedDevice("unpaired-id", null, nowMs = baseTime))
        }
        assertTrue(auth.isInCooldown(nowMs = baseTime + 1_000L))
        assertFalse(auth.isInCooldown(nowMs = baseTime + 35_000L))

        // Pair device explicitly
        val qr = pairingManager.openPairingWindow()
        pairingManager.registerIncomingPairRequest("iphone-trusted", "iPhone", null, qr.token)
        pairingManager.approvePendingPairing()

        val session = auth.authenticatePairedDevice("iphone-trusted", null, nowMs = baseTime + 40_000L)
        assertNotNull(session)
        assertTrue(auth.isAuthorized(session!!.sessionToken, nowMs = baseTime + 50_000L))

        // Expired session must fail authorization
        assertFalse(auth.isAuthorized(session.sessionToken, nowMs = session.expiresAtMs + 1_000L))

        // Replay timestamp window (+/- 120s)
        assertTrue(auth.isTimestampFresh(baseTime, nowMs = baseTime + 60_000L))
        assertFalse(auth.isTimestampFresh(baseTime, nowMs = baseTime + 200_000L))

        // Idempotency key claim
        assertTrue(auth.claimIdempotencyKey("idem-1"))
        assertFalse(auth.claimIdempotencyKey("idem-1"))
    }

    @Test
    fun webSocketAcceptComputation_matchesRfc6455TestVector() {
        // Standard RFC 6455 Section 4.2.2 test vector
        val accept = WebSocketServer.computeWebSocketAccept("dGhlIHNhbXBsZSBub25jZQ==")
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept)
    }
}

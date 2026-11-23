package com.example.callbridge

import com.example.callbridge.protocol.BleFrameReassembler
import com.example.callbridge.protocol.BlePacketFramer
import com.example.callbridge.protocol.CallStateMessage
import com.example.callbridge.protocol.CommandMessage
import com.example.callbridge.protocol.CommandValidationResult
import com.example.callbridge.protocol.EndpointPayload
import com.example.callbridge.protocol.MessageSerializer
import com.example.callbridge.protocol.ProtocolConstants
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProtocolAndFramingTest {

    @Test
    fun ble8ByteFraming_fragmentsAndReassemblesAcrossSmallMtu() {
        val payloadText = """{"version":1,"id":"cmd_42","cmd":"SYNC_START","entity":"CONTACTS","pageSize":150,"sessionToken":"abc1234567890"}"""
        val payloadBytes = payloadText.toByteArray(Charsets.UTF_8)

        val frames = BlePacketFramer.fragment(payloadBytes, mtu = 23, explicitMessageId = 1001)
        assertTrue("Expected multiple chunks for MTU 23", frames.size > 3)

        // Verify 8-byte header magic & version on every chunk
        frames.forEachIndexed { idx, frame ->
            assertEquals(ProtocolConstants.FRAME_MAGIC, frame[0])
            assertEquals(ProtocolConstants.VERSION.toByte(), frame[1])
            assertEquals(idx, frame[4].toInt() and 0xFF)
            assertEquals(frames.size, frame[5].toInt() and 0xFF)
        }

        val reassembler = BleFrameReassembler()
        var reassembled: ByteArray? = null
        // Deliver slightly out-of-order to verify index-based reassembly
        for (frame in frames.reversed()) {
            val out = reassembler.onPacketReceived("AA:BB:CC:DD:EE:FF", frame)
            if (out != null) reassembled = out
        }

        assertNotNull(reassembled)
        assertArrayEquals(payloadBytes, reassembled)

        // Re-sending the exact same messageId must be dropped idempotently
        var duplicateResult: ByteArray? = null
        for (frame in frames) {
            val out = reassembler.onPacketReceived("AA:BB:CC:DD:EE:FF", frame)
            if (out != null) duplicateResult = out
        }
        assertNull("Duplicate messageId should be suppressed", duplicateResult)
    }

    @Test
    fun messageSerializer_roundTripsCallStateAndWssEndpoint() {
        val original = CallStateMessage(
            state = "RINGING",
            callId = "call_1234",
            number = "+15550109999",
            displayName = "Alex",
            direction = "INCOMING",
            subscriptionId = 2,
            startTime = 1795350000000L,
            endpoint = EndpointPayload(
                host = "192.168.43.1",
                port = 8443,
                path = "/bridge/v1",
                scheme = "wss",
                certSha256 = "AA:BB:CC",
                devAudioUdpPort = 8082
            ),
            audioCapability = "CELLULAR_AUDIO_UNAVAILABLE",
            callControlSupported = false,
            timestamp = 1795350001000L
        )
        val json = MessageSerializer.serializeCallState(original)
        val decoded = MessageSerializer.deserializeCallState(json)

        assertNotNull(decoded)
        assertEquals(original.state, decoded!!.state)
        assertEquals(original.subscriptionId, decoded.subscriptionId)
        assertEquals("wss", decoded.endpoint?.scheme)
        assertEquals(8443, decoded.endpoint?.port)
        assertEquals("/bridge/v1", decoded.endpoint?.path)
        assertEquals("AA:BB:CC", decoded.endpoint?.certSha256)
    }

    @Test
    fun commandValidation_acceptsValidBridgeLinkEnvelopeAndRejectsInvalidSms() {
        val validSmsJson = """
            {
              "version": 1,
              "id": "cmd_sms_1",
              "cmd": "SMS_SEND",
              "idempotencyKey": "idem-99",
              "sessionToken": "tok_abc",
              "payload": {
                "destination": "+15550109999",
                "body": "Hello from iPhone",
                "subscriptionId": 1
              }
            }
        """.trimIndent()

        val validResult = MessageSerializer.parseAndValidateCommand(validSmsJson)
        assertTrue(validResult is CommandValidationResult.Valid)
        val cmd = (validResult as CommandValidationResult.Valid).command
        assertEquals(ProtocolConstants.CMD_SMS_SEND, cmd.cmd)
        assertEquals("+15550109999", cmd.number)
        assertEquals("Hello from iPhone", cmd.smsBody)
        assertEquals(1, cmd.subscriptionId)
        assertEquals("idem-99", cmd.idempotencyKey)

        // Empty SMS body must fail validation
        val invalidSmsJson = """{"version":1,"id":"cmd_sms_2","cmd":"SMS_SEND","number":"+15550109999","body":"   "}"""
        val invalidResult = MessageSerializer.parseAndValidateCommand(invalidSmsJson)
        assertTrue(invalidResult is CommandValidationResult.Invalid)
        assertEquals("EMPTY_SMS_BODY", (invalidResult as CommandValidationResult.Invalid).errorCode)
    }

    @Test
    fun commandValidation_rejectsMalformedDialNumberAndUnsupportedVersion() {
        val badDial = MessageSerializer.serializeCommand(
            CommandMessage(version = 1, id = "c1", cmd = ProtocolConstants.CMD_CALL_DIAL, number = "not-a-phone")
        )
        val res1 = MessageSerializer.parseAndValidateCommand(badDial)
        assertTrue(res1 is CommandValidationResult.Invalid)
        assertEquals("INVALID_PHONE_NUMBER", (res1 as CommandValidationResult.Invalid).errorCode)

        val badVer = """{"version":99,"id":"c2","cmd":"PING"}"""
        val res2 = MessageSerializer.parseAndValidateCommand(badVer)
        assertTrue(res2 is CommandValidationResult.Invalid)
        assertEquals("UNSUPPORTED_VERSION", (res2 as CommandValidationResult.Invalid).errorCode)
    }
}

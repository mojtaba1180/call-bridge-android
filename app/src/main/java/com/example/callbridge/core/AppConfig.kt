package com.example.callbridge.core

/**
 * Central application configuration constants.
 * Keeps network ports, protocol versions, timeouts, and audio test parameters consistent across modules.
 */
object AppConfig {
    const val APP_TAG = "CallBridge"
    const val PROTOCOL_VERSION = 1

    // Local network server ports (control and dev audio transport separated by design)
    const val DEFAULT_CONTROL_PORT = 8080
    const val DEFAULT_DEV_AUDIO_UDP_PORT = 8082

    // BLE framing and MTU constraints
    const val BLE_DEFAULT_MTU = 23
    const val BLE_PREFERRED_MTU = 247
    const val BLE_ATT_HEADER_OVERHEAD = 3
    const val BLE_MAX_REASSEMBLY_BYTES = 16 * 1024

    // Security & Pairing
    const val PAIRING_CODE_LENGTH = 6
    const val PAIRING_REQUEST_TIMEOUT_MS = 60_000L
    const val MAX_AUTH_FAILURES_BEFORE_COOLDOWN = 5
    const val AUTH_COOLDOWN_MS = 30_000L
    const val PREFS_NAME = "callbridge_prefs"

    // Reconnection exponential backoff
    const val INITIAL_BACKOFF_MS = 1_500L
    const val MAX_BACKOFF_MS = 30_000L

    // WakeLock safety timeout (never held indefinitely)
    const val WAKELOCK_MAX_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes max safety cap
    const val WAKELOCK_TEMP_OPERATION_MS = 30_000L      // 30 seconds for short operations

    // Target PCM audio test format (16 kHz, 16-bit PCM, Mono)
    const val AUDIO_SAMPLE_RATE_HZ = 16_000
    const val AUDIO_FRAME_DURATION_MS = 20
    const val AUDIO_SAMPLES_PER_FRAME = (AUDIO_SAMPLE_RATE_HZ * AUDIO_FRAME_DURATION_MS) / 1000 // 320 samples
    const val AUDIO_BYTES_PER_SAMPLE = 2
    const val AUDIO_FRAME_BYTES = AUDIO_SAMPLES_PER_FRAME * AUDIO_BYTES_PER_SAMPLE // 640 bytes

    // In-memory live event log ring buffer limit
    const val MAX_EVENT_LOG_ENTRIES = 200
}

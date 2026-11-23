package com.example.callbridge.core

/**
 * Central BridgeLink configuration constants.
 * Keeps BLE, TLS WebSocket, NSD, protocol, sync pagination, security, WakeLock, and audio settings unified.
 */
object AppConfig {
    const val APP_TAG = "BridgeLink"
    const val PROTOCOL_VERSION = 1

    // Local network server ports (TLS WebSocket control/sync separated from dev audio UDP)
    const val DEFAULT_WSS_PORT = 8443
    const val DEFAULT_WSS_PATH = "/bridge/v1"
    const val DEFAULT_DEV_AUDIO_UDP_PORT = 8082
    const val NSD_SERVICE_TYPE = "_bridgelink._tcp."
    const val NSD_SERVICE_NAME_PREFIX = "BridgeLink"

    // BLE framing and MTU constraints
    const val BLE_DEFAULT_MTU = 23
    const val BLE_PREFERRED_MTU = 247
    const val BLE_ATT_HEADER_OVERHEAD = 3
    const val BLE_MAX_REASSEMBLY_BYTES = 16 * 1024

    // Security & Pairing
    const val PAIRING_CODE_LENGTH = 6
    const val PAIRING_QR_TTL_MS = 120_000L
    const val REPLAY_TIMESTAMP_WINDOW_MS = 120_000L
    const val SESSION_TTL_MS = 12 * 60 * 60 * 1000L // 12 hours
    const val MAX_AUTH_FAILURES_BEFORE_COOLDOWN = 5
    const val AUTH_COOLDOWN_MS = 30_000L
    const val PREFS_NAME = "bridgelink_prefs"

    // Synchronization pagination
    const val DEFAULT_SYNC_PAGE_SIZE = 150
    const val MAX_SYNC_PAGE_SIZE = 250

    // Reconnection exponential backoff
    const val INITIAL_BACKOFF_MS = 1_500L
    const val MAX_BACKOFF_MS = 30_000L

    // WakeLock safety timeout (never held indefinitely)
    const val WAKELOCK_MAX_TIMEOUT_MS = 10 * 60 * 1000L
    const val WAKELOCK_TEMP_OPERATION_MS = 30_000L

    // Target PCM audio test format (16 kHz, 16-bit PCM, Mono)
    const val AUDIO_SAMPLE_RATE_HZ = 16_000
    const val AUDIO_FRAME_DURATION_MS = 20
    const val AUDIO_SAMPLES_PER_FRAME = (AUDIO_SAMPLE_RATE_HZ * AUDIO_FRAME_DURATION_MS) / 1000
    const val AUDIO_BYTES_PER_SAMPLE = 2
    const val AUDIO_FRAME_BYTES = AUDIO_SAMPLES_PER_FRAME * AUDIO_BYTES_PER_SAMPLE

    // In-memory live event log ring buffer limit
    const val MAX_EVENT_LOG_ENTRIES = 250
}

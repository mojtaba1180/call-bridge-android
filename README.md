# BridgeLink — Android SIM Companion for iPhone

**BridgeLink** is a native Kotlin + Android XML (ViewBinding) companion application designed to run on an Android smartphone (`minSdk = 21`, `targetSdk = 36`) and bridge calls, contacts, local sync state, and Play Creator-compliant SMS handoff to a future native iOS companion app over local **BLE GATT** and **TLS WebSocket (`wss://`)**.

---

## 1. Architecture Overview

| Layer | Package | Responsibilities |
|---|---|---|
| **Core & Persistence** | `core/`, `database/` | Manual constructor DI (`AppContainer`), sanitized ring-buffer `Logger`, Room `BridgeDatabase` (9 tables: paired devices, sync cursors, sync sessions, SMS outbox, pending commands, contact metadata/tombstones, observed call history). |
| **Security & Pairing** | `security/` | Pure-JCA RSA-2048 self-signed X.509v3 certificate (`KeyStoreManager`) with Android Keystore AES-GCM key protection, SHA-256 DER pinning (`CertificatePinningManager`), ZXing QR pairing payload (`PairingQrGenerator`, 120s TTL), explicit user approval (`PairingManager`), and session TTL + replay window + idempotency + brute-force cooldown (`SessionAuthenticator`). |
| **BLE Peripheral** | `bluetooth/` | 4-characteristic GATT Server (`DEVICE_INFO`, `EVENT_STREAM`, `COMMAND`, `PAIRING`) with 8-byte MTU-aware framing (`BlePacketFramer` / `BleFrameReassembler`), sliding-window duplicate suppression, and `isMultipleAdvertisementSupported` checks. |
| **Local TLS Network** | `network/` | Zero-dependency RFC 6455 TLS WebSocket server (`wss://<LOCAL_IP>:8443/bridge/v1`), dynamic Wi-Fi/Hotspot IP resolution (`LocalIpResolver`), and mDNS/Bonjour service advertisement (`_bridgelink._tcp.` via `NsdManager`). |
| **Telephony & SIM** | `telephony/`, `device/` | 9-state Telecom lifecycle (`CallManager`, `BridgeInCallService`, `CallStateListener`, `DefaultDialerManager`) and dual-SIM subscription awareness (`SimManager`). |
| **Contacts, SMS & Sync** | `contacts/`, `sms/`, `callhistory/`, `sync/` | Authoritative Android `ContactsContract` CRUD + `ContentObserver` + SHA-256 conflict detection + tombstones; Play Creator-compliant SMS outbox + zero-permission `ACTION_SENDTO` handoff; BridgeLink-observed call history in Room; paginated `SyncEngine` (`DEFAULT_SYNC_PAGE_SIZE = 150`). |
| **Isolated Audio** | `audio/` | Honest capability checker (`CELLULAR_AUDIO_UNAVAILABLE`), local 16 kHz PCM Mono mic/loopback test (`LocalAudioBridge`), and documented `PrivilegedCellularAudioExtension` SPI. |
| **UI (Native XML)** | `ui/` | 6-screen Material 3 ViewBinding interface (Dashboard, Messages, Contacts, Calls & Audio, Pairing, Diagnostics), Persian RTL default with live English toggle. |

---

## 2. Google Play Creator Program Compliance & Feasibility Matrix

| Capability | Status | Implementation Detail |
|---|---|---|
| **BLE Peripheral & 4-Char GATT** | Supported (hardware dependent) | Uses `BLUETOOTH_ADVERTISE` / `BLUETOOTH_CONNECT` on Android 12+ (no Location or SCAN requested). |
| **Local TLS WebSocket (`wss://`) + NSD** | Fully Supported | Self-signed X.509v3 cert stored on device; SHA-256 fingerprint pinned in QR code; binds strictly to local private LAN/Hotspot IP. |
| **Contacts Two-Way Sync** | Fully Supported | Uses `READ_CONTACTS` / `WRITE_CONTACTS`; Android authoritative with SHA-256 fingerprints and tombstones. |
| **Telecom Call Monitoring & Control** | Supported (Role Dependent) | Call state monitoring via `READ_PHONE_STATE`; Answer/Reject/Hangup require user-granted Default Dialer (`ROLE_DIALER` / `InCallService`). |
| **SMS Companion** | Play Creator Compliant | **Does NOT declare** prohibited `READ_SMS`, `RECEIVE_SMS`, or `SEND_SMS`. Uses idempotent Room outbox + zero-permission `ACTION_SENDTO` (`smsto:`) system handoff. |
| **Call History Sync** | Play Creator Compliant | **Does NOT declare** prohibited `READ_CALL_LOG`. Persists calls observed directly by BridgeLink into Room and syncs paginated records to iPhone. |
| **Direct Cellular Call Audio Capture / Injection** | **Unavailable on Standard Android** | Standard third-party apps cannot capture `VOICE_CALL` or inject PCM into cellular uplink. Reported transparently as `CELLULAR_AUDIO_UNAVAILABLE`; isolated 16 kHz PCM Mono mic testing is clearly separated. |

---

## 3. Protocol Documentation

See [`BRIDGELINK_PROTOCOL.md`](./BRIDGELINK_PROTOCOL.md) for the complete BLE GATT UUID table, 8-byte binary framing header, QR pairing JSON schema, and WebSocket command envelope contract.

---

## 4. Building & Testing

```bash
# Compile Debug APK
gradle :app:assembleDebug

# Run JVM & Robolectric Unit Test Suite
gradle :app:testDebugUnitTest
```

### Real-Device Verification Checklist (Android 5–16 / API 21–36)
1. **Android 5–6 (API 21–23)**: Verify legacy Bluetooth setup (no runtime BLE prompt), notification channel fallback, and `READ_PHONE_STATE` monitoring.
2. **Android 10–11 (API 29–30)**: Verify `RoleManager` `ROLE_DIALER` request and `BridgeInCallService` binding.
3. **Android 12–13 (API 31–33)**: Verify `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`, and `TelephonyCallback`.
4. **Android 14–16 (API 34–36)**: Verify conditional Foreground Service types (`connectedDevice`, `dataSync`, `microphone`, `phoneCall`) activate only when prerequisites are satisfied.
5. **Diagnostics**: Run **Test BLE**, **Test TLS WSS** (certificate-pinned loopback RFC 6455 handshake + PING/PONG), and **Test Mic** from the Diagnostics screen.

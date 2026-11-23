# BRIDGELINK_PROTOCOL.md

**Version**: `1`  
**Scope**: Shared wire contract between the **BridgeLink Android Companion** (BLE Peripheral + Local TLS WebSocket Server) and the future **BridgeLink iOS Companion** (CoreBluetooth Central + CallKit + `URLSessionWebSocketTask`).

---

## 1. Transport Separation

| Plane | Transport | Purpose |
|---|---|---|
| Discovery, Pairing & Low-Bandwidth Signaling | BLE GATT Peripheral | Device discovery, pairing handshake, live call events, network endpoint announcement, small command results |
| High-Bandwidth Sync & Control | Local TLS WebSocket (`wss://<LOCAL_IP>:8443/bridge/v1`) advertised via mDNS/NSD (`_bridgelink._tcp`) | Paginated Contacts sync, Observed Call History sync, SMS outbox dispatch, large JSON payloads, acknowledgments |
| Isolated Audio Prototype (Dev/Test Only) | Local UDP PCM (port `8082`) or future WebRTC/DTLS-SRTP | Microphone/speaker test loop only — **NOT** direct cellular call audio |

---

## 2. BLE GATT Service & Characteristics

All UUIDs are immutable across releases:

| Role | UUID | Properties |
|---|---|---|
| **Primary Service (`BRIDGELINK_SERVICE`)** | `6E400001-C352-11E5-953D-0002A5D5C51B` | Primary |
| **`DEVICE_INFO`** | `6E400002-C352-11E5-953D-0002A5D5C51B` | `READ` |
| **`EVENT_STREAM`** | `6E400003-C352-11E5-953D-0002A5D5C51B` | `READ`, `NOTIFY` + CCCD (`0x2902`) |
| **`COMMAND`** | `6E400004-C352-11E5-953D-0002A5D5C51B` | `WRITE`, `WRITE_NO_RESPONSE` |
| **`PAIRING`** | `6E400005-C352-11E5-953D-0002A5D5C51B` | `READ`, `WRITE`, `NOTIFY` + CCCD (`0x2902`) |

### 2.1 BLE Framed Transport Header (8 Bytes)

Every multi-MTU payload sent over `EVENT_STREAM`, `COMMAND`, or `PAIRING` uses a fixed 8-byte binary header:

| Offset | Size | Field | Description |
|---|---|---|---|
| `0` | 1 byte | `magic` | Fixed `0xBL` -> `0xB1` |
| `1` | 1 byte | `version` | Protocol version (`1`) |
| `2` | 2 bytes | `messageId` | Unsigned 16-bit big-endian message sequence ID (duplicate detection) |
| `4` | 1 byte | `chunkIndex` | 0-based chunk index (`0..totalChunks-1`) |
| `5` | 1 byte | `totalChunks` | Total chunks (`1..64`) |
| `6` | 2 bytes | `payloadLength` | Unsigned 16-bit big-endian byte length of this chunk's UTF-8 slice |
| `8..` | N bytes | `payloadSlice` | UTF-8 JSON payload fragment |

- Maximum reassembled BLE payload: `16 KB`. Larger synchronization pages MUST use the local `wss://` WebSocket channel.
- Unframed single-MTU JSON writes starting with `{` are also accepted for diagnostic tools.
- Duplicate `messageId` values within the active 64-entry sliding window are ignored/acknowledged idempotently.

---

## 3. Pairing QR Payload & TLS Pinning

Generated on-device by `PairingManager` and rendered via QR code on the Android **Pairing** screen:

```json
{
  "v": 1,
  "deviceId": "android-device-uuid",
  "deviceName": "Pixel / Samsung",
  "token": "<one-time-hex-token>",
  "certSha256": "AA:BB:CC:... (64-char SHA-256 of DER X.509 cert)",
  "wssHost": "192.168.1.42",
  "wssPort": 8443,
  "bleService": "6E400001-C352-11E5-953D-0002A5D5C51B",
  "expiresAt": 1795350000000
}
```

### Security Rules
1. iOS MUST validate the TLS server's X.509 certificate SHA-256 digest against `certSha256` before sending any credentials.
2. `token` is one-time use and expires after `120 seconds`.
3. Android STILL requires explicit user confirmation before storing `PairedDeviceEntity` and issuing a session token.
4. Every sensitive command (`CALL_*`, `CONTACT_*`, `SMS_SEND`, `SYNC_*`) requires an active authenticated session token, valid timestamp window (replay protection), and an `idempotencyKey`.

---

## 4. Unified JSON Envelope

All messages exchanged over BLE (`EVENT_STREAM` / `COMMAND` / `PAIRING`) and WebSocket (`wss://<IP>:8443/bridge/v1`) use this envelope:

```json
{
  "version": 1,
  "id": "msg_001",
  "type": "COMMAND | EVENT | COMMAND_RESULT | SYNC_PAGE | SYNC_ACK | PAIRING_STATUS | HEARTBEAT",
  "timestamp": 1795350000000,
  "idempotencyKey": "optional-uuid",
  "sessionToken": "optional-session-token",
  "payload": {}
}
```

---

## 5. Command Verbs (`type: "COMMAND"`)

| Command | Payload Fields | Authorization | Notes |
|---|---|---|---|
| `PING` | `{}` | None | Health check & latency measurement |
| `PAIR_REQUEST` | `deviceId`, `deviceName`, `token` | Valid one-time QR token | Triggers explicit Android approval dialog |
| `AUTH` | `deviceId`, `pairingSecretHash` | Paired device | Issues fresh session token |
| `GET_STATUS` | `{}` | Session | Publishes device, SIM, call, network & sync status |
| `CALL_ACCEPT` | `{}` | Session | Requires `ROLE_DIALER` / active `BridgeInCallService` |
| `CALL_REJECT` | `{}` | Session | Requires `ROLE_DIALER` / active `BridgeInCallService` |
| `CALL_HANGUP` | `{}` | Session | Requires `ROLE_DIALER` / active `BridgeInCallService` |
| `CALL_DIAL` | `number`, `subscriptionId?` | Session | Validates number; uses `TelecomManager.placeCall` |
| `SYNC_START` | `entities: ["CONTACTS","CALL_HISTORY","SMS_OUTBOX"]`, `pageSize` | Session | Initiates paginated sync from persisted cursors |
| `SYNC_ACK` | `entity`, `cursor`, `pageNumber` | Session | Advances `SyncCursorEntity` |
| `CONTACT_UPSERT` | `contact`, `expectedFingerprint?` | Session | Validates fingerprint; Android authoritative; returns conflict if stale |
| `CONTACT_DELETE` | `contactId`, `confirmed: true` | Session | Requires explicit confirmation; writes synchronized tombstone |
| `SMS_SEND` | `destination`, `body`, `subscriptionId?`, `idempotencyKey` | Session | In Play Creator mode: queues idempotent outbox entry & triggers user-visible `ACTION_SENDTO` handoff |

---

## 6. Call State & Audio Capability Enums

- **`CallState`**: `IDLE`, `RINGING`, `DIALING`, `CONNECTING`, `ACTIVE`, `HOLDING`, `DISCONNECTING`, `DISCONNECTED`, `UNKNOWN`
- **`AudioCapabilityState`**: `CELLULAR_AUDIO_UNAVAILABLE`, `MICROPHONE_TEST_AVAILABLE`, `CELLULAR_AUDIO_EXPERIMENTAL`, `CELLULAR_AUDIO_SUPPORTED`
- **`SmsBridgeMode`**: `CREATOR_COMPLIANT_HANDOFF` (default Google Play Creator-safe mode without `READ_SMS`/`RECEIVE_SMS`/`SEND_SMS`)
- **`CallHistoryMode`**: `BRIDGELINK_OBSERVED_ONLY` (default Google Play Creator-safe mode without `READ_CALL_LOG`)

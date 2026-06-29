# Pinna Privacy And Security Notes

Milestone 8 hardening summary. Pinna is an Android-only, local-network listening room with no
accounts, no cloud storage, and no analytics.

## Permissions And Why

| Permission | Purpose | Scope guard |
| --- | --- | --- |
| `INTERNET` | Bind the local room HTTP/WebSocket server and connect on the LAN. | Local sockets only; no external relay. |
| `ACCESS_NETWORK_STATE` | Diagnose Wi-Fi vs. cellular and same-network checks. | Read-only. |
| `CAMERA` | Scan a room QR code to join. | Requested only when the scanner opens. |
| `CHANGE_WIFI_STATE` | Start a LocalOnlyHotspot fallback. | Host-initiated only. |
| `NEARBY_WIFI_DEVICES` (API 33+) | LocalOnlyHotspot on modern Android. | `neverForLocation` flag set. |
| `ACCESS_FINE_LOCATION` (≤ API 32) | Legacy Wi-Fi API requirement for hotspot. | `maxSdkVersion=32`. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep playback alive while hosting/listening. | Media playback only. |

Explicitly **not** requested: `RECORD_AUDIO`, any `BLUETOOTH*` transport permission,
`CAPTURE_AUDIO_OUTPUT`. These are asserted by `ManifestPolicyTest`.

## Token Handling

- Room tokens are generated with `SecureRandom` (32 bytes, URL-safe Base64).
- A token appears only in the QR/manual payload and in `Authorization: Bearer` headers.
- Tokens are never logged (`RoomToken.redactedForLogs()`), never written to Room
  (`TrackEntityPersistencePolicyTest`), and never placed in filenames.
- Tokens expire at `expiresAtEpochMillis`; the codec rejects expired payloads.
- HTTP and WebSocket requests with a missing or wrong token are rejected with `401`
  (`HttpLocalRoomServerControlStreamTest`).
- Listener-originated control messages are limited to join, ready, and sync-sample. Host-only
  actions (play/pause/seek/queue/end) are ignored from listeners.

## Storage

- Host-imported tracks are copied into app-private storage and recorded in Room as metadata only.
- Deleting an imported track removes its metadata and attempts to delete the private file copy.
- Listeners stream media over authenticated HTTP ranges via ExoPlayer; no persistent listener
  media cache is written. The `TemporaryRoomCache` utility (room-scoped, TTL-bound,
  SHA-256-named directories) is available for future caching and is cleared per-room and on expiry.
- App backup is disabled (`allowBackup=false`).

## Network

- No UPnP, no NAT traversal, no cloud relay, no external signaling.
- No analytics or telemetry.
- The app functions on a local network with no internet access (including the LocalOnlyHotspot
  fallback).

## LocalOnlyHotspot

- Requires explicit user action ("Use phone hotspot").
- SSID/passphrase are shown only to the host and are redacted from `toString()`/logs.
- The hotspot stops on room end and on app shutdown.
- A path back to normal LAN hosting is always available.

## Play Data Safety Inputs

- Local network communication between devices in the same room.
- Camera used only to scan a room QR code.
- Wi-Fi hotspot credentials displayed only to the host.
- Imported audio metadata stored locally on the host device.
- No accounts, no cloud storage, no data sold or shared.

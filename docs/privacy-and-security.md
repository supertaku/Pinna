# Pinna Privacy And Security Notes

Milestone 8 hardening summary. Pinna is an Android-only, local-network listening room with no
accounts, no cloud storage, and no analytics.

## Permissions And Why

| Permission | Purpose | Scope guard |
| --- | --- | --- |
| `INTERNET` | Bind the local room HTTP/WebSocket server and connect on the LAN. | Local sockets only; no external relay. |
| `ACCESS_NETWORK_STATE` | Diagnose Wi-Fi vs. cellular and same-network checks. | Read-only. |
| `CAMERA` | Scan a room QR code to join. | Requested only when the scanner opens. |
| `RECORD_AUDIO` | Push-to-talk voice between people in the room. | Requested only when the user first holds the talk button; capture runs only while held. |
| `CHANGE_WIFI_STATE` | Start a LocalOnlyHotspot fallback. | Host-initiated only. |
| `NEARBY_WIFI_DEVICES` (API 33+) | LocalOnlyHotspot on modern Android. | `neverForLocation` flag set. |
| `ACCESS_FINE_LOCATION` (≤ API 32) | Legacy Wi-Fi API requirement for hotspot. | `maxSdkVersion=32`. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Keep playback alive while hosting/listening. | Media playback only. |

Explicitly **not** requested: any `BLUETOOTH*` transport permission, `CAPTURE_AUDIO_OUTPUT`, or
media-projection capture. `RECORD_AUDIO` is requested only for push-to-talk (see below). These are
asserted by `ManifestPolicyTest`.

## Push-to-talk (microphone)

- The microphone is used only for half-duplex push-to-talk while the user holds the talk button.
  Captured PCM is base64-encoded and sent over the existing authenticated room WebSocket; it is never
  written to storage and never leaves the local network.
- The "no microphone" posture of earlier MVP milestones is intentionally relaxed for this feature; the
  Play Data Safety story must disclose microphone use for in-room voice (no recording is stored or
  shared). Background-while-locked talking (a microphone foreground service) is a documented follow-up;
  the current implementation captures only while the app is foreground and the button is held.

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

- No UPnP, no NAT traversal, no cloud relay, no external signaling for the room transport.
- No analytics or telemetry.
- The room transport functions on a local network with no internet access (including the
  LocalOnlyHotspot fallback).

## Link import (YouTube → audio)

- Optional host-side feature: paste a link; the app resolves the best audio stream on-device with
  NewPipeExtractor and downloads it over HTTPS into app-private storage. No account, API key, or
  third-party server is involved.
- **Distribution impact:** downloading/converting YouTube content violates Google Play policy and
  YouTube's Terms of Service, so a build shipping this feature must be **sideload / F-Droid only**, not
  Google Play. NewPipeExtractor is GPL-3.0, so its copyleft obligations apply to distribution.
- Network egress for this feature goes to Google's video CDN over HTTPS. Imported audio is stored
  app-private like any other imported track; no link, token, or source URL is persisted.
- The feature is fragile by nature (it breaks when YouTube changes) and requires ongoing maintenance.

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
- Microphone used only for in-room push-to-talk voice; audio is not recorded or stored.
- No accounts, no cloud storage, no data sold or shared.

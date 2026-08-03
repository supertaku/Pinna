# Pinna

Pinna is an Android-only MVP for local listening rooms. One host imports music from their Android device, starts a room, and nearby listeners join over the same Wi-Fi.

This repository contains the Android app and tested core logic for:

- room state and queue reduction
- QR room payload encoding/decoding
- NTP-style clock sync math and a listener drift-correction loop
- drift correction policy and sync quality classification
- connectivity diagnostics
- temporary room-scoped listener cache utility
- secure ephemeral room token generation
- media range parsing for local streaming
- Compose MVP screens for host/listener flows, including sync calibration

## Current Scope

Implemented:

- Kotlin Android app package `com.pinna.app`
- Jetpack Compose Material 3 UI shell
- Android manifest with minimal MVP permissions
- local-room domain and protocol models
- JVM, Room, and Compose tests for the highest-risk logic
- app-private Storage Access Framework import copy flow
- host-side link import via two build flavors (see "Build flavors" below):
  - **all flavors:** license-clean direct-audio URL import (podcast/Internet Archive/CC/own cloud), HTTPS only
  - **full flavor only:** on-device YouTube audio extraction (NewPipeExtractor)
- Room database persistence with startup hydration of imported tracks
- Media3-backed playback controller with playback-speed nudges and buffered-position reporting
- authenticated local HTTP room server/client
- WebSocket control channel (`/control-stream`) for realtime play/pause/seek/queue/sync
- CameraX + ML Kit QR scanner with manual encoded-payload fallback
- QR bitmap generation for the host share screen
- LocalOnlyHotspot fallback with host-address rebinding and payload regeneration
- listener startup playback from host-served media URLs with bearer headers
- production sync loop: scheduled host-time starts, NTP sampling, drift correction, manual offset
- sync diagnostics: quality chip, round-trip estimate, drift estimate, correction count, buffer health
- half-duplex push-to-talk voice between people in the room (microphone, PCM over the room WebSocket,
  audio-focus ducking of music). Adds the `RECORD_AUDIO` permission; see docs/privacy-and-security.md.
- listener auto-reconnect with jittered backoff; SSRF/local-address guard on join; Media3
  MediaSessionService plus process-scoped room ownership for background playback and configuration
  changes

Validated by automated tests and code review; multi-device audio sync requires the manual device
matrix in [docs/qa-checklist.md](docs/qa-checklist.md) before release-candidate sign-off.

## Build flavors

Two product flavors on the `distribution` dimension:

- **`play`** — Google Play-eligible. License-clean direct-audio URL import only; **no** YouTube
  extraction and **no** GPL-licensed dependency linked.
- **`full`** — sideload / F-Droid. Adds on-device YouTube audio extraction (NewPipeExtractor, GPL-3.0).
  Distributing this build means licensing Pinna under GPL-3.0 and publishing source; it also carries
  YouTube ToS / copyright risk. See [docs/privacy-and-security.md](docs/privacy-and-security.md).

The YouTube code lives only in `app/src/full`, so the `play` runtime classpath contains no
NewPipeExtractor.

## Build And Test

This workspace includes the standard Gradle wrapper for Gradle 9.0.0. Tasks are per-flavor:

```powershell
.\gradlew.bat testPlayDebugUnitTest
.\gradlew.bat testFullDebugUnitTest
.\gradlew.bat :app:compilePlayDebugKotlin
.\gradlew.bat :app:compileFullDebugKotlin
.\gradlew.bat connectedPlayDebugAndroidTest   # requires a device/emulator
```

`connected*AndroidTest` currently verifies Android test APK packaging plus the Compose/Room tests; run
the manual device matrix in [docs/qa-checklist.md](docs/qa-checklist.md) for real behavior coverage.

## Security And Privacy Defaults

- no account system
- no cloud relay
- no third-party audio capture
- app backup disabled
- temporary cache is room-scoped
- room tokens are generated with `SecureRandom` and redacted from logs
- non-launcher app components should remain non-exported as they are added

## Product Defaults

- minimum SDK: 26
- target SDK: 36
- listener cap target: 8 devices
- primary join path: QR scan, with manual encoded payload paste as fallback
- primary transport: existing LAN, with LocalOnlyHotspot fallback
- sync goal: practical same-room sync, not professional speaker-array alignment

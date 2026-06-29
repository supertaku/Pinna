# Pinna QA Checklist

## Automated Commands

Tasks are per build flavor (`play` = Play-eligible, `full` = sideload with YouTube):

```powershell
.\gradlew.bat testPlayDebugUnitTest
.\gradlew.bat testFullDebugUnitTest
.\gradlew.bat :app:compilePlayDebugKotlin
.\gradlew.bat :app:compileFullDebugKotlin
.\gradlew.bat :app:compilePlayDebugAndroidTestKotlin
.\gradlew.bat :app:compileFullDebugAndroidTestKotlin
.\gradlew.bat connectedPlayDebugAndroidTest   # requires an attached device/emulator
```

Flavor guard: `:app:dependencies --configuration playDebugRuntimeClasspath` must contain **no**
NewPipeExtractor; the `full` classpath must contain it.

## JVM Coverage Present

- room reducer idempotency and stale sequence rejection
- QR payload round trip, malformed payload, expired payload, token redaction
- clock sync offset and bad sample rejection
- listener sync controller: NTP sample folding, drift correction, correction counting
- drift policy thresholds (ignore / nudge / seek / rebuffer)
- sync quality classification and Bluetooth route-latency advisory
- connectivity diagnostics classification
- temporary room cache TTL and room-scoped deletion
- protocol message round trips, including sync sample
- room token generation, expiry, and constant-time comparison
- media HTTP range parsing
- authenticated local HTTP room server/client behavior
- WebSocket handshake, frame codec, oversized-frame rejection, unauthorized upgrade
- WebSocket sync-sample request/host-stamped reply
- session controller host/listener state transitions and sync wiring
- manifest policy assertions (no Bluetooth/system-capture, mic only for PTT, single exported component)
- Room persistence policy (no token/payload fields in `TrackEntity`)
- local-address/SSRF guard on join (LocalAddressValidator)
- listener reconnect backoff (ReconnectBackoff) and control-stream drop → reconnect
- YouTube link recognition/normalization (YouTubeUrlValidator) and importFromUrl dispatch
- push-to-talk: protocol round-trip, half-duplex TalkArbiter, server voice fan-out, controller
  voice in/out + arbitration

## Instrumented / Compose Coverage Present

- Room database insert/list/delete (`PinnaDatabaseTest`)
- QR bitmap generation (`QrBitmapGeneratorTest`)
- host QR share dialog, hotspot credentials, create-room enablement
- camera permission denial fallback, invalid-payload error
- listener room sync calibration (quality chip, connection chip, manual offset slider/reset)

## Manual Device Matrix

API levels: 26, 29, 31, 33, current target API.
Device counts: 2 phones, 4 phones, 8 phones.

Network conditions:

- same router 2.4 GHz
- same router 5 GHz
- LocalOnlyHotspot
- weak signal
- AP/client isolation

Scenarios:

- host imports an MP3/M4A/OGG file
- imported tracks survive an app restart (Room hydration)
- host creates a room on LAN and shows a real QR code
- listener scans the QR from a second device and joins
- listener pastes the manual payload as a fallback and joins
- listener receives room metadata from `/room` and live events over WebSocket
- listener playback starts from authenticated `/media/{trackId}` when the host is already playing
- invalid or expired payload fails cleanly
- wrong-network / cellular-only join is rejected with guidance
- host play/pause/seek reaches listeners without polling
- listener joins mid-track and starts near the host position
- host locks the phone; playback and the room continue
- listener toggles Wi-Fi off/on and reconnects
- host ends the room; listeners exit cleanly and the hotspot stops
- manual output offset slider shifts listener timing as expected
- Bluetooth output shows the route-latency warning
- paste a direct audio link (podcast/Archive/CC) on the host in EITHER flavor; it downloads into
  private storage and plays in the room (requires network)
- paste a YouTube link in the `full` build; audio downloads and plays (sideload only; requires network)
- paste a YouTube link in the `play` build; it fails cleanly with "not a direct audio file"
- push-to-talk: hold the talk button on two devices; only one talker at a time; music ducks while
  someone talks and restores after; measure perceived voice latency
- microphone permission is requested only on first talk and capture stops on release
- background/locked-screen playback continues (Media3 MediaSessionService) — device verification

## Audio Sync Validation

- play a click track on host + listeners
- externally record the devices simultaneously
- measure P50 / P95 drift
- record correction events from the diagnostics screen (correction count, drift estimate)
- document acceptable listener-count limits per network condition

## Battery / Thermal

- 60-minute host session: log battery drain, CPU, thermal throttling, dropped sockets
- 60-minute listener session: same metrics

## Release Criteria

- no crash in a 60-minute two-phone session
- stable two-phone sync on good Wi-Fi
- acceptable four-phone sync
- documented eight-phone limitations
- QR scan works on a second device
- LocalOnlyHotspot works on at least one supported Android 13+ device
- invalid QR / token / network failures are clean and actionable

## Release Blockers

- any request for `RECORD_AUDIO`
- any third-party music capture
- listener cache visible in public storage
- QR token in logs, screenshots, filenames, or crash payloads
- exported non-launcher components without an explicit reason
- cloud/network calls outside local room behavior

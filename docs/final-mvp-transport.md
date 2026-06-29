# Pinna Final MVP Transport And Test Guardrails

## Scope Boundary

Pinna MVP remains an Android-only local listening room. The allowed remaining work is:

- QR code sharing and camera-based QR joining.
- LocalOnlyHotspot fallback when a shared LAN is unavailable.
- WebSocket control messages for realtime room state and sync.
- Room persistence for host-imported track metadata.
- Practical multi-device sync validation and diagnostics.

Out of scope remains:

- Accounts, friend graph, public rooms, chat, moderation, cloud relay, remote internet rooms, iOS, web, Bluetooth transport, system-audio capture, and permanent listener downloads.

> Scope addendum (post-MVP, by product decision): two features were added beyond the original MVP
> boundary. (1) Host-side **link import**, split across build flavors: the Play-eligible `play` flavor
> does license-clean direct-audio URL import only, while the sideload-only `full` flavor adds on-device
> YouTube extraction (GPL-3.0, violates Google Play policy + YouTube ToS); see
> docs/privacy-and-security.md. (2) **Push-to-talk** voice, which introduces microphone capture. These
> intentionally relax the earlier "no streaming-service integrations / no microphone capture" exclusions.

## Transport Model

Pinna uses a dual local-channel design:

- HTTP serves room snapshots, host time, and authenticated media byte ranges.
- WebSocket carries authenticated realtime control and sync messages.
- QR payloads carry the local endpoint, room id, token, expiry, and fingerprint.
- LocalOnlyHotspot is a fallback transport path only; existing LAN remains primary.

The listener never receives a public export path for media. Any listener media cache must remain app-private and temporary.

Listener-visible room snapshots and control messages must contain only safe track metadata, stable track ids, and authenticated local media routes. They must not expose host `content://` URIs, host file paths, app-private cache paths, database ids, or listener cache paths.

## Authority And Endpoint Guardrails

The shared room token admits a nearby listener to the room; it does not grant host authority. Listener-originated WebSocket messages are limited to join, ready, and sync-sample messages. Host-only actions such as play, pause, seek, queue mutation, and room end must originate from the host controller/server path, include fresh sequence numbers, and reject stale or replayed events.

Before a listener uses a QR endpoint, the app must reject public IP addresses, non-local hostnames, cellular-only routes, and endpoints that fail local reachability checks. Pinna must not attempt UPnP, NAT traversal, or external relay behavior.

Room tokens must never be logged, persisted, placed in filenames, included in crash metadata, or displayed outside the explicit QR/manual share UI.

LocalOnlyHotspot must require explicit user action, must not persist or log SSID/passphrase, must stop on room end/shutdown, and must provide a path back to normal LAN hosting.

## Persistence Model

Only host-imported library metadata is persisted in Room. Active room tokens, QR payloads, listener cache paths, and ephemeral socket state must not be persisted.

## Test Conventions

- Add JVM tests for pure codecs, reducers, protocol parsing, sync math, and repository mapping.
- Add Android tests for Room database behavior, CameraX/QR integration compile coverage, Compose permission states, and runtime wiring that requires Android framework APIs.
- Add manual device evidence for two to eight Android devices before release candidate status.
- Named manual scenarios are required for invalid/expired payloads, wrong-network joins, AP/client isolation, transient reconnects, hotspot fallback, 30-minute soak, and click-track drift measurement.
- `connectedDebugAndroidTest` is compile/install coverage only until behavior-specific instrumented tests are present.

## Verification Commands

Run these after every milestone:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

Run this when at least one Android device or emulator is connected:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

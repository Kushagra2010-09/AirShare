# AirShare 0.1.0

Android-to-Android nearby file sharing with a two-way handshake: pick files anywhere → Share → AirShare →
phones nearby are asked → receivers accept → **you** confirm one receiver → encrypted transfer.
No account, server, cloud or internet. Kotlin, Jetpack Compose, Google Nearby Connections (play-services-nearby 19.3.0). Min Android 9 (API 28).

## Build
Needs JDK 17 + Android SDK 35.  `gradle :app:testDebugUnitTest :app:assembleDebug`
(Android Studio: open this folder, Sync, Run.) Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
GitHub: push everything (or `airshare-project.zip` + `.github/workflows/build.yml`) and the *Build AirShare* workflow publishes the APKs under Releases.

## Status — honest report
**Status legend:** VERIFIED = compiled and run in the authoring sandbox. WRITTEN = code exists, compiled only by you/CI. DEVICE = needs two real phones.

| Area | Status |
|---|---|
| Binary protocol codec (strict validation, size/count limits) | VERIFIED (unit tests) |
| Proximity filter (median/MAD outlier rejection, confidence, ambiguity rules) | VERIFIED (unit tests, synthetic data only) |
| Filename sanitising / path traversal / duplicate names, invite rate-limit gate, throughput meter | VERIFIED (unit tests) |
| Gradle/Compose/Android code, UI, service, notifications | WRITTEN — **not compiled** in the sandbox (no Android SDK / Google Maven access). Expect to fix a few compile errors on the first CI run. |
| Discovery, handshake, transfer, background service, BLE beacon, notifications | WRITTEN, **DEVICE-testing required**. Nothing here has run on a phone. |

Implemented: Share Sheet target (single/multiple files), multi-receiver invitations with unique ids and expiry, per-receiver
state, explicit final recipient confirmation (dialog), reject/disconnect of non-chosen receivers, streamed file payloads
(no RAM buffering), SHA-256 + size verification before a file becomes visible, saving to Downloads/AirShare (MediaStore pending on
API 29+; `.part` + rename on API 28), cancellation both sides, history (last 50), foreground-service background receiving with
bounded retry back-off, Bluetooth on/off + screen-off pause + permission-revoked handling, boot resume, Material 3 UI (light/dark/system, optional dynamic colour), reduced-motion respect.

**Not implemented:** UWB / Bluetooth Channel Sounding ranging (hardware presence is only *reported* in diagnostics), trusted devices
(deliberately left out rather than half-secured), pause/resume of transfers, transfer-resume after interruption, Room, benchmarks.

## Security model and limits
- Nearby Connections encrypts the link and gives a 4-digit code shown on both phones. Compare it; if nobody compares, a man-in-the-middle is not excluded.
- The transport connection is accepted automatically only as a tiny control channel (max 16 KB messages, strict parser, ≤3 pending / 6 per minute). No file payload is accepted before the receiver taps Accept **and** the sender confirms.
- Identity = the authenticated connection (endpoint id + code), never the display name. A receiver whose advertised token changes mid-handshake is rejected.
- The BLE beacon token (3 random bytes) is copyable, so proximity only *suggests*; it can never select a receiver. It is stable until receiving restarts, so nearby observers can notice an AirShare phone. Turn it off in Settings.
- Android 9 only: Nearby stores the raw incoming file in Download/Nearby; AirShare deletes it after saving, but it is briefly visible.

## Proximity
RSSI is smoothed and outlier-filtered; only coarse words are shown (Very close / Nearby / A bit farther / Signal unclear), never centimetres.
All thresholds in `ProximityConfig` are **unvalidated defaults** and must be calibrated with real phones.
Rules: one responder → highlighted; one clearly stronger (≥8 dB margin, all responders measurable) → "Suggested"; otherwise sender chooses.

## Background receiving (what is true)
Nearby advertising needs a live process, so receiving uses a `connectedDevice` foreground service with a persistent low-priority notification
(system-managed PendingIntent scanning cannot replace it for Nearby's advertising protocol). The service stops itself whenever receiving is off and nothing is transferring. No polling, no wake locks, no GPS.
Some manufacturers kill background apps anyway; the app reports status but cannot promise uninterrupted operation. `START_STICKY` restart behaviour is untested.
Strategy is `P2P_CLUSTER` (needed for one sender → several receivers); peak throughput is unmeasured and may be lower than point-to-point.

## Permissions
Bluetooth advertise/scan/connect (Android 12+), Nearby Wi-Fi devices (13+), Location (≤ Android 12 only; the system requires it for discovery; AirShare never reads your location, and on Android ≤ 11 the Location *switch* must be on),
Notifications (13+), Foreground service (connected device), Boot completed, Storage write (Android 9 only), Wi-Fi state.

## Real-device test checklist (two phones, ideally Android 9–10 and 13+)
1. Phone B: enable *Receive nearby files*, grant permissions. Lock screen. Phone A: Gallery → share 1 photo → AirShare.
2. B gets "Nearby sharing request" with Accept/Decline. Accept. A shows B with a code; codes match; tap Send → confirm.
3. File appears in Downloads/AirShare on B; open it; compare checksum.
4. Repeat: multiple files, a 1 GB+ video, cancel mid-transfer (both sides), airplane-mode mid-transfer, decline, let it expire.
5. Three phones: two receivers accept; verify only the chosen one receives and the other is rejected.
6. Toggle Bluetooth off/on on B; revoke permission; enable battery saver; swipe app away; reboot. Record what survives.
7. Measure (not done yet): idle RAM/CPU (`adb shell dumpsys meminfo app.airshare`), battery drain over an hour idle, connect latency, MB/s, proximity accuracy at 5/20/50/100 cm for several phone pairs.

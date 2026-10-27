# Proposal: Add DLNA/UPnP cast receiver

## Why

Commercial screen-casting (投屏) receiver apps on Android TV are ad-supported, subscription-gated, or ship private protocols that hijack the standard cast button in Chinese video apps (Bilibili, iQiyi, Tencent Video, etc.). A minimal, open, standard-compliant DLNA/UPnP MediaRenderer makes any LAN sender cast ad-free, works on old 32-bit TV firmware (Android 9 / API 28 / armeabi-v7a), and is small enough to idle permanently without noticeable RAM cost.

## What Changes

- New Android TV application (Kotlin) implementing a UPnP AV `MediaRenderer` device.
- SSDP discovery: answers `M-SEARCH` for `MediaRenderer` targets and multicasts `NOTIFY ssdp:alive` / `ssdp:byebye` so sender apps list the receiver within seconds.
- UPnP device/service descriptions served over HTTP (device description, AVTransport, RenderingControl, ConnectionManager SCPDs).
- SOAP control endpoint for the actions senders actually use: `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, `GetPositionInfo`, `GetTransportInfo`, `GetMediaInfo`, `SetVolume`, `GetVolume`, `GetMute`, `SetMute`, plus `GetProtocolInfo` — with correct UPnP error responses for unsupported actions.
- Hardware-accelerated playback of cast URLs via Media3/ExoPlayer (HLS `.m3u8`, progressive MP4/FLV, cross-protocol redirects), surfaced fullscreen with TV D-pad controls (OK = pause/resume, ◀ ▶ = seek ±10 s, Back = stop).
- GENA `SUBSCRIBE`/`UNSUBSCRIBE` handling (minimum: accept subscription and answer `SUBSCRIBE` with a valid SID; eventing can be a no-op callback) so senders that require a subscription before casting do not fail.
- Android shell: foreground service holding a `MulticastLock` (SSDP requires it), optional start-on-boot, and a settings screen to change the friendly name; the friendly name defaults to the device model (e.g. `Build.MODEL`) — **no hardcoded product/device names** so the project stays reusable when open-sourced.
- JVM unit tests (TDD) for all protocol logic: SSDP message round-trips, SOAP request parsing, UPnP response rendering, transport state machine, and time formatting (`H:MM:SS`).
- End-to-end smoke test harness (scripted UPnP control point) used from a dev machine against a real TV.

## Capabilities

### New Capabilities

- `ssdp-discovery`: Make the receiver discoverable on the LAN — respond to UDP multicast M-SEARCH and emit NOTIFY alive/byebye per UPnP 1.0.
- `upnp-renderer`: Serve UPnP device/service descriptions and handle SOAP control actions for AVTransport, RenderingControl, and ConnectionManager, with spec-compliant responses and errors.
- `media-playback`: Play, control, and report progress of cast media streams using hardware decoding, including TV remote key handling and graceful failure for unsupported sources.
- `receiver-app`: Android application packaging — foreground service with multicast lock, boot autostart, configurable friendly name, and lean TV settings UI.

### Modified Capabilities

(none — initial change)

## Impact

- **New code**: entire repository (`app/` Gradle module; domain layer in pure Kotlin for JVM tests).
- **New dependencies**: NanoHTTPD (embedded HTTP server), Media3 ExoPlayer + HLS (playback). Both are Maven Central artifacts; no native libraries added (project stays pure-ABI and runs on armeabi-v7a).
- **Build**: AGP/Gradle wrapper; `minSdk 21`, `targetSdk 28` (TV-oriented; verified against Fire OS 7 / Android 9).
- **Runtime**: tested target is an Amazon Fire TV (MT7632, 32-bit userland); device under test at `192.168.31.47`.
- **License**: Apache-2.0 (dependencies are Apache-2.0/BSD-compatible).

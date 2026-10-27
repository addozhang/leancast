# Design: DLNA/UPnP cast receiver

## Context

Target device class: Android TV boxes/panels on old firmware — the primary test target is an Amazon Fire TV (Fire OS 7.7 / Android 9 / API 28) with a **32-bit userland** (`armeabi-v7a`, empty `/system/lib64`, MTK MT7632 with OMX hardware decoders). Sender population: mainstream Chinese video apps whose cast buttons speak SSDP + UPnP AVTransport with HLS/MP4 URLs. The project must remain open-sourceable: no hardcoded branding, permissive license, lean dependencies.

Development approach is OpenSpec SDD with strict TDD: domain logic lives in a pure Kotlin module testable on the JVM; every spec scenario maps to at least one unit test; end-to-end behavior is verified by a scripted UPnP control point run from the development machine against the real TV.

## Goals / Non-Goals

**Goals**
- Standard UPnP AV `MediaRenderer:1` that senders discover and drive with zero private-protocol negotiation.
- Ad-free, tiny, idle-cheap foreground service on API 21+ (verified on API 28).
- Pure-Kotlin APK (no native libs) → one artifact for all ABIs.
- All protocol logic (SSDP, SOAP, XML, state machine, time math) JVM-unit-tested; Android layer kept thin.

**Non-Goals (v1)**
- GENA event *delivery* (subscriptions are accepted; no `NOTIFY` callbacks to senders).
- `RenderingControl` per-channel audio beyond Master; equalizer/AV sync settings.
- Multi-instance (`InstanceID != 0`) sessions — accepted but mapped to instance 0.
- AirPlay/Miracast/Google Cast; QR setup; i18n beyond English + Chinese resources.
- DRM-protected streams.

## Decisions

1. **Protocol stack written in-repo (no Cling/jUPnP).** Cling is abandoned upstream, drags in servlet dependencies, and its async model obscures testability. The used surface of UPnP AV is small: SSDP datagram codec, 3 SCPD XMLs, ~14 SOAP actions. Hand-rolling keeps the APK tiny and every byte testable. *Alternative*: NanoDLNA/jUPnP forks — rejected for the same weight/health reasons.
2. **NanoHTTPD 2.3.1 for the control plane HTTP server.** SSDP is raw UDP (own socket), but description/SCPD/control/event URLs are HTTP/1.1; NanoHTTPD gives battle-tested keep-alive/parsing in ~100 KB. *Alternative*: raw `ServerSocket` loop — rejected: keep-alive + chunked subtleties.
3. **Media3 ExoPlayer (exoplayer + hls + ui) for playback.** Platform `MediaPlayer` lacks FLV and is opaque on HLS re-directs; ExoPlayer is pure-Java (uses platform MediaCodec → the TV's OMX.MS.* decoders) so ABI-neutral. HLS module covers `.m3u8`. *Alternative*: MediaPlayer — smaller but fails B站-style FLV and gives no duration/events reliably.
4. **Hexagonal layering.** `domain` (pure Kotlin: SSDP codec, UPnP XML render/parse, transport state machine, time & volume math) ← `android` adapters (NanoHTTPD endpoint, ExoPlayer facade, settings store). Unit tests target domain only; instrumentation is limited to smoke checks.
5. **`targetSdk 28`** matches the TV generation; avoids FGS-type ceremony (API 29+) while remaining installable everywhere (compat behaviors). `minSdk 21`. `compileSdk 34` (AGP 8.x).
6. **Stable UUID + friendly name in `SharedPreferences`.** Generated once (`UUID.randomUUID()` with `uuid:` prefix per UPnP), reused in every boot; name default `Build.MODEL`, user-editable — satisfies "no hardcoded name".
7. **Single foreground service** owns: multicast lock, SSDP socket coroutine, NanoHTTPD server, `CastSession` (the state machine), and an `ExoPlayer` created lazily on first play and released on stop-to-idle after grace period — idle RAM stays low (player is the only heavy object).
8. **SSDP responses paced by MX** (uniform [0, min(MX,5)]s, jittered alive advertisements at max-age/2) per UPnP 1.0 §1.2.2 to avoid broadcast storms.
9. **SOAP parsing via regex-free `XmlPullParser`** (Android-bundled, also JVM-testable via kxml2 in tests? — no: use `org.xmlpull.v1` available in the Android SDK jar for unit tests via `xmlpull` artifact; domain module depends only on stdlib + `kxml2` test-scope). Responses rendered from Kotlin string templates — XML escaping included. *Alternative*: JAXB/kotlinx-serialization — rejected: heavyweight for fixed-shape documents.
10. **License Apache-2.0** (matches NanoHTTPD BSD-3 and Media3 Apache-2.0 compatibility).

## Risks / Trade-offs

- [Senders requiring GENA initial-event before casting hang] → Accept in v1 (spec'd as accepted-subscriptions-only); most CN apps poll `GetPositionInfo` instead. Revisit if a specific sender fails E2E.
- [FLV support depends on ExoPlayer extractor coverage] → Media3 has FLV extractor; verified in E2E with a known FLV URL; degrade gracefully (error → `STOPPED`, next cast works).
- [HTTP server port collisions] → Bind wildcard port 0 (ephemeral) or prefer 49152 with fallback; LOCATION composed from the actual bound port.
- [32-bit TV's old TLS stack vs HTTPS cast URLs] → ExoPlayer uses platform `HttpsURLConnection`; TV plays YouTube HTTPS fine in practice; if a sender's TLS fails, error path keeps receiver alive.
- [`targetSdk 28` blocks future Play publishing] → Acceptable: distribution via GitHub releases/sideload; bump later with FGS typing if ever needed.
- [Doze/App Standby on Chinese ROMs] → Foreground service + multicast lock covers it; boot autostart opt-in.

## Migration Plan

Greenfield repo — no migration. Rollback = uninstall APK; no system state mutated beyond its own preferences.

## Open Questions

- Should `byebye` fire on network change (interface down/up)? v1: only on service stop; reconnect re-advertises alive.
- Refresh `POSITION` tracking when ExoPlayer seek finishes (discontinuity) — handled via position query at GetPositionInfo time (always live query, never extrapolated).

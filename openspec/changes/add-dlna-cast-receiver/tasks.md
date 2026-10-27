# Tasks: add-dlna-cast-receiver

## 1. Project scaffold

- [x] 1.1 Create Gradle multi-module build (root + `app` + `domain`), AGP 8.x, Kotlin, `minSdk 21` / `targetSdk 28` / `compileSdk 34`, wrapper 8.12
- [x] 1.2 Add dependencies: NanoHTTPD 2.3.1, Media3 (exoplayer, exoplayer-hls, ui) in `app`; `kxml2` test-scope in `domain`; JUnit + Truth in both; verify `assembleDebug` passes
- [ ] 1.3 Add Apache-2.0 LICENSE, README (build/install/usage, no hardcoded device names), `.gitignore`, first commit

## 2. Domain: SSDP (TDD)

- [x] 2.1 Write failing tests: M-SEARCH parse (valid, malformed, missing headers) per `ssdp-discovery`
- [x] 2.2 Implement `SsdpMessage` codec (parse + render NOTIFY/SEARCH/RESPONSE with all required headers)
- [x] 2.3 Write failing tests: ST→response mapping (`ssdp:all`, rootdevice, device type, services, unsupported), USN composition
- [x] 2.4 Implement `SsdpResponder` target matching + response rendering; MX delay computation (bounded, default 1)
- [x] 2.5 Write failing tests: alive/byebye sets mirror advertised targets and carry correct NTS/USN/LOCATION

## 3. Domain: UPnP renderer (TDD)

- [x] 3.1 Write failing tests: device description XML (friendlyName injectable, UDN stable, 3 services with resolvable URLs)
- [x] 3.2 Implement `DeviceDescription` renderer + SCPD XMLs for AVTransport/RenderingControl/ConnectionManager
- [x] 3.3 Write failing tests: SOAP request parse (header + body fallback), argument extraction, DIDL `dc:title` extraction
- [x] 3.4 Implement `SoapParser` (XmlPullParser) + `DidlParser`
- [x] 3.5 Write failing tests: response envelopes for all actions incl. 401/402 fault bodies, `H:MM:SS` time conversion both ways
- [x] 3.6 Implement `SoapResponses` (Play/Pause/Stop/Seek/GetPosition/GetTransport/GetMediaInfo/Volume/Mute/ProtocolInfo) + `UpnpTime`

## 4. Domain: transport session (TDD)

- [ ] 4.1 Write failing tests: state machine transitions incl. illegal-command tolerance and error→STOPPED recovery
- [ ] 4.2 Implement `CastSession` with `PlayerPort` interface (prepare/play/pause/seek/stop/release, position/duration queries) — fake in tests
- [ ] 4.3 Write failing tests: volume clamp 0..100 and 0..100→device-stream mapping; position advance during play

## 5. Android adapters

- [ ] 5.1 `ControlServer` (NanoHTTPD): routes `/desc.xml`, SCPDs, `/ctl/*` SOAP dispatch, `/evt/*` SUBSCRIBE/UNSUBSCRIBE with SID/TIMEOUT; wire to `CastSession`
- [ ] 5.2 `ExoPlayerPort` implementing `PlayerPort` (lazy create, error→session error, duration/position live query)
- [ ] 5.3 `SsdpAgent`: UDP socket + multicast lock + M-SEARCH responder with MX pacing + periodic alive + byebye on stop
- [ ] 5.4 `ReceiverService` foreground service assembling settings/UUID/ssdp/server/session; notification channel; boot receiver behind toggle
- [ ] 5.5 UI: status screen (state, name, IP:port, item title) + settings (friendly name, autostart) + `PlayerActivity` fullscreen with D-pad keys

## 6. Verify on device

- [ ] 6.1 `assembleDebug` → install on TV (`192.168.31.47`) via ADB, launch, verify SSDP answers from Mac (Python control point: M-SEARCH → desc.xml → SCPD)
- [ ] 6.2 E2E cast: SetAVTransportURI (LAN-hosted MP4 + public HLS) → Play → GetPositionInfo advances → Seek → Pause/Play → Stop; D-pad smoke
- [ ] 6.3 Idle RAM/measurements + reboot persistence of UUID; archive change via `openspec archive`

# LeanCast

A minimal, ad-free **DLNA/UPnP cast receiver** for Android TV. Open any video app
with a cast button (Bilibili, iQiyi, Tencent Video, Youku, Quark, …), pick this
receiver, and the video plays fullscreen with hardware decoding — no ads, no
accounts, no cloud.

```
Phone app --SSDP--> discovers  Phone app --SOAP SetAVTransportURI--> LeanCast
                                                                    |
                                                        ExoPlayer + MediaCodec HW decode
```

## Highlights

- **Standard UPnP AV `MediaRenderer:1`** — SSDP discovery, AVTransport /
  RenderingControl / ConnectionManager SOAP control, GENA subscribe stubs.
- **Hardware decoding** via Media3/ExoPlayer: HLS (`.m3u8`), progressive MP4,
  Matroska, HTTP-FLV (best effort), HTTP↔HTTPS redirects, cleartext LAN URLs.
- **TV remote controls**: OK = pause/resume, ◀ ▶ = seek ±10 s, Back = stop.
- **Lean & private**: idle PSS ≈ 70 MB (≈ 39 MB private) on a 1.6 GB TV;
  zero analytics, zero non-LAN network calls; pure-Kotlin APK with **no native
  libs** — one artifact runs on `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`.
- **Nothing hardcoded**: the cast name defaults to the device model
  (`Build.MODEL`) and is user-renamable; UUID persists across reboots.
- Tested on an Amazon Fire TV (Fire OS 7 / Android 9, 32-bit MTK SoC) with
  `minSdk 21` / `targetSdk 28`.

## Build

Requirements: JDK 17+, Android SDK (platform 34). Then:

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Install & use

```bash
adb connect <TV-IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.leancast/io.leancast.app.MainActivity
```

Launch the app once to start the receiver service; it then appears in every
DLNA sender's device list under the TV's model name (rename it in the app).
Optional: enable **Start on boot** in the app.

## Project layout

```
domain/          Pure-Kotlin protocol core (JVM-unit-tested, 72 tests)
  ssdp/          M-SEARCH parsing, target matching, USN, alive/byebye, MX delays
  upnp/          Device description + SCPDs, SOAP parser (prefix-tolerant DIDL),
                 response envelopes & faults, H:MM:SS time math, SOAP router
  cast/          AVTransport state machine, player port, volume math
app/             Android shell (thin adapters, E2E-verified on device)
  ControlServer  NanoHTTPD: /desc.xml, /scpd/*, /ctl/*, /evt/*
  SsdpAgent      Multicast UDP listener + advertiser (MulticastLock held)
  ExoPlayerPort  PlayerPort adapter (main-thread marshalled, cached position)
  ReceiverService Foreground service assembling everything
tools/
  e2e_control_point.py  Scripted UPnP control point for device E2E runs
openspec/        Spec-driven development artifacts (proposal/specs/design/tasks)
```

Development follows **OpenSpec** (`openspec/`) with strict TDD: every spec
scenario maps to unit tests in `domain`; run them with `./gradlew :domain:test`.
End-to-end verification drives a real device from a dev box:

```bash
python3 tools/e2e_control_point.py http://<lan-host>/video.mp4
```

## License

Apache-2.0. Dependencies: NanoHTTPD (BSD-3), Media3 (Apache-2.0).

# Design: harden-cast-ux

## Context

Field tests with real Chinese senders: Quark/CCTV/Tencent cast + control work; bilibili/Youku show title but no picture; every sender lacks on-screen feedback during pause/seek. chatty-compressed logs hid the exact CDN error, so fixes must both (a) remove known header/redirect obstacles and (b) make future failures diagnosable.

## Goals / Non-Goals

**Goals** HUD feedback; CDN-friendly fetch defaults; diagnosable errors; real icon.
**Non-Goals** GENA eventing; Miracast (system cast) — explain to user, Fire TV has its own display-mirroring sink; transcoding.

## Decisions

1. **Media3 `DefaultHttpDataSource.Factory` with UA + `setDefaultRequestProperties` and `setAllowCrossProtocolRedirects(true)`, wrapped in `DefaultDataSource.Factory`, injected via `DefaultMediaSourceFactory`.** Rationale: CN CDNs commonly filter non-browser UAs; manual cross-protocol handling in DefaultHttpDataSource only happens with the flag. Headers come from pure `MediaHttpHeaders` (domain) so they're JVM-testable.
2. **HUD = PlayerView's built-in controller** (`useController=true`, `controllerShowTimeoutMs=2500`, shown on D-pad interaction) **plus a small custom center overlay** (state icon shown ~1.2 s on pause/resume/seek). Rationale: the built-in controller already renders a TV-focusable timebar + play/pause; a custom overlay only adds the transient glanceable signal users expect from native TV players.
3. **Error logging in `ExoPlayerPort.onPlayerError`: `Log.e(TAG, "playback error ${error.errorCodeName} uri=$lastUri")`.** Cheap, survives chatty (error-level lines from our own tag aren't rate-limited the same way), enough for field triage.
4. **Icon generated with Pillow** (host-side script committed under `tools/`): adaptive foreground PNG (432×432, art within safe zone) + solid background color; legacy rasters downscaled to mdpi…xxxhdpi. Regenerate with `python3 tools/gen_icon.py`.

## Risks / Trade-offs

- [UA spoofing may still not satisfy every CDN] → error logs + URI dump let us iterate per-sender.
- [Controller focus may intercept DPAD_CENTER meant for pause] → controller's play/pause button receives focus only after user navigates to it; global key handling stays in `onKeyDown` (consumed before controller when not focused on it).
- [Adaptive foreground as bitmap] → larger than a vector but trivially generated; fine at icon sizes.

## Migration Plan

Purely additive UI/resource changes; reinstall over previous build. Rollback = previous APK.

## Open Questions

- Whether bilibili also needs `Referer` — decide from field logs after this change ships.

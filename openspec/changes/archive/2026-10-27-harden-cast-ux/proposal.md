# Proposal: Harden cast UX (playback HUD, CDN compat, icon)

## Why

Real-sender testing on the Fire TV surfaced three gaps: (1) bilibili/Youku casts show the item title but no picture — their CDNs are picky about HTTP headers and their streams need cross-protocol redirect support; (2) working casts (Quark, CCTV Video, Tencent) give zero on-screen feedback — no progress bar, no pause/resume indicator; (3) the launcher icon is a placeholder. Also confirmed: phone system-level "cast" (Miracast) will never list a DLNA renderer — documented, not fixed here.

## What Changes

- Player screen gains an on-screen HUD: seekbar with elapsed/total time, a play/pause button, and a transient center indicator on pause/resume/seek.
- Media HTTP loading uses a browser-like `User-Agent` plus `Accept` defaults and cross-protocol redirect following on the ExoPlayer data source; player errors are logged with error codes for field diagnosis.
- New launcher icon: adaptive icon (API 26+) with density PNG fallbacks, generated programmatically; no text/product names baked into artwork.
- No protocol behavior changes; SSDP/UPnP surface untouched.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `media-playback`: ADDED requirement — on-screen HUD (seekbar, position, transient state indicator); MODIFIED requirement — playback loading uses compatible HTTP headers and follows cross-protocol redirects.
- `receiver-app`: ADDED requirement — launcher icon (adaptive + legacy fallbacks).

## Impact

- `app/PlayerActivity` (HUD overlay + controller), `app/ExoPlayerPort` (data-source factory, error logging), launcher resources (`mipmap*`, adaptive XML), manifest icon reference.
- Domain: new pure helper `MediaHttpHeaders` (JVM-tested).
- Risk: none to protocol compatibility; header changes only affect outbound fetches.

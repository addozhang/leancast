# Tasks: harden-cast-ux

## 1. Domain (TDD)

- [x] 1.1 Failing tests for `MediaHttpHeaders.forUri` (browser UA + Accept on http/https; empty map for non-http schemes)
- [x] 1.2 Implement `MediaHttpHeaders` in domain/cast

## 2. Player adapter

- [x] 2.1 Wire `DefaultHttpDataSource.Factory` (UA, default request props, cross-protocol redirects) through `DefaultMediaSourceFactory` into `ExoPlayer.Builder`
- [x] 2.2 Log `errorCodeName` + failing URI on player error; track last prepared URI

## 3. Playback HUD

- [x] 3.1 Enable PlayerView controller (seekbar + times + play/pause, 2.5s auto-hide) on the player screen
- [x] 3.2 Add transient center indicator (pause/play) shown ~1.2s on key presses and state changes

## 4. Icon

- [x] 4.1 Write `tools/gen_icon.py` (Pillow) producing adaptive foreground/background + legacy mipmaps; run it
- [x] 4.2 Point manifest at adaptive `@mipmap/ic_launcher` with legacy fallbacks; verify both resolvers

## 5. Verify

- [x] 5.1 `:domain:test` green; `assembleDebug`; reinstall on TV; rerun 12-step E2E + D-pad (HUD visible in screenshots)
- [x] 5.2 User retests bilibili/Youku; if still failing, capture tagged error logs and iterate

## 6. Sender-compat fixes (from field logs)

- [x] 6.1 Auto-start playback on non-empty SetAVTransportURI (bilibili sends Set + polls, never Play); Play made idempotent
- [x] 6.2 Explicit Hls/Progressive MediaSource routing (DefaultMediaSourceFactory inference missed mid-path "m3u8"); prepare logging with resolved MIME
- [x] 6.3 Verified on TV: youku HLS (captured live URL) plays via Set-only cast; 12/12 E2E regression green
- [x] 6.4 Remove request-level diagnostic logging (kept spec'd error-path logs only); 12/12 E2E regression green

## 7. Accelerated seeking

- [x] 7.1 TTD domain SeekStepPolicy (escalating 10/30/60/120/300s ladder by repeat count, capped)
- [x] 7.2 PlayerActivity: use policy for DPAD L/R repeats + handle MEDIA_FAST_FORWARD/MEDIA_REWIND
- [x] 7.3 On-device long-press verification

## 8. 10-foot TV UI & Custom OSD (Plan A)

- [x] 8.1 TDD `OsdFormatter` in `domain/cast` (signed seek delta formatting `+10s`, `+1:00`, `-5:00` and progress ratio math)
- [x] 8.2 Implement Plan A two-column 10-foot TV dashboard in `MainActivity` with D-pad focus highlight + scale animation
- [x] 8.3 Replace default Media3 touch controller in `PlayerActivity` with custom TV OSD (top title bar, center glassmorphic state/seek pill, bottom progress bar + remote legend)
- [x] 8.4 Verify on Fire TV and capture screenshots of new MainActivity & PlayerActivity OSD
- [x] 8.5 Separate pure `values/strings.xml` (English) and `values-zh/strings.xml` (Simplified Chinese) and add a language toggle card (`中文` / `EN`) persisted in `Settings`
- [x] 8.6 Spec rows replace 3-cell grid (no truncation); zh guide step 2 aligned with en (generic video app); header brand icon matches launcher adaptive crop (ic_brand.png via gen_icon.py)

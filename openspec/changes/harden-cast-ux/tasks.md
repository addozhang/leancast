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
- [ ] 5.2 User retests bilibili/Youku; if still failing, capture tagged error logs and iterate

## 6. Sender-compat fixes (from field logs)

- [x] 6.1 Auto-start playback on non-empty SetAVTransportURI (bilibili sends Set + polls, never Play); Play made idempotent
- [x] 6.2 Explicit Hls/Progressive MediaSource routing (DefaultMediaSourceFactory inference missed mid-path "m3u8"); prepare logging with resolved MIME
- [x] 6.3 Verified on TV: youku HLS (captured live URL) plays via Set-only cast; 12/12 E2E regression green

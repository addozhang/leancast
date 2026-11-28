# Tasks: add-opt-in-app-update

## 1. Domain (TDD — pure Kotlin in `domain/update`)

- [x] 1.1 Failing tests for `SemVer` (parsing `0.3.0`, `v0.3.1`, prerelease suffixes, ordering, invalid input rejection)
- [x] 1.2 Implement `SemVer`
- [x] 1.3 Failing tests for `UpdateManifest` validation (valid fields, reject blank version, reject path-traversal/non-`.apk` filenames, reject invalid SHA-256 hex)
- [x] 1.4 Implement `UpdateManifest`
- [x] 1.5 Failing tests for `UpdateUrlResolver` (ordered `[dl.solocraft.app/leancast/..., github.com/addozhang/leancast/releases/...]` URLs for `latest.json` and versioned APK files)
- [x] 1.6 Implement `UpdateUrlResolver`
- [x] 1.7 Failing tests for `UpdateCheckThrottle` (disabled always false, first check true, within 6h false, after 6h true)
- [x] 1.8 Implement `UpdateCheckThrottle`

## 2. CI Release Manifest

- [x] 2.1 Update `.github/workflows/release.yml` to compute `sha256sum` of `LeanCast-${VERSION_NAME}.apk`, generate `latest.json` (with `version`, `changelog`, and `assets["android-universal"] = { file, sha256 }`), and attach `latest.json` to the GitHub Release

## 3. Android Settings, Permissions & Updater Engine

- [x] 3.1 Add `updateCheckEnabled` (default `false`) and `lastUpdateCheckMs` (default `0L`) to `Settings.kt`
- [x] 3.2 Add `REQUEST_INSTALL_PACKAGES` permission and `FileProvider` (`${applicationId}.fileprovider`) to `AndroidManifest.xml` + `res/xml/file_paths.xml` exposing `cache-path` `updates/`
- [x] 3.3 Implement `AppUpdater.kt` using `HttpURLConnection` + pure-Kotlin `UpdateManifestParser` + `MessageDigest("SHA-256")`: multi-source fallback, HTTP 2xx + manifest validation, monotonic progress callback, `.part` atomic rename + SHA-256 check + stale cache cleanup, and installer handoff (`canRequestPackageInstalls` check + `ACTION_VIEW` fallback)
- [x] 3.4 Add unit tests in `app/src/test` covering JSON parsing, mirror fallback against local `ServerSocket`, and SHA-256 pass/fail handling

## 4. 10-Foot TV Dashboard & Update Modal UI

- [x] 4.1 Add pure English (`values/strings.xml`) and Simplified Chinese (`values-zh/strings.xml`) strings for the Updates card, modal, progress states, permission prompt, and errors
- [x] 4.2 Add the 5th focusable Updates action card to `MainActivity` right column and new-version badge indicator next to the hero version tag
- [x] 4.3 Implement the dark TV Update Modal in `MainActivity` (D-pad toggle for opt-in update checks, status/changelog view, Check Now / Download & Install button, download/verify progress bar, manual fallback hint)
- [x] 4.4 Wire throttled foreground-only auto-check in `MainActivity` when `settings.updateCheckEnabled == true` (never in `ReceiverService`)

## 5. Docs & Verification

- [x] 5.1 Update `README.md` and `README.zh-CN.md` to document the opt-in update switch (default OFF, zero background polling)
- [x] 5.2 Run `./gradlew :domain:test :app:testDebugUnitTest assembleDebug` and verify APK remains pure Kotlin (no `lib/` native directories)
- [x] 5.3 Verify on TV device: default OFF makes no requests, enabling checks & downloads from a test release/local manifest, D-pad focus and bilingual strings render cleanly (verified end-to-end on API 34 1080p emulator against live `dl.solocraft.app` `v0.3.1`)

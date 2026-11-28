# Proposal: Opt-in App Update Check and In-App Upgrade

## Why

LeanCast currently enforces a strict zero-outbound-network posture with no update checking. While this satisfies privacy-conscious users, sideloaded Android TV / set-top box users face high friction when upgrading (USB drives, third-party transfer tools, or `adb install`). Community feedback proposed a clean compromise: add an **opt-in update toggle, disabled by default**, so out-of-the-box installs remain 100% offline-first while users who enable it can check for new releases and upgrade directly with their TV remote.

## What Changes

- **Opt-in update toggle (default OFF)**: Persisted in `Settings` (`update_check_enabled = false`). While disabled, the app makes zero outbound update requests (neither automatically nor manually).
- **Static release manifest in CI**: `.github/workflows/release.yml` generates and attaches a lightweight cross-platform `latest.json` (`version`, `changelog`, `assets["android-universal"] = { file, sha256 }`) alongside `LeanCast-<version>.apk` on every GitHub Release—avoiding GitHub REST API rate limits (`60 req/hr/IP`) and enabling self-hosted edge caching.
- **First-party mirror + direct GitHub fallback & SHA-256 verification**: Update checks and APK downloads try the first-party distribution endpoint (`https://dl.solocraft.app/leancast/...`) first and fall back to direct GitHub Releases (`https://github.com/addozhang/leancast/releases/...`) with zero third-party public proxies, rejecting non-2xx or invalid/HTML responses and strictly verifying the downloaded APK against the manifest's `sha256` digest before invoking the system installer.
- **Android TV in-app upgrade**: Downloads verified APK to `cacheDir/updates/`, handles API 26+ unknown-sources permission (`REQUEST_INSTALL_PACKAGES`), and hands off to the system package installer via `FileProvider` `content://` URI, with graceful fallback messaging on restricted TV firmware.
- **10-foot TV UI**: Adds an "App Updates / 版本更新" action card to the dashboard right column, a subtle new-version indicator on the hero version badge, and a D-pad-navigable dark modal for toggling update checks, viewing changelogs, checking manually, and tracking download/verification progress.
- **Zero new dependencies**: Pure Kotlin domain logic (`domain/update`) + JDK `HttpURLConnection` / `MessageDigest` + Android `org.json` / `FileProvider`. APK stays pure Kotlin with no native libraries.

## Capabilities

### New Capabilities

- `app-update`: Opt-in update discovery via static `latest.json`, SemVer comparison, multi-mirror fallback, SHA-256 integrity verification, in-app APK download + `FileProvider` installer handoff, and TV update modal UX.

### Modified Capabilities

- `receiver-app`:
  - MODIFIED `Lean permission and network posture`: adds `REQUEST_INSTALL_PACKAGES` for opt-in self-upgrade and refines the network rule so zero non-LAN app requests occur by default while permitting user-enabled update checks/downloads.
  - MODIFIED `10-foot two-column TV dashboard UI`: adds the focusable Updates card in the right action column and optional new-version badge indicator in the left hero header.

## Impact

- `domain/src/main/kotlin/io/leancast/domain/update/`: new pure Kotlin classes (`SemVer`, `UpdateManifest`, `UpdateUrlResolver`, `UpdateCheckThrottle`) with full JVM unit tests.
- `app/src/main/java/io/leancast/app/`: `Settings.kt` (opt-in flag + last check timestamp), new `AppUpdater.kt`, `MainActivity.kt` (card + update modal + throttled on-open check), `AndroidManifest.xml` (`REQUEST_INSTALL_PACKAGES` + `FileProvider`), `res/xml/file_paths.xml`, `values/strings.xml` & `values-zh/strings.xml`.
- `.github/workflows/release.yml`: emit `latest.json` with SHA-256 and upload it to GitHub Releases.
- `README.md` & `README.zh-CN.md`: document opt-in update behavior while keeping the default zero-outbound-network guarantee clear.
- Risk: Low. Disabled by default; `ReceiverService` and DLNA/UPnP playback paths are untouched. In-place upgrades require consistent release signing (`KEYSTORE_BASE64` in CI).

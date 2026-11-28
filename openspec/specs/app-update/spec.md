# app-update Specification

## Purpose
TBD - created by archiving change add-opt-in-app-update. Update Purpose after archive.
## Requirements
### Requirement: Opt-in update toggle (default disabled)
The app SHALL provide a persisted boolean setting (`update_check_enabled`) that defaults to `false`. While disabled, the app MUST NOT initiate any outbound network request for update checking or APK downloading, whether automatically or via manual action.

#### Scenario: Fresh install makes zero update requests
- **WHEN** the app is installed and launched for the first time with default settings
- **THEN** `update_check_enabled` is `false` and no HTTP request is sent to any update manifest or release host

#### Scenario: Disabling toggle stops future checks
- **WHEN** the user toggles update checks from ON to OFF
- **THEN** subsequent launches and resumes of `MainActivity` perform no update requests and any cached update badge is cleared

### Requirement: Foreground-only throttled auto-check
When `update_check_enabled` is `true`, the app SHALL check for updates only when `MainActivity` is in the foreground, throttled to at most once every 6 hours for automatic checks. `ReceiverService` MUST NOT perform update checks or background polling.

#### Scenario: Auto-check skipped within throttle window
- **WHEN** `update_check_enabled` is `true` and a successful or attempted auto-check occurred less than 6 hours ago
- **THEN** reopening `MainActivity` does not issue another automatic network request

#### Scenario: Manual check bypasses throttle when enabled
- **WHEN** `update_check_enabled` is `true` and the user activates "Check Now" in the update modal
- **THEN** the app immediately fetches the latest release manifest regardless of the 6-hour auto-check interval

### Requirement: Static release manifest and SemVer comparison
The release CI workflow SHALL generate and attach a `latest.json` asset alongside `LeanCast-<version>.apk` on every GitHub Release containing `version`, `changelog`, and `assets["android-universal"]` (`file` and `sha256`). The app SHALL compare versions using semantic versioning (`major.minor.patch`) and report an update only when `remote > current`.

#### Scenario: Newer stable version detected
- **WHEN** installed version is `0.3.0` and `latest.json` reports `0.3.1` with a valid `assets["android-universal"]` entry
- **THEN** the updater reports an available update with version `0.3.1` and its changelog

#### Scenario: Same or older version reports up to date
- **WHEN** installed version is `0.3.1` and `latest.json` reports `0.3.1` or `0.3.0`
- **THEN** the updater reports the app is up to date and offers no install action

#### Scenario: Invalid manifest payload rejected
- **WHEN** an endpoint returns HTTP 200 with an HTML error page, malformed JSON, missing `assets["android-universal"]`, path-traversal APK name (e.g. `../evil.apk`), or non-64-hex `sha256`
- **THEN** the response is rejected as invalid and the updater tries the next candidate URL

### Requirement: First-party primary source with GitHub fallback
For both `latest.json` and APK downloads, the updater SHALL construct an ordered list of candidate URLs: first the first-party distribution endpoint (`https://dl.solocraft.app/leancast/latest.json` and `https://dl.solocraft.app/leancast/v<version>/<file>`), followed by the direct GitHub Releases URL (`https://github.com/addozhang/leancast/releases/latest/download/latest.json` and `https://github.com/addozhang/leancast/releases/download/v<version>/<file>`), trying each in order until one returns HTTP `200..299` with a valid payload.

#### Scenario: First-party endpoint fails and GitHub fallback succeeds
- **WHEN** `dl.solocraft.app` times out or returns HTTP 502 and the direct GitHub Releases URL returns HTTP 200 with a valid `latest.json`
- **THEN** the check succeeds using the GitHub fallback without surfacing an error

#### Scenario: All sources unreachable
- **WHEN** both `dl.solocraft.app` and the direct GitHub URL fail
- **THEN** the updater surfaces a localized network error in the update modal without crashing or affecting cast playback

### Requirement: Verified APK download and Android TV installer handoff
When the user confirms an upgrade, the app SHALL download the APK to `cacheDir/updates/`, report monotonically non-decreasing progress (`0..100`), verify the file's SHA-256 digest against `latest.json`, and launch the Android system package installer via `FileProvider` (`content://` URI).

#### Scenario: SHA-256 mismatch aborts install
- **WHEN** the downloaded APK's computed SHA-256 does not match `manifest.sha256`
- **THEN** the app deletes the temporary file, does NOT invoke the package installer, and displays an integrity verification error

#### Scenario: API 26+ unknown-sources permission required
- **WHEN** the user initiates install on Android 8.0+ without `canRequestPackageInstalls()` granted
- **THEN** the app opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for its package and displays guidance to allow installation and retry

#### Scenario: Verified APK launches system installer
- **WHEN** the downloaded APK passes SHA-256 verification and install permission is granted
- **THEN** the app starts `Intent.ACTION_VIEW` with the `FileProvider` `content://` URI, MIME type `application/vnd.android.package-archive`, and `FLAG_GRANT_READ_URI_PERMISSION`

#### Scenario: Restricted TV firmware lacks package installer
- **WHEN** starting the package installer throws `ActivityNotFoundException` or `SecurityException`
- **THEN** the app catches the exception and shows a fallback message with the manual download URL

### Requirement: 10-foot TV update modal and bilingual copy
Activating the Updates action card SHALL open a dark TV modal navigable solely by D-pad containing: the opt-in toggle, current vs. latest version status, release notes when available, progress bar during download/verification, and primary/close action buttons. All strings SHALL be provided in pure Simplified Chinese (`values-zh/strings.xml`) and pure English (`values/strings.xml`).

#### Scenario: D-pad navigation in update modal
- **WHEN** the user opens the update modal with a TV remote
- **THEN** focus lands on the primary interactive control and every toggle and button is reachable and visually highlighted with D-pad navigation


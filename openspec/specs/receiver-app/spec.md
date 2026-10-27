# receiver-app Specification

## Purpose
TBD - created by archiving change add-dlna-cast-receiver. Update Purpose after archive.
## Requirements
### Requirement: Foreground receiver service
The app SHALL host SSDP discovery, the UPnP control server, and the playback controller inside a single Android foreground service. While running it SHALL hold a `WifiManager.MulticastLock` and release it on destroy. The service SHALL restart lazily: launching the app or (if enabled) boot completes starts it.

#### Scenario: Service lifecycle
- **WHEN** the receiver service is created and then destroyed
- **THEN** the multicast lock is acquired after start and released after stop, and the SSDP/HTTP components stop with it

### Requirement: Configurable friendly name
The app SHALL persist a receiver friendly name that defaults to the application name (`@string/app_name`, i.e. `LeanCast`). The name is injected into the UPnP device description `friendlyName` and the launcher UI, and can be customized by the user at any time.

#### Scenario: Default name is LeanCast
- **WHEN** the app runs for the first time with no custom name saved
- **THEN** the UPnP description and UI advertise `friendlyName` as `LeanCast`

#### Scenario: Rename takes effect
- **WHEN** the user changes the friendly name in settings and confirms
- **THEN** the receiver service restarts and subsequent device description responses carry the new name

### Requirement: Optional boot autostart
The app SHALL offer a boot-autostart toggle, default off. When enabled and boot completes, the receiver service starts without opening the UI.

#### Scenario: Boot receiver honors toggle
- **WHEN** the system boots with autostart disabled
- **THEN** the receiver service is not started

### Requirement: Status UI
The launcher activity SHALL display the receiver state: service running, current friendly name, LAN address and port, and the current transport state with the item title when casting. It provides a path to settings and to stop/start the service.

#### Scenario: Idle status
- **WHEN** the app opens with the service running and no active cast
- **THEN** the UI shows the running indicator, friendly name, and an idle transport state

### Requirement: Lean permission and network posture
The app SHALL request only `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, and `POST_NOTIFICATIONS` (API 33+) permissions. It MUST make no network requests beyond the LAN cast protocols; no analytics, ads, or update checks are permitted.

#### Scenario: No external calls
- **WHEN** the app manifest and merged configuration are inspected
- **THEN** no analytics/ad SDK or non-LAN endpoint is present

### Requirement: Pure-Kotlin APK
The app SHALL build as a pure Kotlin/Java APK with no bundled native libraries, so a single artifact runs on `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` devices.

#### Scenario: ABI independence
- **WHEN** the release APK is inspected
- **THEN** it contains no `lib/` native directories

### Requirement: Launcher icon
The app SHALL ship a launcher icon: an adaptive icon (API 26+) plus raster fallbacks for API 21–25, rendered from generated artwork (screen + cast-waves motif, no embedded text or third-party marks).

#### Scenario: Icon resolves on all supported APIs
- **WHEN** the launcher resolves the app icon on API 21 and on API 26+
- **THEN** a valid icon resource resolves in both cases (legacy raster below 26, adaptive icon at 26+)

### Requirement: 10-foot two-column TV dashboard UI
The launcher activity SHALL present a 10-foot Android TV layout with overscan-safe margins: a left hero status card (brand header, live status badge, device friendly name in large typography, LAN endpoint + protocol pills, active media title when casting, and 3-step casting instructions) and a right action column of focusable TV cards (rename device, boot autostart toggle, restart/stop service). Focusing any action card with the D-pad SHALL highlight its border and apply a subtle scale-up animation.

#### Scenario: D-pad focus highlights action card
- **WHEN** the user navigates to an action card on the right column using the TV remote D-pad
- **THEN** the focused card renders a high-contrast accent border and scales up relative to unfocused cards

### Requirement: Switchable UI language (Chinese / English)
The app SHALL support switching the UI language between Simplified Chinese (`zh`) and English (`en`) via a focusable action card on the dashboard. Each language SHALL display pure, single-language strings (without mixed bilingual concatenation), persisted across restarts.

#### Scenario: Toggle between Chinese and English
- **WHEN** the user activates the language switch card on the dashboard
- **THEN** the persisted language toggles between `zh` and `en` and all UI strings render in the selected language only


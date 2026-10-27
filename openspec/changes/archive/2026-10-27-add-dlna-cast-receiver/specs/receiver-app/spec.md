# Spec Delta: receiver-app

## ADDED Requirements

### Requirement: Foreground receiver service
The app SHALL host SSDP discovery, the UPnP control server, and the playback controller inside a single Android foreground service. While running it SHALL hold a `WifiManager.MulticastLock` and release it on destroy. The service SHALL restart lazily: launching the app or (if enabled) boot completes starts it.

#### Scenario: Service lifecycle
- **WHEN** the receiver service is created and then destroyed
- **THEN** the multicast lock is acquired after start and released after stop, and the SSDP/HTTP components stop with it

### Requirement: Configurable friendly name
The app SHALL persist a receiver friendly name that defaults to the device model name (`Build.MODEL`). The name is injected into the SSDP description location metadata, the UPnP device description `friendlyName`, and the launcher UI. No product-specific name is hardcoded anywhere in the app resources' runtime strings (placeholder text in resources is permitted).

#### Scenario: Default name
- **WHEN** the app runs for the first time on a device whose model is `AFTANNA0`
- **THEN** the UPnP description advertises `friendlyName` as `AFTANNA0`

#### Scenario: Rename takes effect
- **WHEN** the user changes the friendly name in settings and the service is restarted
- **THEN** subsequent device description responses carry the new name

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

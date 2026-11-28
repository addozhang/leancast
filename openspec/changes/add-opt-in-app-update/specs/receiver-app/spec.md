# Spec Delta: receiver-app

## MODIFIED Requirements

### Requirement: Lean permission and network posture
The app SHALL request only `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS` (API 33+), `SYSTEM_ALERT_WINDOW`, `USE_FULL_SCREEN_INTENT`, and `REQUEST_INSTALL_PACKAGES` (API 26+ for opt-in self-upgrade) permissions. By default (`update_check_enabled = false`), the app MUST make no network requests beyond LAN cast protocols and user-cast media streams; no analytics or ads are permitted, and update checks/downloads SHALL occur only after the user explicitly enables the opt-in update toggle.

#### Scenario: Default posture makes no external app calls
- **WHEN** the app runs with default settings (`update_check_enabled = false`) and the manifest/configuration is inspected
- **THEN** no analytics/ad SDK is present and no outbound update check request is initiated

### Requirement: 10-foot two-column TV dashboard UI
The launcher activity SHALL present a 10-foot Android TV layout with overscan-safe margins: a left hero status card (brand header with version badge and optional new-version indicator, live status badge, device friendly name in large typography, LAN endpoint + protocol summary, active media title when casting, and 3-step casting instructions) and a right action column of focusable TV cards (rename device, boot autostart toggle, restart/stop service, language switch, and opt-in app updates). Focusing any action card with the D-pad SHALL highlight its border and apply a subtle scale-up animation.

#### Scenario: D-pad focus highlights action card
- **WHEN** the user navigates to an action card on the right column using the TV remote D-pad
- **THEN** the focused card renders a high-contrast accent border and scales up relative to unfocused cards

#### Scenario: Updates card reflects opt-in and available-update states
- **WHEN** `update_check_enabled` is `false`
- **THEN** the Updates card displays an "Off" pill; when enabled and a newer version is discovered, both the Updates card pill and the left hero version area indicate the available version

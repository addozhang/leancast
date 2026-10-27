# Spec Delta: receiver-app

## ADDED Requirements

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

## MODIFIED Requirements

### Requirement: Configurable friendly name
The app SHALL persist a receiver friendly name that defaults to the application name (`@string/app_name`, i.e. `LeanCast`). The name is injected into the UPnP device description `friendlyName` and the launcher UI, and can be customized by the user at any time.

#### Scenario: Default name is LeanCast
- **WHEN** the app runs for the first time with no custom name saved
- **THEN** the UPnP description and UI advertise `friendlyName` as `LeanCast`

#### Scenario: Rename takes effect
- **WHEN** the user changes the friendly name in settings and confirms
- **THEN** the receiver service restarts and subsequent device description responses carry the new name




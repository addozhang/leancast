# Spec Delta: receiver-app

## ADDED Requirements

### Requirement: Launcher icon
The app SHALL ship a launcher icon: an adaptive icon (API 26+) plus raster fallbacks for API 21–25, rendered from generated artwork (screen + cast-waves motif, no embedded text or third-party marks).

#### Scenario: Icon resolves on all supported APIs
- **WHEN** the launcher resolves the app icon on API 21 and on API 26+
- **THEN** a valid icon resource resolves in both cases (legacy raster below 26, adaptive icon at 26+)

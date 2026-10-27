# device-compat-docs Specification

## Purpose
TBD - created by archiving change document-device-compatibility. Update Purpose after archive.
## Requirements
### Requirement: Device compatibility matrix and report flow
The project SHALL maintain a tiered device compatibility matrix in the README
(verified / expected / experimental / unsupported) consistent with the app's
technical baseline (`minSdk 21`, pure-Kotlin ABI-independent APK, platform
`MediaCodec` decoding), and SHALL provide a structured GitHub issue template
for community device compatibility reports covering device model, OS family,
Android version, app version, sender app, and result.

#### Scenario: Matrix present and consistent with baseline
- **WHEN** the README and issue templates are inspected
- **THEN** a tiered compatibility matrix exists matching the documented
  technical baseline, and a device-compatibility issue form is available

#### Scenario: Report captures actionable fields
- **WHEN** a compatibility report is filed via the template
- **THEN** it includes the device model, OS family, Android version,
  LeanCast version, sender app, and result classification


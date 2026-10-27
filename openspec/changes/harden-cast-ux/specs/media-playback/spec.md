# Spec Delta: media-playback

## ADDED Requirements

### Requirement: On-screen playback HUD
While the player surface is foreground, the app SHALL display a HUD consisting of: a seekbar reflecting live position and duration with elapsed/total time text, a play/pause affordance, and a transient center indicator that appears for a short window after pause, resume, or seek key presses and then fades. The HUD SHALL be operable with the D-pad (focusable seekbar and play/pause control) and SHALL auto-hide during uninterrupted playback.

#### Scenario: HUD appears during playback
- **WHEN** media is playing and the user presses any D-pad key
- **THEN** the HUD (seekbar + times + play/pause) becomes visible and auto-hides after a short timeout of uninterrupted playback

#### Scenario: Center indicator signals pause
- **WHEN** the user presses the pause/resume key while playing
- **THEN** a centered indicator reflects the new state (paused or playing) for a short window before fading, in addition to the persistent HUD while paused

### Requirement: Compatible media fetching
The playback layer SHALL fetch media over HTTP with a browser-like `User-Agent` and an `Accept: */*` header, and SHALL follow cross-protocol redirects (HTTP ↔ HTTPS). The header set SHALL be produced by a pure, unit-testable helper so senders with CDN header checks (bilibili, Youku) are served.

#### Scenario: Headers attached to every fetch
- **WHEN** media HTTP headers are computed for any `http://` or `https://` cast URL
- **THEN** the set contains a desktop-browser `User-Agent` string and `Accept: */*`

#### Scenario: Cross-protocol redirects followed
- **WHEN** a cast URL responds with a redirect to the other protocol
- **THEN** the data source follows it transparently

### Requirement: Field-diagnosable playback errors
When the player reports a fatal error, the app SHALL log the error code name and the failing cast URI locally (logcat) so compatibility issues can be diagnosed from a device.

#### Scenario: Error logged with context
- **WHEN** a playback error occurs
- **THEN** a log record contains the ExoPlayer error code name and the URI being played

## ADDED Requirements

### Requirement: Accelerated seeking on key hold
Holding the seek keys SHALL increase the jump distance in steps: the first presses move the base step (10 s), and as key repeats accumulate the per-event jump SHALL escalate through 30 s, 60 s, 120 s, up to 300 s, where it caps. Dedicated `MEDIA_FAST_FORWARD`/`MEDIA_REWIND` keys (sent by some remotes on long-press) SHALL seek with the same escalating policy. The step schedule SHALL be a pure function of repeat count so it is unit-testable.

#### Scenario: Escalating steps while held
- **WHEN** seek key events arrive with repeat counts 0, 4, 5, 9, 10, 14, 15, 30
- **THEN** the per-event jumps are 10s, 10s, 30s, 30s, 60s, 60s, 120s, 300s respectively

#### Scenario: Fast-forward and rewind keycodes supported
- **WHEN** KEYCODE_MEDIA_FAST_FORWARD or KEYCODE_MEDIA_REWIND is pressed
- **THEN** the player seeks forward/backward using the same escalating schedule

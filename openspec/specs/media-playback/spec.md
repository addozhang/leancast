# media-playback Specification

## Purpose
TBD - created by archiving change add-dlna-cast-receiver. Update Purpose after archive.
## Requirements
### Requirement: Play cast URLs with hardware decoding
The playback layer SHALL play a stored cast URI through the platform media pipeline (Media3/ExoPlayer with default MediaCodec renderers), supporting progressive MP4 (`video/mp4`), HLS manifests (`application/vnd.apple.mpegurl`, `.m3u8`), and HTTP-FLV best effort, following HTTP↔HTTPS redirects transparently. Playback MUST NOT require network permissions beyond Internet.

#### Scenario: URI handed to the player
- **WHEN** the transport session enters `Play` for URI `https://example.com/v.m3u8`
- **THEN** the player facade receives a prepare/play command with that exact URI and standard HLS MIME hint

### Requirement: Transport state machine
The playback layer SHALL expose the states `NO_MEDIA_PRESENT`, `TRANSITIONING`, `PLAYING`, `PAUSED_PLAYBACK`, `STOPPED` with transitions: set-URI→`NO_MEDIA_PRESENT`; play→`TRANSITIONING`→`PLAYING` on prepared or error→`STOPPED`; pause→`PAUSED_PLAYBACK`; stop→`STOPPED`. Illegal commands (e.g. pause with no media) are ignored without error.

#### Scenario: Full happy path
- **WHEN** the sequence set-URI → play → prepared → pause → play → stop is driven
- **THEN** observed states are `NO_MEDIA_PRESENT`, `TRANSITIONING`, `PLAYING`, `PAUSED_PLAYBACK`, `PLAYING`, `STOPPED` in order

#### Scenario: Playback error recovers receiver
- **WHEN** the player reports a fatal error during `TRANSITIONING`
- **THEN** transport state becomes `STOPPED`, the receiver keeps serving control actions, and a subsequent set-URI/play cycle succeeds

### Requirement: Track position and duration
While playing, the layer SHALL track a monotonic playback position; once the media reports its duration, both SHALL be readable in milliseconds. Seek commands move the position; stop resets it to zero.

#### Scenario: Position advances during play
- **WHEN** the state is `PLAYING` and 1,500 ms elapses with no seek
- **THEN** reported position increases by approximately 1,500 ms (± tolerance for clock drift)

### Requirement: Time unit conversion
The layer SHALL convert between UPnP `H:MM:SS` strings and milliseconds in both directions, truncating fractional seconds when parsing, and zero-padding hours may exceed one digit.

#### Scenario: String to milliseconds
- **WHEN** `"0:01:30.500"`, `"1:31:32"`, and `"10:00:00"` are parsed
- **THEN** the results are 90,500 ms, 5,492,000 ms, and 36,000,000 ms respectively

#### Scenario: Milliseconds to string
- **WHEN** 5,492,000 ms is rendered
- **THEN** the string is `1:31:32`

### Requirement: Map volume to the device stream
The layer SHALL map volume `0..100` to the device `STREAM_MUSIC` percentage and report the effective value back to the UPnP layer.

#### Scenario: Volume mapping
- **WHEN** volume 37 of 100 is applied on a device stream whose max is 15
- **THEN** the device receives volume 6 (round(37*15/100) = round(5.55)) and queried volume reports 37

### Requirement: TV remote key handling
While the player surface is foreground, `DPAD_CENTER` SHALL toggle pause/resume, `DPAD_LEFT`/`DPAD_RIGHT` SHALL seek −10 s/+10 s (bounded by media), and `BACK` SHALL stop playback and leave the player screen.

#### Scenario: Center toggles pause
- **WHEN** `DPAD_CENTER` is pressed twice while playing
- **THEN** the transport toggles to `PAUSED_PLAYBACK` and back to `PLAYING`

### Requirement: Accelerated seeking on key hold
Holding the seek keys SHALL increase the jump distance in steps: the first presses move the base step (10 s), and as key repeats accumulate the per-event jump SHALL escalate through 30 s, 60 s, 120 s, up to 300 s, where it caps. Dedicated `MEDIA_FAST_FORWARD`/`MEDIA_REWIND` keys (sent by some remotes on long-press) SHALL seek with the same escalating policy. The step schedule SHALL be a pure function of repeat count so it is unit-testable.

#### Scenario: Escalating steps while held
- **WHEN** seek key events arrive with repeat counts 0, 4, 5, 9, 10, 14, 15, 30
- **THEN** the per-event jumps are 10s, 10s, 30s, 30s, 60s, 60s, 120s, 300s respectively

#### Scenario: Fast-forward and rewind keycodes supported
- **WHEN** KEYCODE_MEDIA_FAST_FORWARD or KEYCODE_MEDIA_REWIND is pressed
- **THEN** the player seeks forward/backward using the same escalating schedule


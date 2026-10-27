# upnp-renderer Specification

## Purpose
TBD - created by archiving change add-dlna-cast-receiver. Update Purpose after archive.
## Requirements
### Requirement: Serve device description
The renderer SHALL serve a UPnP `MediaRenderer:1` device description XML at `/desc.xml` containing `UDN` (stable UUID persisted across restarts), `friendlyName` (from receiver configuration, defaulting to the device model name — never a hardcoded product name), `manufacturer`/`modelName` (from configuration), and `<serviceList>` entries for `AVTransport:1` (event sub URL `/evt/avt`), `RenderingControl:1` (`/evt/rcs`), and `ConnectionManager:1` (`/evt/cmn`), each with `SCPDURL`, `controlURL`, and `eventSubURL` that resolve against the served location.

#### Scenario: Description fetched
- **WHEN** `GET /desc.xml` is served
- **THEN** the body is well-formed XML declaring `MediaRenderer:1`, lists all three services, and the friendly name equals the configured receiver name

#### Scenario: Stable UDN across restarts
- **WHEN** the description is requested twice with a renderer restart in between
- **THEN** both responses contain the same `UDN` UUID value

### Requirement: Serve service control protocol descriptions
The renderer SHALL serve a valid SCPD XML for each advertised service at its `SCPDURL`, including every action and state variable the control endpoint dispatches.

#### Scenario: SCPD per service
- **WHEN** `GET` is performed on each service's `SCPDURL`
- **THEN** each response is well-formed SCPD XML whose action names match the actions accepted by that service's control endpoint

### Requirement: Dispatch SOAP control actions
The renderer SHALL accept `POST` requests on each service's `controlURL`, read the `SOAPACTION` header (falling back to the body's `Body` element name when the header is missing), and dispatch to the matching action handler. Unknown actions SHALL return HTTP `401` with a `UPnPError` fault body of code `401` (`Invalid Action`). Malformed XML SHALL return HTTP `400` with fault code `402`.

#### Scenario: SOAPACTION header routing
- **WHEN** a POST with `SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#Play"` and a Play body arrives
- **THEN** the AVTransport Play handler is invoked with the parsed arguments

#### Scenario: Headerless fallback
- **WHEN** a POST omits the `SOAPACTION` header but its body contains `u:Play`
- **THEN** the action is still resolved and dispatched from the body element name

#### Scenario: Unknown action
- **WHEN** a POST requests an action not defined by the service
- **THEN** the response is HTTP `401` containing `errorCode 401` and `errorDescription Invalid Action`

### Requirement: Handle SetAVTransportURI
`SetAVTransportURI(InstanceID=0, CurrentURI, CurrentURIMetaData)` SHALL stop any current media, store the new URI and metadata, and — when the URI is non-empty — **immediately begin playback** (state `TRANSITIONING`), because senders like bilibili set the URI and poll state without ever sending `Play`. Empty `CurrentURI` SHALL clear the media and report `NO_MEDIA_PRESENT`. An explicit `Play` arriving after auto-start SHALL be idempotent (no media restart).

#### Scenario: Auto-start on set
- **WHEN** `SetAVTransportURI` is called with `CurrentURI="http://x/v.m3u8"` and no `Play` follows
- **THEN** the transport enters `TRANSITIONING` and playback begins

#### Scenario: Explicit Play after auto-start is idempotent
- **WHEN** `Play` arrives while already `TRANSITIONING` or `PLAYING`
- **THEN** no re-prepare occurs and the response succeeds

### Requirement: Handle transport commands
The renderer SHALL implement `Play` (start/resume from stored URI; playback begins asynchronously via state `TRANSITIONING`), `Pause`, and `Stop` with empty success bodies, updating the transport state machine accordingly (`PLAYING`, `PAUSED_PLAYBACK`, `STOPPED`).

#### Scenario: Play after SetAVTransportURI
- **WHEN** `Play` is invoked on a session holding a URI in state `NO_MEDIA_PRESENT`
- **THEN** playback is requested for that URI and the state becomes `TRANSITIONING`

#### Scenario: Stop resets position
- **WHEN** `Stop` is invoked while playing
- **THEN** playback halts, position reporting resets to zero, and state becomes `STOPPED`

### Requirement: Handle Seek
`Seek(InstanceID=0, Unit, Target)` SHALL support `Unit=REL_TIME` with `Target` formatted `H:MM:SS` or `H:MM:SS.fraction` (fraction interpreted as fractional seconds) and also accept `ABS_TIME`/`TRACK_NR`-style values it cannot honor by ignoring them gracefully. On success the playback position moves to the requested time.

#### Scenario: Seek to a timestamp
- **WHEN** `Seek` is called with `Unit="REL_TIME"`, `Target="0:01:30.500"`
- **THEN** the requested position is 90,500 ms and a success response is returned

#### Scenario: Unsupported unit ignored
- **WHEN** `Seek` is called with `Unit="TRACK_NR"`
- **THEN** a success response is returned without changing position

### Requirement: Report position and transport info
`GetPositionInfo` SHALL return `RelTime`/`AbsTime` as the current playback position and `TrackDuration` as the media duration, both rendered `H:MM:SS` with zero-padding, plus `TrackMetaData` echoing the stored DIDL-Lite for the current item. `GetTransportInfo` SHALL return `CurrentTransportState` reflecting the state machine, with `TransportStatus=OK` and `TransportPlaySpeed=1`. `GetMediaInfo` SHALL return `NrTracks=1`, the duration, and the current URI.

#### Scenario: Position formatting
- **WHEN** position info is rendered for 5,492 seconds of a 7,200-second item
- **THEN** `RelTime="1:31:32"` and `TrackDuration="2:00:00"`

#### Scenario: Unprepared media reports zeros
- **WHEN** `GetPositionInfo` is called before the media is prepared
- **THEN** `RelTime` and `TrackDuration` are both `0:00:00` and `TrackMetaData` is `NOT_IMPLEMENTED`

### Requirement: Handle volume and mute
`RenderingControl` SHALL implement `SetVolume(DesiredVolume 0..100, Channel)` and `GetVolume` for `Channel=Master` (other channels ignored with success), and `SetMute`/`GetMute` mapped to the same master volume domain. Out-of-range volume values are clamped.

#### Scenario: Set and get volume
- **WHEN** `SetVolume` is called with `DesiredVolume=37` then `GetVolume` is called
- **THEN** `CurrentVolume=37` is returned

#### Scenario: Clamping
- **WHEN** `SetVolume` is called with `DesiredVolume=150`
- **THEN** the stored volume becomes `100` and the response succeeds

### Requirement: Answer GetProtocolInfo
`ConnectionManager GetProtocolInfo` SHALL return a `Sink` protocol list enumerating at minimum `http-get:*:video/mp4:*`, `http-get:*:application/vnd.apple.mpegurl:*`, and `http-get:*:audio/mpeg:*`, and an empty `Source` list.

#### Scenario: Protocol info lists supported sinks
- **WHEN** `GetProtocolInfo` is called
- **THEN** the Sink list contains the HTTP video/mp4 and HLS MIME types

### Requirement: Accept GENA subscriptions
The renderer SHALL answer `SUBSCRIBE` requests on each `eventSubURL` with HTTP `200`, a generated `SID: uuid:...`, and a `TIMEOUT: Second-1800` header; `UNSUBSCRIBE` with a known `SID` returns `200`. Callback delivery of events is not required in this version.

#### Scenario: Subscribe and unsubscribe
- **WHEN** `SUBSCRIBE` with a callback header is sent to `/evt/avt`, followed by `UNSUBSCRIBE` with the returned `SID`
- **THEN** both responses are `200` and the SID is a valid UUID

### Requirement: Bind control plane to the LAN address
The renderer SHALL bind its HTTP server to a wildcard port and compose `LOCATION`, `controlURL`, and `SCPDURL` absolute URLs from the active non-loopback IPv4 address, never `127.0.0.1`.

#### Scenario: Location uses LAN address
- **WHEN** the renderer owns address `192.168.31.47` and serves on port 49152
- **THEN** SSDP `LOCATION` and the description's control URLs all start with `http://192.168.31.47:49152/`


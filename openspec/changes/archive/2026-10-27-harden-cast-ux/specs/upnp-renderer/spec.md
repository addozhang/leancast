# Spec Delta: upnp-renderer

## MODIFIED Requirements

### Requirement: Handle SetAVTransportURI
`SetAVTransportURI(InstanceID=0, CurrentURI, CurrentURIMetaData)` SHALL stop any current media, store the new URI and metadata, and — when the URI is non-empty — **immediately begin playback** (state `TRANSITIONING`), because senders like bilibili set the URI and poll state without ever sending `Play`. Empty `CurrentURI` SHALL clear the media and report `NO_MEDIA_PRESENT`. An explicit `Play` arriving after auto-start SHALL be idempotent (no media restart).

#### Scenario: Auto-start on set
- **WHEN** `SetAVTransportURI` is called with `CurrentURI="http://x/v.m3u8"` and no `Play` follows
- **THEN** the transport enters `TRANSITIONING` and playback begins

#### Scenario: Explicit Play after auto-start is idempotent
- **WHEN** `Play` arrives while already `TRANSITIONING` or `PLAYING`
- **THEN** no re-prepare occurs and the response succeeds

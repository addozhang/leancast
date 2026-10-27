# Spec Delta: ssdp-discovery

## ADDED Requirements

### Requirement: Join SSDP multicast group
The discovery layer SHALL join the SSDP multicast group `239.255.255.250:1900` on all eligible IPv4 network interfaces with `SO_REUSEADDR`/`SO_REUSEPORT` so multiple discovery implementations can coexist on the device.

#### Scenario: Listener starts
- **WHEN** the receiver service starts and the UDP listener is bound
- **THEN** the socket is bound to port `1900` with address reuse enabled, and the multicast group is joined

### Requirement: Parse M-SEARCH requests
The discovery layer SHALL parse raw SSDP datagrams into typed messages, extracting `ST`, `MAN`, `MX`, and originating address. Datagrams missing required headers or not starting with `M-SEARCH` SHALL be ignored without error.

#### Scenario: Valid M-SEARCH parsed
- **WHEN** a datagram `M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: "ssdp:discover"\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\nMX: 3\r\n\r\n` is parsed
- **THEN** the result is an M-SEARCH with `ST=urn:schemas-upnp-org:device:MediaRenderer:1`, `MX=3`, and a well-formed sender address

#### Scenario: Malformed datagram ignored
- **WHEN** a datagram arrives that is not HTTP-shaped, or an `M-SEARCH` missing `ST` or `MAN`
- **THEN** parsing yields an ignorable result and no exception is thrown

### Requirement: Respond to M-SEARCH for supported targets
The discovery layer SHALL send unicast `HTTP/1.1 200 OK` responses to M-SEARCHes whose `ST` is `ssdp:all`, `upnp:rootdevice`, `urn:schemas-upnp-org:device:MediaRenderer:1`, or any of the receiver's service types (`AVTransport`, `RenderingControl`, `ConnectionManager` with `:1` versions). Each response SHALL include headers `CACHE-CONTROL: max-age=`, `DATE`, `EXT`, `LOCATION`, `SERVER`, `ST`, `USN`, and the response for `ssdp:all` SHALL contain one message per target type.

#### Scenario: M-SEARCH for MediaRenderer device type
- **WHEN** an M-SEARCH with `ST: urn:schemas-upnp-org:device:MediaRenderer:1` is handled
- **THEN** exactly one response is produced with `ST` echoing the search target, `USN = <device-uuid>::urn:schemas-upnp-org:device:MediaRenderer:1`, and `LOCATION` pointing at an HTTP URL on the device LAN address

#### Scenario: M-SEARCH ssdp:all
- **WHEN** an M-SEARCH with `ST: ssdp:all` is handled
- **THEN** responses are produced for the root device, the `MediaRenderer` device type, and each advertised service type

#### Scenario: Unsupported search target
- **WHEN** an M-SEARCH with `ST: urn:schemas-upnp-org:device:MediaServer:1` is handled
- **THEN** no response is produced

### Requirement: Respect MX response delay bound
For an M-SEARCH with `MX` seconds, the discovery layer SHALL compute a per-response delay uniformly distributed in `[0, min(MX, 5)]` seconds; if `MX` is absent or unparseable it SHALL default to `1`.

#### Scenario: MX honored and capped
- **WHEN** delays are computed for `MX=2`, `MX=10`, and a message without `MX`
- **THEN** every delay for `MX=2` lies in `[0, 2]`, every delay for `MX=10` lies in `[0, 5]`, and the missing-`MX` delay lies in `[0, 1]`

### Requirement: Advertise aliveness on startup
The discovery layer SHALL multicast `NOTIFY ssdp:alive` messages for the root device and every advertised service on startup and periodically (max-age/2 + jitter), each containing `NT`, `NTS: ssdp:alive`, `USN`, `LOCATION`, `CACHE-CONTROL`, and `SERVER` headers.

#### Scenario: Alive advertisement content
- **WHEN** the alive set for a device UUID is rendered
- **THEN** it contains one NOTIFY per target (root device, MediaRenderer type, services), each with `NTS: ssdp:alive` and matching `USN` composition

### Requirement: Announce departure on shutdown
The discovery layer SHALL multicast `NOTIFY ssdp:byebye` for every advertised target on shutdown.

#### Scenario: Byebye set mirrors alive set
- **WHEN** the byebye set for a device UUID is rendered
- **THEN** it contains the same target set as aliveness advertising, each with `NTS: ssdp:byebye` and no `LOCATION` requirement

### Requirement: Discovery identity is configurable
The discovery layer SHALL take its device UUID, location URL, and server token as inputs; it MUST NOT hardcode any product or device name in message bodies.

#### Scenario: Identity injected
- **WHEN** discovery is constructed with a given UUID and location URL
- **THEN** all rendered messages use exactly those values

#!/usr/bin/env python3
"""Scripted UPnP control point: discovers the LeanCast receiver on the LAN and
drives it through a full cast cycle. Used for E2E verification from a dev box.

Usage: python3 e2e_control_point.py [media_url]
"""
import re
import socket
import sys
import time
import urllib.request

MULTICAST = ("239.255.255.250", 1900)
TIMEOUT = 5.0

M_SEARCH = "\r\n".join([
    "M-SEARCH * HTTP/1.1",
    "HOST: 239.255.255.250:1900",
    'MAN: "ssdp:discover"',
    "MX: 3",
    "ST: urn:schemas-upnp-org:device:MediaRenderer:1",
    "",
    "",
]).encode()

AVT = "urn:schemas-upnp-org:service:AVTransport:1"


def soap_envelope(action: str, args_xml: str) -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"'
        ' s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        f"<s:Body><u:{action} xmlns:u=\"{AVT}\">{args_xml}</u:{action}></s:Body></s:Envelope>"
    )


def control(location: str, action: str, args_xml: str) -> tuple[int, str]:
    body = soap_envelope(action, args_xml).encode()
    req = urllib.request.Request(
        location.rsplit("/", 1)[0] + "/ctl/avt",
        data=body,
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPACTION": f'"{AVT}#{action}"',
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def find(tag: str, xml: str) -> str:
    m = re.search(f"<{tag}>(.*?)</{tag}>", xml, re.S)
    return m.group(1) if m else ""


def main() -> int:
    media = sys.argv[1] if len(sys.argv) > 1 else \
        "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(TIMEOUT)
    sock.sendto(M_SEARCH, MULTICAST)

    location = None
    deadline = time.time() + TIMEOUT
    while time.time() < deadline:
        try:
            data, addr = sock.recvfrom(4096)
        except socket.timeout:
            break
        text = data.decode(errors="replace")
        m = re.search(r"LOCATION:\s*(\S+)", text, re.I)
        if m and "desc.xml" in m.group(1):
            location = m.group(1)
            print(f"[1] M-SEARCH answered by {addr[0]}: LOCATION={location}")
            break
    if not location:
        print("[1] FAIL: no MediaRenderer responded to M-SEARCH")
        return 1

    with urllib.request.urlopen(location, timeout=5) as resp:
        desc = resp.read().decode()
    name = find("friendlyName", desc)
    udn = find("UDN", desc)
    services = len(re.findall("<service>", desc))
    print(f"[2] desc.xml OK: friendlyName={name!r} UDN={udn} services={services}")
    assert services == 3, "expected 3 services"

    scpd_url = location.rsplit("/", 1)[0] + "/scpd/avt.xml"
    with urllib.request.urlopen(scpd_url, timeout=5) as resp:
        scpd = resp.read().decode()
    actions = re.findall(r"<name>(\w+)</name>", scpd)
    print(f"[3] AVT SCPD OK: {len(actions)} names incl. Play={('Play' in actions)}")
    assert "Play" in actions and "SetAVTransportURI" in actions

    didl = (
        '<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"'
        ' xmlns:dc="http://purl.org/dc/elements/1.1/">'
        "<item><dc:title>Big Buck Bunny (E2E)</dc:title></item></DIDL-Lite>"
    )
    status, body = control(location, "SetAVTransportURI",
                           f"<InstanceID>0</InstanceID><CurrentURI>{media}</CurrentURI>"
                           f"<CurrentURIMetaData>{didl.replace(chr(38), chr(38)+chr(35)+chr(51)+chr(56)+chr(59)).replace(chr(60), chr(38)+chr(108)+chr(116)+chr(59)).replace(chr(62), chr(38)+chr(103)+chr(116)+chr(59))}</CurrentURIMetaData>")
    print(f"[4] SetAVTransportURI -> HTTP {status}")
    assert status == 200, body

    status, body = control(location, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    print(f"[5] Play -> HTTP {status}")
    assert status == 200

    # TVs are slow to cold-start the player activity + decoder; poll until live.
    rel1, dur = "0:00:00", "0:00:00"
    for _ in range(10):
        time.sleep(2)
        status, body = control(location, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        rel1 = find("RelTime", body)
        dur = find("TrackDuration", body)
        if re.match(r"^\d+:\d\d:\d\d$", rel1) and rel1 != "0:00:00" and dur != "0:00:00":
            break
    print(f"[6] GetPositionInfo#1 -> RelTime={rel1} TrackDuration={dur}")
    assert re.match(r"^\d+:\d\d:\d\d$", rel1) and rel1 != "0:00:00", body
    assert dur != "0:00:00", "duration unknown after prepare"

    status, body = control(location, "Seek",
                           "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:01:00</Target>")
    print(f"[7] Seek 0:01:00 -> HTTP {status}")
    assert status == 200
    time.sleep(2)
    status, body = control(location, "GetPositionInfo", "<InstanceID>0</InstanceID>")
    print(f"[8] GetPositionInfo#2 -> RelTime={find('RelTime', body)} (post-seek)")
    assert find("RelTime", body).startswith("0:00:5") or find("RelTime", body).startswith("0:01:0")

    status, body = control(location, "Pause", "<InstanceID>0</InstanceID>")
    status, body = control(location, "GetTransportInfo", "<InstanceID>0</InstanceID>")
    state = find("CurrentTransportState", body)
    print(f"[9] Pause -> GetTransportInfo={state}")
    assert state == "PAUSED_PLAYBACK", body

    status, body = control(location, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
    status, body = control(location, "GetTransportInfo", "<InstanceID>0</InstanceID>")
    state = find("CurrentTransportState", body)
    print(f"[10] Resume -> GetTransportInfo={state}")
    assert state == "PLAYING", body

    status, body = control(location, "Stop", "<InstanceID>0</InstanceID>")
    status, body = control(location, "GetTransportInfo", "<InstanceID>0</InstanceID>")
    state = find("CurrentTransportState", body)
    print(f"[11] Stop -> GetTransportInfo={state}")
    assert state == "STOPPED", body

    status, body = control(location, "NonsenseAction", "<InstanceID>0</InstanceID>")
    print(f"[12] Unknown action -> HTTP {status} (expect 401)")
    assert status == 401 and "401" in find("errorCode", body)

    print("E2E PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Comprehensive real-device E2E verification suite for LeanCast.

Generates local H.264/AAC MP4, HLS VOD (.m3u8), and simulated HLS Live (.m3u8)
streams via ffmpeg, serves them over the LAN with HTTP Range support, and
exercises every UPnP/DLNA, GENA, HTTP, player, and (when --adb is given) TV
remote / background-launch / SSDP byebye-alive capability on a real TV.

Usage:
  python3 tools/e2e_full_suite.py [--tv 192.168.31.47] [--adb 192.168.31.47:5555]
"""
from __future__ import annotations

import argparse
import http.server
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request

AVT = "urn:schemas-upnp-org:service:AVTransport:1"
RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
MULTICAST = ("239.255.255.250", 1900)
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def log_pass(name: str, detail: str = "") -> None:
    suffix = f" — {detail}" if detail else ""
    print(f"  [PASS] {name}{suffix}", flush=True)


def local_ip_for(remote_host: str) -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((remote_host, 80))
        return s.getsockname()[0]
    finally:
        s.close()


def discover(st: str = "urn:schemas-upnp-org:device:MediaRenderer:1", expected_host: str | None = None) -> tuple[str, str]:
    msg = "\r\n".join([
        "M-SEARCH * HTTP/1.1",
        "HOST: 239.255.255.250:1900",
        'MAN: "ssdp:discover"',
        "MX: 2",
        f"ST: {st}",
        "",
        "",
    ]).encode()
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.settimeout(3.0)
    try:
        for _ in range(3):
            sock.sendto(msg, MULTICAST)
            deadline = time.time() + 3.0
            while time.time() < deadline:
                try:
                    data, addr = sock.recvfrom(4096)
                except socket.timeout:
                    break
                text = data.decode(errors="replace")
                m = re.search(r"LOCATION:\s*(\S+)", text, re.I)
                if m and "desc.xml" in m.group(1):
                    if expected_host is None or addr[0] == expected_host:
                        return m.group(1), text
    finally:
        sock.close()
    raise RuntimeError(f"SSDP discovery failed for ST={st}")


def soap(
    base: str,
    path: str,
    service: str,
    action: str,
    args_xml: str = "",
    body_override: str | None = None,
) -> tuple[int, str]:
    body = body_override if body_override is not None else (
        '<?xml version="1.0" encoding="utf-8"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"'
        ' s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        f'<s:Body><u:{action} xmlns:u="{service}">{args_xml}</u:{action}></s:Body></s:Envelope>'
    )
    req = urllib.request.Request(
        base + path,
        data=body.encode("utf-8"),
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPACTION": f'"{service}#{action}"',
        },
        method="POST",
    )
    try:
        with OPENER.open(req, timeout=10) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", errors="replace")


def raw_http(host: str, port: int, payload: bytes) -> bytes:
    with socket.create_connection((host, port), timeout=5) as s:
        s.settimeout(5)
        s.sendall(payload)
        chunks = []
        while True:
            try:
                data = s.recv(4096)
                if not data:
                    break
                chunks.append(data)
            except socket.timeout:
                break
        return b"".join(chunks)


def find(tag: str, xml: str) -> str:
    m = re.search(f"<{tag}>(.*?)</{tag}>", xml, re.S)
    return m.group(1) if m else ""


def wait_state(base: str, wanted: set[str], timeout_s: float = 20.0) -> tuple[str, str]:
    deadline = time.time() + timeout_s
    last_body = ""
    last_state = ""
    while time.time() < deadline:
        _, last_body = soap(base, "/ctl/avt", AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        last_state = find("CurrentTransportState", last_body)
        if last_state in wanted:
            return last_state, last_body
        time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for {wanted}; last state={last_state!r} body={last_body}")


def generate_media_assets(root: str) -> None:
    if not shutil.which("ffmpeg"):
        raise RuntimeError("ffmpeg is required on PATH to generate local test media")
    mp4 = os.path.join(root, "vod.mp4")
    subprocess.run(
        [
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=duration=60:size=640x360:rate=24",
            "-f", "lavfi", "-i", "sine=frequency=440:duration=60",
            "-c:v", "libx264", "-preset", "ultrafast", "-g", "24", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-movflags", "+faststart",
            mp4, "-loglevel", "error",
        ],
        check=True,
    )

    hls_dir = os.path.join(root, "hls")
    os.makedirs(hls_dir, exist_ok=True)
    subprocess.run(
        [
            "ffmpeg", "-y", "-i", mp4,
            "-c", "copy",
            "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod",
            "-hls_segment_filename", os.path.join(hls_dir, "seg_%03d.ts"),
            os.path.join(hls_dir, "vod.m3u8"),
            "-loglevel", "error",
        ],
        check=True,
    )

    # Create a simulated live sliding-window playlist (no #EXT-X-ENDLIST, no VOD tag)
    with open(os.path.join(hls_dir, "vod.m3u8"), "r", encoding="utf-8") as f:
        lines = f.read().splitlines()
    live_lines = [
        line for line in lines
        if not line.startswith("#EXT-X-ENDLIST") and not line.startswith("#EXT-X-PLAYLIST-TYPE")
    ]
    with open(os.path.join(hls_dir, "live.m3u8"), "w", encoding="utf-8") as f:
        f.write("\n".join(live_lines) + "\n")


class MediaServer:
    """Range-capable LAN HTTP server serving MP4, HLS VOD (incl. mid-path m3u8), HLS Live, and 404."""

    def __init__(self, root: str) -> None:
        self.root = root
        self.httpd = http.server.ThreadingHTTPServer(("0.0.0.0", 0), self._make_handler())
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def _make_handler(self):
        root = self.root

        class Handler(http.server.BaseHTTPRequestHandler):
            def _resolve(self) -> tuple[str | None, str]:
                path = self.path.split("?", 1)[0]
                if path == "/vod.mp4":
                    return os.path.join(root, "vod.mp4"), "video/mp4"
                if path in ("/hls/vod.m3u8", "/playlist/m3u8"):
                    return os.path.join(root, "hls", "vod.m3u8"), "application/vnd.apple.mpegurl"
                if path == "/hls/live.m3u8":
                    return os.path.join(root, "hls", "live.m3u8"), "application/vnd.apple.mpegurl"
                if path.startswith("/hls/") or path.startswith("/playlist/"):
                    seg = os.path.basename(path)
                    candidate = os.path.join(root, "hls", seg)
                    if os.path.isfile(candidate):
                        return candidate, "video/mp2t"
                return None, "text/plain"

            def do_HEAD(self):
                fpath, ctype = self._resolve()
                if not fpath or not os.path.isfile(fpath):
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                size = os.path.getsize(fpath)
                self.send_response(200)
                self.send_header("Content-Type", ctype)
                self.send_header("Accept-Ranges", "bytes")
                self.send_header("Content-Length", str(size))
                self.end_headers()

            def do_GET(self):
                fpath, ctype = self._resolve()
                if not fpath or not os.path.isfile(fpath):
                    self.send_response(404)
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
                size = os.path.getsize(fpath)
                rng = self.headers.get("Range")
                start, end = 0, size - 1
                status = 200
                if rng:
                    m = re.match(r"bytes=(\d+)-(\d*)", rng)
                    if m:
                        start = int(m.group(1))
                        if m.group(2):
                            end = min(int(m.group(2)), size - 1)
                        status = 206
                length = max(0, end - start + 1)
                self.send_response(status)
                self.send_header("Content-Type", ctype)
                self.send_header("Accept-Ranges", "bytes")
                if status == 206:
                    self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
                self.send_header("Content-Length", str(length))
                self.end_headers()
                with open(fpath, "rb") as f:
                    f.seek(start)
                    rem = length
                    while rem > 0:
                        chunk = f.read(min(65536, rem))
                        if not chunk:
                            break
                        try:
                            self.wfile.write(chunk)
                        except BrokenPipeError:
                            break
                        rem -= len(chunk)

            def log_message(self, format, *args):
                pass

        return Handler

    def start(self) -> None:
        self.thread.start()

    def stop(self) -> None:
        self.httpd.shutdown()


class GenaListener:
    def __init__(self) -> None:
        self.events: list[tuple[str, str, str]] = []
        self.lock = threading.Lock()
        self.httpd = http.server.ThreadingHTTPServer(("0.0.0.0", 0), self._make_handler())
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def _make_handler(self):
        parent = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_NOTIFY(self):
                length = int(self.headers.get("Content-Length", "0"))
                body = self.rfile.read(length).decode("utf-8", errors="replace")
                sid = self.headers.get("SID", "")
                seq = self.headers.get("SEQ", "")
                with parent.lock:
                    parent.events.append((sid, seq, body))
                self.send_response(200)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, format, *args):
                pass

        return Handler

    def start(self) -> None:
        self.thread.start()

    def stop(self) -> None:
        self.httpd.shutdown()

    def events_for(self, sid: str) -> list[tuple[str, str, str]]:
        with self.lock:
            return [e for e in self.events if e[0] == sid]


def adb_run(serial: str, *args: str) -> str:
    res = subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, check=True)
    return res.stdout


def main() -> int:
    parser = argparse.ArgumentParser(description="Full-coverage real-device E2E suite for LeanCast")
    parser.add_argument("--tv", default=None, help="Expected TV LAN IP (auto-discovers if omitted)")
    parser.add_argument("--adb", default=None, help="ADB serial/host:port for remote, background-launch, and logcat checks")
    args = parser.parse_args()

    tmp_dir = tempfile.mkdtemp(prefix="leancast_e2e_")
    media_srv = None
    gena = GenaListener()
    try:
        print("[Setup] Generating local MP4 + HLS VOD + HLS Live fixtures via ffmpeg...", flush=True)
        generate_media_assets(tmp_dir)
        media_srv = MediaServer(tmp_dir)
        media_srv.start()
        gena.start()

        # ── 1. SSDP Discovery ────────────────────────────────────────────────
        print("\n[1] SSDP Discovery", flush=True)
        loc, _ = discover("urn:schemas-upnp-org:device:MediaRenderer:1", args.tv)
        loc_all, _ = discover("SSDP:ALL", args.tv)
        assert loc == loc_all, f"{loc} != {loc_all}"
        base = loc.rsplit("/", 1)[0]
        host, port_s = base.split("://", 1)[1].split(":")
        port = int(port_s)
        my_ip = local_ip_for(host)
        media_base = f"http://{my_ip}:{media_srv.port}"
        log_pass("SSDP M-SEARCH (MediaRenderer:1 & uppercase SSDP:ALL)", loc)

        # ── 2. Device Description & All 3 SCPDs ──────────────────────────────
        print("\n[2] Device Description & SCPDs", flush=True)
        with OPENER.open(loc, timeout=5) as r:
            desc = r.read().decode()
        udn = find("UDN", desc)
        assert udn.startswith("uuid:"), desc
        assert "DMR-1.50" in desc and "https://github.com/addozhang/leancast" in desc, desc
        loc_udn, _ = discover(udn, host)
        assert loc_udn == loc
        head_resp = raw_http(host, port, f"HEAD /desc.xml HTTP/1.1\r\nHost: {host}:{port}\r\n\r\n".encode()).decode()
        assert head_resp.startswith("HTTP/1.1 200 OK") and head_resp.endswith("\r\n\r\n"), repr(head_resp)
        for scpd_path, expected_action in [
            ("/scpd/avt.xml", "SetAVTransportURI"),
            ("/scpd/rcs.xml", "SetMute"),
            ("/scpd/cmn.xml", "GetProtocolInfo"),
        ]:
            with OPENER.open(base + scpd_path, timeout=5) as r:
                scpd = r.read().decode()
            assert f"<name>{expected_action}</name>" in scpd, scpd_path
        log_pass("UDN search, HEAD /desc.xml, and AVT/RCS/CM SCPDs", udn)

        # ── 3. Capabilities (AVT / RCS / CM) ─────────────────────────────────
        print("\n[3] UPnP Capabilities", flush=True)
        assert "NORMAL" in soap(base, "/ctl/avt", AVT, "GetTransportSettings", "<InstanceID>0</InstanceID>")[1]
        assert "NETWORK" in soap(base, "/ctl/avt", AVT, "GetDeviceCapabilities", "<InstanceID>0</InstanceID>")[1]
        assert "http-get:*:video/mp4:*" in soap(base, "/ctl/cmn", CM, "GetProtocolInfo")[1]
        assert "<ConnectionIDs>0</ConnectionIDs>" in soap(base, "/ctl/cmn", CM, "GetCurrentConnectionIDs")[1]
        assert "<Status>OK</Status>" in soap(base, "/ctl/cmn", CM, "GetCurrentConnectionInfo", "<ConnectionID>0</ConnectionID>")[1]
        assert "FactoryDefaults" in soap(base, "/ctl/rcs", RCS, "ListPresets", "<InstanceID>0</InstanceID>")[1]
        assert soap(base, "/ctl/rcs", RCS, "SelectPreset", "<InstanceID>0</InstanceID><PresetName>FactoryDefaults</PresetName>")[0] == 200
        assert "<MinValue>-32768</MinValue>" in soap(base, "/ctl/rcs", RCS, "GetVolumeDBRange", "<InstanceID>0</InstanceID><Channel>Master</Channel>")[1]
        log_pass("All AVT/RCS/CM capability queries answered 200")

        # ── 4. HTTP Hardening & GENA Subscription Lifecycle ──────────────────
        print("\n[4] HTTP Hardening & GENA Subscribe/Renew/Order", flush=True)
        assert raw_http(host, port, f"GET /nope HTTP/1.1\r\nHost: {host}:{port}\r\n\r\n".encode()).startswith(b"HTTP/1.1 404")
        assert raw_http(host, port, f"POST /ctl/avt HTTP/1.1\r\nHost: {host}:{port}\r\nContent-Length: 9999999999\r\n\r\n".encode()).startswith(b"HTTP/1.1 413")
        st_unk, body_unk = soap(base, "/ctl/avt", AVT, "NoSuchAction")
        assert st_unk == 500 and "<errorCode>401</errorCode>" in body_unk
        bad_sub = (
            f"SUBSCRIBE /evt/avt HTTP/1.1\r\nHOST: {host}:{port}\r\n"
            "CALLBACK: <http://127.0.0.1:49152/ctl/avt>\r\nNT: upnp:event\r\nTIMEOUT: SECOND-300\r\n\r\n"
        ).encode()
        assert raw_http(host, port, bad_sub).startswith(b"HTTP/1.1 412")

        # Subscribe to both AVT and RCS
        def subscribe(ep: str) -> str:
            req = (
                f"SUBSCRIBE {ep} HTTP/1.1\r\nHOST: {host}:{port}\r\n"
                f"CALLBACK: <http://{my_ip}:{gena.port}/ev>\r\nNT: upnp:event\r\nTIMEOUT: SECOND-300\r\n\r\n"
            ).encode()
            resp = raw_http(host, port, req).decode()
            assert resp.startswith("HTTP/1.1 200") and "TIMEOUT: Second-300" in resp, resp
            m = re.search(r"SID:\s*(\S+)", resp, re.I)
            assert m, resp
            return m.group(1)

        avt_sid = subscribe("/evt/avt")
        rcs_sid = subscribe("/evt/rcs")
        time.sleep(0.6)
        assert gena.events_for(avt_sid)[0][1] == "0"
        assert gena.events_for(rcs_sid)[0][1] == "0"

        renew = raw_http(host, port, f"SUBSCRIBE /evt/avt HTTP/1.1\r\nHOST: {host}:{port}\r\nSID: {avt_sid}\r\nTIMEOUT: Second-600\r\n\r\n".encode()).decode()
        assert renew.startswith("HTTP/1.1 200") and "TIMEOUT: Second-600" in renew

        # Expect: 100-continue
        b100 = f'<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetTransportInfo xmlns:u="{AVT}"><InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>'.encode()
        with socket.create_connection((host, port), timeout=5) as s:
            s.settimeout(5)
            s.sendall((f"POST /ctl/avt HTTP/1.1\r\nHOST: {host}:{port}\r\nSOAPACTION: \"{AVT}#GetTransportInfo\"\r\nExpect: 100-continue\r\nContent-Length: {len(b100)}\r\n\r\n").encode())
            assert s.recv(1024).startswith(b"HTTP/1.1 100 Continue")
            s.sendall(b100)
            assert b"HTTP/1.1 200 OK" in s.recv(4096)
        log_pass("HTTP 404/413/500, Expect 100-continue, GENA loopback guard, SECOND-300, initial SEQ=0, RENEW")

        # ── 5. MP4 VOD Full Lifecycle (incl. namespace-less SOAP body & Speed) ──
        print("\n[5] MP4 VOD Playback, Seek, Pause/Resume, Speed", flush=True)
        mp4_url = f"{media_base}/vod.mp4"
        didl = "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;MP4 E2E&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
        no_ns_body = (
            '<?xml version="1.0" encoding="utf-8"?>'
            '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">'
            f"<s:Body><SetAVTransportURI><InstanceID>0</InstanceID><CurrentURI>{mp4_url}</CurrentURI>"
            f"<CurrentURIMetaData>{didl}</CurrentURIMetaData></SetAVTransportURI></s:Body></s:Envelope>"
        )
        assert soap(base, "/ctl/avt", AVT, "SetAVTransportURI", body_override=no_ns_body)[0] == 200
        wait_state(base, {"PLAYING"})
        time.sleep(1.5)
        _, mi = soap(base, "/ctl/avt", AVT, "GetMediaInfo", "<InstanceID>0</InstanceID>")
        assert "MP4 E2E" in mi and find("MediaDuration", mi) == "0:01:00", mi
        _, pi = soap(base, "/ctl/avt", AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        assert find("TrackDuration", pi) == "0:01:00" and find("RelTime", pi) != "0:00:00", pi

        assert soap(base, "/ctl/avt", AVT, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:00:30</Target>")[0] == 200
        time.sleep(1.2)
        _, pi_seek = soap(base, "/ctl/avt", AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        rel_seek = find("RelTime", pi_seek)
        assert rel_seek.startswith("0:00:3") or rel_seek.startswith("0:00:29"), pi_seek

        assert soap(base, "/ctl/avt", AVT, "Play", "<InstanceID>0</InstanceID><Speed>1.5</Speed>")[0] == 200
        _, ti_spd = soap(base, "/ctl/avt", AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        assert find("CurrentSpeed", ti_spd) == "1.5", ti_spd

        assert soap(base, "/ctl/avt", AVT, "Pause", "<InstanceID>0</InstanceID>")[0] == 200
        wait_state(base, {"PAUSED_PLAYBACK"})
        assert soap(base, "/ctl/avt", AVT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")[0] == 200
        _, ti_res = wait_state(base, {"PLAYING"})
        assert find("CurrentSpeed", ti_res) == "1.5", "routine Play Speed=1 should preserve 1.5x"
        log_pass("MP4 VOD play, metadata, seek, speed 1.5x, pause/resume speed preservation", f"post-seek={rel_seek}")

        # ── 6. HLS VOD (mid-path Youku-style m3u8 URL) ───────────────────────
        print("\n[6] HLS VOD (.m3u8 mid-path routing & HlsMediaSource)", flush=True)
        hls_vod_url = f"{media_base}/playlist/m3u8?vid=e2e"
        assert soap(base, "/ctl/avt", AVT, "SetAVTransportURI", f"<InstanceID>0</InstanceID><CurrentURI>{hls_vod_url}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")[0] == 200
        _, ti_hls = wait_state(base, {"PLAYING"})
        assert find("CurrentSpeed", ti_hls) == "1", "new cast must reset speed to 1"
        time.sleep(1.2)
        _, pi_hls = soap(base, "/ctl/avt", AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        assert find("TrackDuration", pi_hls) == "0:01:00", pi_hls
        assert soap(base, "/ctl/avt", AVT, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:00:24</Target>")[0] == 200
        time.sleep(1.2)
        _, pi_hls2 = soap(base, "/ctl/avt", AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        assert find("RelTime", pi_hls2).startswith("0:00:2"), pi_hls2
        log_pass("HLS VOD played via mid-path m3u8 & seeked cleanly", f"RelTime={find('RelTime', pi_hls2)}")

        # ── 7. HLS Live Stream (no ENDLIST) ──────────────────────────────────
        print("\n[7] HLS Live Stream (unknown duration, no Seek, elapsed clock)", flush=True)
        hls_live_url = f"{media_base}/hls/live.m3u8"
        assert soap(base, "/ctl/avt", AVT, "SetAVTransportURI", f"<InstanceID>0</InstanceID><CurrentURI>{hls_live_url}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")[0] == 200
        wait_state(base, {"PLAYING"})
        time.sleep(1.5)
        _, pi_live = soap(base, "/ctl/avt", AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        _, act_live = soap(base, "/ctl/avt", AVT, "GetCurrentTransportActions", "<InstanceID>0</InstanceID>")
        assert find("TrackDuration", pi_live) == "0:00:00", f"Live duration must be 0:00:00, got {pi_live}"
        assert find("Actions", act_live) == "Pause,Stop", f"Live actions must omit Seek, got {act_live}"
        # Speed change on live must be ignored
        soap(base, "/ctl/avt", AVT, "Play", "<InstanceID>0</InstanceID><Speed>2</Speed>")
        _, ti_live = soap(base, "/ctl/avt", AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        assert find("CurrentSpeed", ti_live) == "1", f"Live must stay at 1x, got {ti_live}"
        log_pass("HLS Live reported 0:00:00 duration, Actions=Pause,Stop, ignored 2x speed")

        # ── 8. Volume & Mute + RCS GENA ──────────────────────────────────────
        print("\n[8] Volume & Mute (AudioManager + RCS GENA)", flush=True)
        _, gv_orig = soap(base, "/ctl/rcs", RCS, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")
        orig_vol = int(find("CurrentVolume", gv_orig) or "50")
        target_vol = 40 if orig_vol != 40 else 55
        try:
            assert soap(base, "/ctl/rcs", RCS, "SetVolume", f"<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>{target_vol}</DesiredVolume>")[0] == 200
            assert find("CurrentVolume", soap(base, "/ctl/rcs", RCS, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")[1]) == str(target_vol)
            assert soap(base, "/ctl/rcs", RCS, "SetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>150</DesiredVolume>")[0] == 200
            assert find("CurrentVolume", soap(base, "/ctl/rcs", RCS, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")[1]) == "100"
            assert soap(base, "/ctl/rcs", RCS, "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>1</DesiredMute>")[0] == 200
            assert find("CurrentMute", soap(base, "/ctl/rcs", RCS, "GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>")[1]) == "1"
            assert soap(base, "/ctl/rcs", RCS, "SetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredMute>0</DesiredMute>")[0] == 200
            assert find("CurrentMute", soap(base, "/ctl/rcs", RCS, "GetMute", "<InstanceID>0</InstanceID><Channel>Master</Channel>")[1]) == "0"
        finally:
            soap(base, "/ctl/rcs", RCS, "SetVolume", f"<InstanceID>0</InstanceID><Channel>Master</Channel><DesiredVolume>{orig_vol}</DesiredVolume>")
        time.sleep(0.5)
        rcs_evs = gena.events_for(rcs_sid)
        assert len(rcs_evs) >= 3, f"Expected RCS GENA updates, got {len(rcs_evs)}"
        log_pass("SetVolume (incl. clamp 150->100), SetMute 1->0, restored volume & RCS GENA received", f"events={len(rcs_evs)}")

        # ── 9. Playback Error Recovery & Rapid Source Switch ─────────────────
        print("\n[9] Playback Error Recovery & Rapid Source Switch", flush=True)
        bad_url = f"{media_base}/missing.mp4"
        assert soap(base, "/ctl/avt", AVT, "SetAVTransportURI", f"<InstanceID>0</InstanceID><CurrentURI>{bad_url}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")[0] == 200
        wait_state(base, {"STOPPED"}, timeout_s=12.0)
        # Immediately recover with rapid double SetAVTransportURI
        assert soap(base, "/ctl/avt", AVT, "SetAVTransportURI", f"<InstanceID>0</InstanceID><CurrentURI>{hls_vod_url}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")[0] == 200
        assert soap(base, "/ctl/avt", AVT, "SetAVTransportURI", f"<InstanceID>0</InstanceID><CurrentURI>{mp4_url}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")[0] == 200
        wait_state(base, {"PLAYING"}, timeout_s=15.0)
        log_pass("404 bad URL parked in STOPPED cleanly; rapid re-cast recovered to PLAYING")

        # ── 10. ADB TV Remote, Background Launch, & SSDP byebye/alive ────────
        if args.adb:
            print("\n[10] TV Remote D-pad Controls (Speed, Pause/Play, Seek, Back)", flush=True)
            # Cycle speed via DPAD_DOWN through full ladder: 1.25 -> 1.5 -> 2 -> 0.75 -> 1
            for expected_spd in ["1.25", "1.5", "2", "0.75", "1"]:
                adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_DPAD_DOWN")
                time.sleep(0.6)
                _, ti = soap(base, "/ctl/avt", AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")
                got = find("CurrentSpeed", ti)
                assert got == expected_spd, f"DPAD_DOWN expected {expected_spd}, got {got}"

            # Pause / Resume via DPAD_CENTER
            adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
            wait_state(base, {"PAUSED_PLAYBACK"}, timeout_s=5.0)
            adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
            wait_state(base, {"PLAYING"}, timeout_s=5.0)

            # Seek forward via DPAD_RIGHT
            _, pi_before = soap(base, "/ctl/avt", AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
            adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_DPAD_RIGHT")
            time.sleep(1.0)
            _, pi_after = soap(base, "/ctl/avt", AVT, "GetPositionInfo", "<InstanceID>0</InstanceID>")
            assert find("RelTime", pi_after) != find("RelTime", pi_before), f"{pi_before} vs {pi_after}"

            # DPAD_UP shows OSD without changing PLAYING state
            adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_DPAD_UP")
            time.sleep(0.4)
            assert find("CurrentTransportState", soap(base, "/ctl/avt", AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")[1]) == "PLAYING"

            # BACK stops and exits player
            adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_BACK")
            wait_state(base, {"STOPPED"}, timeout_s=5.0)
            log_pass("Remote DPAD_DOWN speed ladder, DPAD_CENTER pause/resume, DPAD_RIGHT seek, DPAD_UP OSD, BACK exit")

            print("\n[11] Background Cast Surfacing from TV Home Screen", flush=True)
            adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(1.5)
            assert soap(base, "/ctl/avt", AVT, "SetAVTransportURI", f"<InstanceID>0</InstanceID><CurrentURI>{mp4_url}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")[0] == 200
            wait_state(base, {"PLAYING"}, timeout_s=15.0)
            # Android enforces a 5 s stopAppSwitches() window immediately after KEYCODE_HOME;
            # poll until PlayerActivity becomes the focused window.
            surfaced = False
            deadline = time.time() + 8.0
            while time.time() < deadline:
                win = adb_run(args.adb, "shell", "dumpsys", "window", "windows")
                if any("mCurrentFocus=" in line and "PlayerActivity" in line for line in win.splitlines()):
                    surfaced = True
                    break
                time.sleep(0.5)
            assert surfaced, "PlayerActivity did not surface to foreground from Home screen"
            soap(base, "/ctl/avt", AVT, "Stop", "<InstanceID>0</InstanceID>")
            wait_state(base, {"STOPPED"}, timeout_s=5.0)
            log_pass("PlayerActivity surfaced cleanly as mCurrentFocus from TV Home screen")

            print("\n[12] SSDP byebye/alive & Logcat Crash Check", flush=True)
            # Bring MainActivity back to foreground, toggle Cast Service off then on
            adb_run(args.adb, "shell", "am", "start", "-W", "-n", "app.solocraft.leancast.debug/io.leancast.app.MainActivity")
            time.sleep(0.8)
            msock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
            msock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            if hasattr(socket, "SO_REUSEPORT"):
                msock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
            msock.bind(("", 1900))
            mreq = struct.pack("4s4s", socket.inet_aton("239.255.255.250"), socket.inet_aton("0.0.0.0"))
            msock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, mreq)
            msock.settimeout(0.4)
            try:
                # Re-focus Rename -> Autostart -> Cast Service
                adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_DPAD_DOWN")
                adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_DPAD_DOWN")
                adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
                byebye = alive = 0
                end_t = time.time() + 2.0
                while time.time() < end_t:
                    try:
                        data, addr = msock.recvfrom(4096)
                    except socket.timeout:
                        continue
                    if addr[0] == host and "ssdp:byebye" in data.decode(errors="replace"):
                        byebye += 1
                adb_run(args.adb, "shell", "input", "keyevent", "KEYCODE_DPAD_CENTER")
                end_t = time.time() + 2.5
                while time.time() < end_t:
                    try:
                        data, addr = msock.recvfrom(4096)
                    except socket.timeout:
                        continue
                    if addr[0] == host and "ssdp:alive" in data.decode(errors="replace"):
                        alive += 1
            finally:
                msock.close()
            assert byebye >= 5 and alive >= 5, f"Expected >=5 byebye & alive, got {byebye}/{alive}"

            logcat = adb_run(args.adb, "logcat", "-d", "-s", "AndroidRuntime", "LeanCastSsdp", "LeanCastGena")
            assert "FATAL EXCEPTION" not in logcat, logcat
            assert "NetworkOnMainThreadException" not in logcat, logcat
            log_pass("SSDP byebye/alive verified & logcat clean", f"byebye={byebye}, alive={alive}")
        else:
            soap(base, "/ctl/avt", AVT, "Stop", "<InstanceID>0</InstanceID>")

        # ── Final GENA SEQ Monotonicity & Unsubscribe ────────────────────────
        print("\n[Final] GENA SEQ Ordering Audit", flush=True)
        for label, sid in [("AVT", avt_sid), ("RCS", rcs_sid)]:
            seqs = [int(ev[1]) for ev in gena.events_for(sid)]
            assert seqs == list(range(len(seqs))), f"{label} GENA SEQ out of order: {seqs}"
            log_pass(f"{label} GENA strictly monotonic", f"{len(seqs)} events (SEQ 0..{len(seqs) - 1})")

        print("\n=== FULL-COVERAGE E2E SUITE PASSED ===", flush=True)
        return 0
    finally:
        gena.stop()
        if media_srv:
            media_srv.stop()
        shutil.rmtree(tmp_dir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())

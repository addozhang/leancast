package io.leancast.app

import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.PlaybackSpeedPolicy
import io.leancast.domain.cast.PlayerPort
import io.leancast.domain.upnp.LastChangeEvent
import io.leancast.domain.upnp.RendererDescription
import io.leancast.domain.upnp.SoapRouter
import io.leancast.domain.upnp.UpnpService
import io.leancast.domain.upnp.UpnpTime
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real-socket JVM integration tests for [ControlServer] wired to [SoapRouter],
 * [CastSession], and [SubscriptionRegistry]. Runs in CI without an Android device.
 */
class ControlServerIntegrationTest {

    private class FakePlayerPort : PlayerPort {
        var preparedUri: String? = null
        var positionMs = 45_000L
        var durationMs = 120_000L
        var speed = 1.0f
        var seekMs: Long? = null
        var live = false

        override fun prepareAndPlay(uri: String) {
            preparedUri = uri
        }
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Long) {
            seekMs = ms
            positionMs = ms
        }
        override fun stop() = Unit
        override fun currentPositionMs(): Long = positionMs
        override fun currentDurationMs(): Long = durationMs
        override fun isLive(): Boolean = live
        override fun setPlaybackSpeed(speed: Float) {
            this.speed = speed
        }
    }

    private lateinit var port: FakePlayerPort
    private lateinit var session: CastSession
    private lateinit var events: SubscriptionRegistry
    private lateinit var server: ControlServer

    @Before
    fun setUp() {
        port = FakePlayerPort()
        session = CastSession(port)
        events = SubscriptionRegistry()
        server = ControlServer.startWithFallback(
            preferredPort = 0,
            descriptionProvider = {
                RendererDescription(
                    friendlyName = "Test TV",
                    uuid = "uuid:11111111-2222-3333-4444-555555555555",
                    manufacturer = "LeanCast",
                    modelName = "LeanCast Receiver",
                    baseUrl = "http://127.0.0.1:${server.boundPort}",
                )
            },
            router = SoapRouter(session),
            events = events,
            eventBody = { service ->
                if (service == "rcs") {
                    LastChangeEvent.renderingControl(session.volume, session.muted)
                } else {
                    LastChangeEvent.avTransport(
                        transportState = session.state.name,
                        currentUri = session.currentUri,
                        currentTrackDuration = UpnpTime.renderMsToHms(session.durationMs()),
                        isLive = session.isLive(),
                        playbackSpeed = PlaybackSpeedPolicy.formatUpnpSpeed(session.playbackSpeed),
                    )
                }
            },
            serverToken = "Test/1.0 UPnP/1.0 LeanCast/1",
        )
    }

    @After
    fun tearDown() {
        server.stop()
        events.shutdown()
    }

    private data class RawResponse(val statusLine: String, val headers: Map<String, String>, val body: String)

    private fun sendRaw(request: String, targetPort: Int = server.boundPort): RawResponse {
        Socket("127.0.0.1", targetPort).use { sock ->
            sock.soTimeout = 5_000
            sock.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                flush()
            }
            val raw = sock.getInputStream().readBytes().toString(Charsets.UTF_8)
            val splitIdx = raw.indexOf("\r\n\r\n")
            val head = if (splitIdx >= 0) raw.substring(0, splitIdx) else raw
            val body = if (splitIdx >= 0) raw.substring(splitIdx + 4) else ""
            val lines = head.split("\r\n")
            val headers = linkedMapOf<String, String>()
            lines.drop(1).forEach { line ->
                val colon = line.indexOf(':')
                if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            }
            return RawResponse(lines.firstOrNull().orEmpty(), headers, body)
        }
    }

    private fun soapPost(path: String, service: UpnpService, action: String, argsXml: String): RawResponse {
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body><u:$action xmlns:u="${service.serviceType}">$argsXml</u:$action></s:Body>
</s:Envelope>"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val req = buildString {
            append("POST $path HTTP/1.1\r\n")
            append("HOST: 127.0.0.1:${server.boundPort}\r\n")
            append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
            append("SOAPACTION: \"${service.serviceType}#$action\"\r\n")
            append("CONTENT-LENGTH: ${bytes.size}\r\n\r\n")
            append(body)
        }
        return sendRaw(req)
    }

    @Test
    fun `serves desc xml, all three SCPDs, HEAD without body, 404, and 405`() {
        val desc = sendRaw("GET /desc.xml HTTP/1.1\r\nHost: localhost\r\n\r\n")
        assertEquals("HTTP/1.1 200 OK", desc.statusLine)
        assertTrue(desc.body.contains("<friendlyName>Test TV</friendlyName>"))
        assertTrue(desc.body.contains("https://github.com/addozhang/leancast"))

        val head = sendRaw("HEAD /desc.xml HTTP/1.1\r\nHost: localhost\r\n\r\n")
        assertEquals("HTTP/1.1 200 OK", head.statusLine)
        assertEquals("", head.body)
        assertTrue((head.headers["content-length"]?.toIntOrNull() ?: 0) > 100)

        listOf("/scpd/avt.xml", "/scpd/rcs.xml", "/scpd/cmn.xml").forEach { path ->
            val scpd = sendRaw("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertEquals(path, "HTTP/1.1 200 OK", scpd.statusLine)
            assertTrue(path, scpd.body.contains("<scpd"))
        }

        assertEquals("HTTP/1.1 404 Not Found", sendRaw("GET /unknown HTTP/1.1\r\nHost: localhost\r\n\r\n").statusLine)
        assertEquals("HTTP/1.1 405 Method Not Allowed", sendRaw("DELETE /desc.xml HTTP/1.1\r\nHost: localhost\r\n\r\n").statusLine)
    }

    @Test
    fun `handles full AVT, RCS, and CM SOAP actions over HTTP`() {
        val didl = "&lt;DIDL-Lite&gt;&lt;item&gt;&lt;dc:title&gt;Movie&lt;/dc:title&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;"
        val setUri = soapPost(
            "/ctl/avt",
            UpnpService.AVT,
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>http://lan/v.mp4</CurrentURI><CurrentURIMetaData>$didl</CurrentURIMetaData>",
        )
        assertEquals("HTTP/1.1 200 OK", setUri.statusLine)
        session.onPlayerPrepared()

        val mediaInfo = soapPost("/ctl/avt", UpnpService.AVT, "GetMediaInfo", "<InstanceID>0</InstanceID>")
        assertTrue(mediaInfo.body.contains("Movie"))
        assertTrue(mediaInfo.body.contains("<MediaDuration>0:02:00</MediaDuration>"))

        val speedPlay = soapPost("/ctl/avt", UpnpService.AVT, "Play", "<InstanceID>0</InstanceID><Speed>1.5</Speed>")
        assertEquals("HTTP/1.1 200 OK", speedPlay.statusLine)
        val transport = soapPost("/ctl/avt", UpnpService.AVT, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        assertTrue(transport.body.contains("<CurrentSpeed>1.5</CurrentSpeed>"))

        val seek = soapPost("/ctl/avt", UpnpService.AVT, "Seek", "<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>0:01:10</Target>")
        assertEquals("HTTP/1.1 200 OK", seek.statusLine)
        assertEquals(70_000L, port.seekMs)

        // Volume & Mute
        assertEquals("HTTP/1.1 200 OK", soapPost("/ctl/rcs", UpnpService.RCS, "SetVolume", "<Channel>Master</Channel><DesiredVolume>64</DesiredVolume>").statusLine)
        assertTrue(soapPost("/ctl/rcs", UpnpService.RCS, "GetVolume", "<Channel>Master</Channel>").body.contains("<CurrentVolume>64</CurrentVolume>"))
        assertEquals("HTTP/1.1 200 OK", soapPost("/ctl/rcs", UpnpService.RCS, "SetMute", "<Channel>Master</Channel><DesiredMute>1</DesiredMute>").statusLine)
        assertTrue(soapPost("/ctl/rcs", UpnpService.RCS, "GetMute", "<Channel>Master</Channel>").body.contains("<CurrentMute>1</CurrentMute>"))

        // ConnectionManager
        assertTrue(soapPost("/ctl/cmn", UpnpService.CM, "GetProtocolInfo", "").body.contains("http-get:*:video/mp4:*"))
        assertTrue(soapPost("/ctl/cmn", UpnpService.CM, "GetCurrentConnectionIDs", "").body.contains("<ConnectionIDs>0</ConnectionIDs>"))
    }

    @Test
    fun `maps SOAP faults, handles Expect 100-continue, and rejects 413 payloads`() {
        val unknown = soapPost("/ctl/avt", UpnpService.AVT, "UnknownAction", "")
        assertEquals("HTTP/1.1 500 Internal Server Error", unknown.statusLine)
        assertTrue(unknown.body.contains("<errorCode>401</errorCode>"))

        val badXml = sendRaw(
            "POST /ctl/avt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 7\r\n\r\nnot-xml",
        )
        assertEquals("HTTP/1.1 400 Bad Request", badXml.statusLine)
        assertTrue(badXml.body.contains("<errorCode>402</errorCode>"))

        // 413 for >256KB and for >Int.MAX_VALUE
        val tooBig = sendRaw("POST /ctl/avt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 300000\r\n\r\n")
        assertEquals("HTTP/1.1 413 Payload Too Large", tooBig.statusLine)
        val hugeLong = sendRaw("POST /ctl/avt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 9999999999\r\n\r\n")
        assertEquals("HTTP/1.1 413 Payload Too Large", hugeLong.statusLine)

        // Expect: 100-continue handshake
        val body = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetTransportInfo xmlns:u="${UpnpService.AVT.serviceType}"><InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>"""
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        Socket("127.0.0.1", server.boundPort).use { sock ->
            sock.soTimeout = 5_000
            val out = sock.getOutputStream()
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            out.write(
                (
                    "POST /ctl/avt HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "SOAPACTION: \"${UpnpService.AVT.serviceType}#GetTransportInfo\"\r\n" +
                        "Expect: 100-continue\r\n" +
                        "Content-Length: ${bodyBytes.size}\r\n\r\n"
                    ).toByteArray(Charsets.ISO_8859_1),
            )
            out.flush()
            assertEquals("HTTP/1.1 100 Continue", reader.readLine())
            assertEquals("", reader.readLine())
            out.write(bodyBytes)
            out.flush()
            assertEquals("HTTP/1.1 200 OK", reader.readLine())
        }
    }

    @Test
    fun `GENA SUBSCRIBE rejects loopback by default, accepts valid LAN callback, renews, and unsubscribes`() {
        val rejected = sendRaw(
            "SUBSCRIBE /evt/avt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "CALLBACK: <http://127.0.0.1:9999/ev>\r\n" +
                "NT: upnp:event\r\n" +
                "TIMEOUT: SECOND-300\r\n\r\n",
        )
        assertEquals("HTTP/1.1 412 Precondition Failed", rejected.statusLine)

        val accepted = sendRaw(
            "SUBSCRIBE /evt/avt HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "CALLBACK: <http://198.51.100.10:8080/ev>\r\n" +
                "NT: upnp:event\r\n" +
                "TIMEOUT: SECOND-300\r\n\r\n",
        )
        assertEquals("HTTP/1.1 200 OK", accepted.statusLine)
        assertEquals("Second-300", accepted.headers["timeout"])
        val sid = accepted.headers["sid"]
        assertNotNull(sid)

        val renewed = sendRaw(
            "SUBSCRIBE /evt/avt HTTP/1.1\r\nHost: localhost\r\nSID: $sid\r\nTIMEOUT: Second-600\r\n\r\n",
        )
        assertEquals("HTTP/1.1 200 OK", renewed.statusLine)
        assertEquals("Second-600", renewed.headers["timeout"])

        val unsub = sendRaw("UNSUBSCRIBE /evt/avt HTTP/1.1\r\nHost: localhost\r\nSID: $sid\r\n\r\n")
        assertEquals("HTTP/1.1 200 OK", unsub.statusLine)

        val unsubAgain = sendRaw("UNSUBSCRIBE /evt/avt HTTP/1.1\r\nHost: localhost\r\nSID: $sid\r\n\r\n")
        assertEquals("HTTP/1.1 412 Precondition Failed", unsubAgain.statusLine)
    }

    @Test
    fun `GENA initial NOTIFY is dispatched strictly after SUBSCRIBE 200 OK is flushed`() {
        val received = CopyOnWriteArrayList<String>()
        val notifyLatch = CountDownLatch(1)
        val subscribeResponseReceived = AtomicBoolean(false)
        val notifyArrivedBeforeSubscribeOk = AtomicBoolean(false)

        val callbackServer = ServerSocket(0)
        val callbackThread = Thread {
            runCatching {
                callbackServer.accept().use { client ->
                    if (!subscribeResponseReceived.get()) {
                        // Give the client socket reader a brief moment if OS scheduled callback thread first
                        Thread.sleep(20)
                    }
                    if (!subscribeResponseReceived.get()) {
                        notifyArrivedBeforeSubscribeOk.set(true)
                    }
                    val req = client.getInputStream().bufferedReader().readLine().orEmpty()
                    received += req
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    notifyLatch.countDown()
                }
            }
        }.apply { isDaemon = true; start() }

        // Test server allowing loopback callback only for this ordering test.
        val testServer = ControlServer(
            port = 0,
            descriptionProvider = {
                RendererDescription("T", "uuid:t", "LeanCast", "LeanCast", "http://127.0.0.1")
            },
            router = SoapRouter(session),
            events = events,
            eventBody = { LastChangeEvent.avTransport("STOPPED", null, "0:00:00") },
            serverToken = "Test/1.0",
            callbackParser = { raw ->
                Regex("<([^>]+)>").findAll(raw.orEmpty()).map { it.groupValues[1] }.toList()
            },
        ).apply { start() }

        try {
            val subResp = sendRaw(
                "SUBSCRIBE /evt/avt HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "CALLBACK: <http://127.0.0.1:${callbackServer.localPort}/ev>\r\n" +
                    "NT: upnp:event\r\n" +
                    "TIMEOUT: Second-300\r\n\r\n",
                targetPort = testServer.boundPort,
            )
            subscribeResponseReceived.set(true)
            assertEquals("HTTP/1.1 200 OK", subResp.statusLine)
            assertTrue("initial NOTIFY should arrive", notifyLatch.await(3, TimeUnit.SECONDS))
            assertFalse("NOTIFY must not race ahead of SUBSCRIBE 200 OK", notifyArrivedBeforeSubscribeOk.get())
            assertTrue(received.first().startsWith("NOTIFY /ev HTTP/1.1"))
        } finally {
            testServer.stop()
            runCatching { callbackServer.close() }
            callbackThread.interrupt()
        }
    }
}

package io.leancast.domain.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec: upnp-renderer — Requirement: Dispatch SOAP control actions (parse side). */
class SoapParserTest {

    private val setUriBody = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
              <InstanceID>0</InstanceID>
              <CurrentURI>http://example.com/v.m3u8</CurrentURI>
              <CurrentURIMetaData>DIDL</CurrentURIMetaData>
            </u:SetAVTransportURI>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    @Test
    fun `SOAPACTION header routes service and action`() {
        val req = SoapParser.parse(
            soapActionHeader = "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            body = playBody(),
        )
        assertEquals(UpnpService.AVT, req?.service)
        assertEquals("Play", req?.action)
        assertEquals("0", req?.args?.get("InstanceID"))
    }

    @Test
    fun `missing header falls back to body element`() {
        val req = SoapParser.parse(soapActionHeader = null, body = playBody())
        assertEquals(UpnpService.AVT, req?.service)
        assertEquals("Play", req?.action)
    }

    @Test
    fun `headerless rendering control body resolves RCS`() {
        val body = soapBody(
            "GetVolume",
            "urn:schemas-upnp-org:service:RenderingControl:1",
            "<InstanceID>0</InstanceID><Channel>Master</Channel>",
        )
        val req = SoapParser.parse(null, body)
        assertEquals(UpnpService.RCS, req?.service)
        assertEquals("GetVolume", req?.action)
        assertEquals("Master", req?.args?.get("Channel"))
    }

    @Test
    fun `arguments of SetAVTransportURI are extracted`() {
        val req = SoapParser.parse(
            "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            setUriBody,
        )
        assertEquals("http://example.com/v.m3u8", req?.args?.get("CurrentURI"))
        assertEquals("DIDL", req?.args?.get("CurrentURIMetaData"))
    }

    @Test
    fun `embedded DIDL XML survives SOAP unescaping`() {
        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"><item><dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\">A &amp; B</dc:title></item></DIDL-Lite>"
        val body = soapBody(
            "SetAVTransportURI",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "<InstanceID>0</InstanceID><CurrentURI>u</CurrentURI>" +
                "<CurrentURIMetaData>$didl</CurrentURIMetaData>",
        )
        // The SOAP layer will have escaped the DIDL once more inside the arg element;
        // simulate by wrapping the whole didl in an escaped text node.
        val escaped = didl
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val raw = body.replace(didl, escaped)
        val req = SoapParser.parse(null, raw)
        assertEquals("A & B", DidlParser.extractTitle(req?.args?.get("CurrentURIMetaData")))
    }

    @Test
    fun `malformed body returns null`() {
        assertNull(SoapParser.parse(null, "this is not xml"))
        assertNull(SoapParser.parse(null, ""))
    }

    @Test
    fun `DIDL title extraction handles absence and garbage`() {
        assertNull(DidlParser.extractTitle(null))
        assertNull(DidlParser.extractTitle("no xml here"))
        val withTitle = "<DIDL-Lite><item><dc:title>Big Buck Bunny</dc:title></item></DIDL-Lite>"
        assertEquals("Big Buck Bunny", DidlParser.extractTitle(withTitle))
    }

    private fun playBody() = soapBody(
        "Play",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "<InstanceID>0</InstanceID><Speed>1</Speed>",
    )

    private fun soapBody(action: String, serviceUrn: String, argsXml: String) = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:$action xmlns:u="$serviceUrn">$argsXml</u:$action>
          </s:Body>
        </s:Envelope>
    """.trimIndent()
}

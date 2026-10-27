package io.leancast.domain.upnp

import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: upnp-renderer — Requirement: Serve device description / SCPDs. */
class DeviceDescriptionTest {

    private val doc = DeviceDescriptionDoc(
        RendererDescription(
            friendlyName = "Living Room TV",
            uuid = "uuid:2fac1234-31f8-11b4-a222-08002b34c003",
            manufacturer = "LeanCast",
            modelName = "LeanCast Receiver",
            baseUrl = "http://192.168.31.47:49152",
        ),
    )

    private fun parseXml(xml: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())

    @Test
    fun `device description is well-formed and advertises MediaRenderer with three services`() {
        val dom = parseXml(doc.deviceXml())
        assertEquals("root", dom.documentElement.nodeName)
        assertEquals(3, dom.getElementsByTagName("service").length)
        assertEquals(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            dom.getElementsByTagName("deviceType").item(0).textContent,
        )
        assertEquals("Living Room TV", dom.getElementsByTagName("friendlyName").item(0).textContent)
        assertEquals(
            "uuid:2fac1234-31f8-11b4-a222-08002b34c003",
            dom.getElementsByTagName("UDN").item(0).textContent,
        )
    }

    private fun childText(node: org.w3c.dom.Node, tag: String): String =
        (0 until node.childNodes.length)
            .map { node.childNodes.item(it) }
            .first { it.nodeName == tag }
            .textContent

    @Test
    fun `service control and event URLs are server-relative and distinct`() {
        val dom = parseXml(doc.deviceXml())
        val services = dom.getElementsByTagName("service")
        val paths = (0 until services.length).flatMap { i ->
            val s = services.item(i)
            listOf("SCPDURL", "controlURL", "eventSubURL").map { tag -> childText(s, tag) }
        }
        assertEquals(9, paths.size)
        assertEquals(9, paths.distinct().size)
        assertTrue(paths.all { it.startsWith("/") })
        assertTrue(paths.contains("/ctl/avt"))
        assertTrue(paths.contains("/evt/avt"))
        assertTrue(paths.contains("/scpd/avt.xml"))
    }

    @Test
    fun `friendly name is XML-escaped`() {
        val odd = DeviceDescriptionDoc(
            RendererDescription(
                friendlyName = "A & B <TV>",
                uuid = "uuid:x",
                manufacturer = "m",
                modelName = "n",
                baseUrl = "http://h",
            ),
        )
        val dom = parseXml(odd.deviceXml())
        assertEquals("A & B <TV>", dom.getElementsByTagName("friendlyName").item(0).textContent)
    }

    @Test
    fun `AVT SCPD is well-formed and lists transport actions`() {
        val dom = parseXml(doc.scpdAvt())
        val actions = dom.getElementsByTagName("name").let { nodes ->
            (0 until nodes.length).map { nodes.item(it).textContent }
        }
        assertTrue(actions.containsAll(listOf("Play", "Pause", "Stop", "Seek", "SetAVTransportURI", "GetPositionInfo", "GetTransportInfo", "GetMediaInfo")))
    }

    @Test
    fun `RCS SCPD lists volume and mute actions`() {
        val dom = parseXml(doc.scpdRcs())
        val actions = dom.getElementsByTagName("name").let { nodes ->
            (0 until nodes.length).map { nodes.item(it).textContent }
        }
        assertTrue(actions.containsAll(listOf("SetVolume", "GetVolume", "SetMute", "GetMute")))
    }

    @Test
    fun `CM SCPD lists GetProtocolInfo`() {
        val dom = parseXml(doc.scpdCmn())
        val actions = dom.getElementsByTagName("name").let { nodes ->
            (0 until nodes.length).map { nodes.item(it).textContent }
        }
        assertTrue(actions.contains("GetProtocolInfo"))
    }
}

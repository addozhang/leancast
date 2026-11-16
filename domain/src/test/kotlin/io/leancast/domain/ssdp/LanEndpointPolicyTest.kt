package io.leancast.domain.ssdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: ssdp-discovery / receiver-app — Boot-time LAN readiness and dynamic
 * endpoint formatting without advertising `0.0.0.0`.
 */
class LanEndpointPolicyTest {

    @Test
    fun `null, blank, wildcard and loopback IPs are rejected as unusable`() {
        assertFalse(LanEndpointPolicy.isUsableLanIp(null))
        assertFalse(LanEndpointPolicy.isUsableLanIp(""))
        assertFalse(LanEndpointPolicy.isUsableLanIp("   "))
        assertFalse(LanEndpointPolicy.isUsableLanIp("0.0.0.0"))
        assertFalse(LanEndpointPolicy.isUsableLanIp("127.0.0.1"))
    }

    @Test
    fun `site-local LAN IPv4 addresses are accepted`() {
        assertTrue(LanEndpointPolicy.isUsableLanIp("192.168.31.47"))
        assertTrue(LanEndpointPolicy.isUsableLanIp("10.0.0.12"))
        assertTrue(LanEndpointPolicy.isUsableLanIp("172.16.1.5"))
    }

    @Test
    fun `formatEndpoint returns empty string until a valid LAN IP and port exist`() {
        assertEquals("", LanEndpointPolicy.formatEndpoint(null, 49152))
        assertEquals("", LanEndpointPolicy.formatEndpoint("0.0.0.0", 49152))
        assertEquals("", LanEndpointPolicy.formatEndpoint("192.168.1.20", 0))
        assertEquals("192.168.1.20:49152", LanEndpointPolicy.formatEndpoint("192.168.1.20", 49152))
    }

    @Test
    fun `locationUrl returns null while offline and builds desc_xml URL when online`() {
        assertNull(LanEndpointPolicy.locationUrl(null, 49152))
        assertNull(LanEndpointPolicy.locationUrl("0.0.0.0", 49152))
        assertEquals(
            "http://192.168.31.47:49152/desc.xml",
            LanEndpointPolicy.locationUrl("192.168.31.47", 49152),
        )
    }
}

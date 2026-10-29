package io.leancast.domain.ssdp

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: ssdp-discovery — Requirement: Receiver identity is stable across reinstalls. */
class DeviceUuidTest {

    @Test
    fun `same device id always derives the same udn`() {
        val id = "a1b2c3d4e5f60718"
        assertEquals(DeviceUuid.derive(id), DeviceUuid.derive(id))
    }

    @Test
    fun `derived udn is uuid prefixed and well formed`() {
        val udn = DeviceUuid.derive("a1b2c3d4e5f60718")!!
        assertTrue("expected uuid: prefix, got $udn", udn.startsWith("uuid:"))
        // Must parse as a UUID so senders can treat it like any other UDN.
        UUID.fromString(udn.removePrefix("uuid:"))
    }

    @Test
    fun `different device ids derive different udns`() {
        assertNotEquals(DeviceUuid.derive("device-one"), DeviceUuid.derive("device-two"))
    }

    @Test
    fun `unusable device ids yield no udn`() {
        listOf(null, "", "   ", "null", "NULL", "unknown", "9774d56d682e549c")
            .forEach { bad -> assertNull("expected null for '$bad'", DeviceUuid.derive(bad)) }
    }

    @Test
    fun `derived udn does not leak the raw device id`() {
        val id = "a1b2c3d4e5f60718"
        assertFalse(DeviceUuid.derive(id)!!.contains(id))
    }

    @Test
    fun `surrounding whitespace does not change the udn`() {
        assertEquals(DeviceUuid.derive("a1b2c3d4e5f60718"), DeviceUuid.derive("  a1b2c3d4e5f60718\n"))
    }
}

package io.leancast.domain.upnp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec: media-playback — Requirement: Time unit conversion. */
class UpnpTimeTest {

    @Test
    fun `parses H colon MM colon SS with fractional seconds`() {
        assertEquals(90_500L, UpnpTime.parseHmsToMs("0:01:30.500"))
        assertEquals(5_492_000L, UpnpTime.parseHmsToMs("1:31:32"))
        assertEquals(36_000_000L, UpnpTime.parseHmsToMs("10:00:00"))
        assertEquals(0L, UpnpTime.parseHmsToMs("0:00:00"))
    }

    @Test
    fun `parses without leading zero hour`() {
        assertEquals(3_662_000L, UpnpTime.parseHmsToMs("1:01:02"))
    }

    @Test
    fun `renders milliseconds as H colon MM colon SS`() {
        assertEquals("1:31:32", UpnpTime.renderMsToHms(5_492_000L))
        assertEquals("0:00:00", UpnpTime.renderMsToHms(0L))
        assertEquals("10:00:00", UpnpTime.renderMsToHms(36_000_000L))
    }

    @Test
    fun `render truncates sub-second remainder`() {
        assertEquals("0:01:30", UpnpTime.renderMsToHms(90_500L))
    }

    @Test
    fun `rejects malformed timestamps`() {
        assertNull(UpnpTime.parseHmsToMs("banana"))
        assertNull(UpnpTime.parseHmsToMs("1:2:3:4"))
        assertNull(UpnpTime.parseHmsToMs("01:02"))
        assertNull(UpnpTime.parseHmsToMs(""))
    }
}

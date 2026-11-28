package io.leancast.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: app-update — SemVer parsing and strict remote > current comparison. */
class SemVerTest {

    @Test
    fun `parses plain, v-prefixed, prerelease and build-metadata versions`() {
        assertEquals(SemVer(0, 3, 0), SemVer.parse("0.3.0"))
        assertEquals(SemVer(0, 3, 1), SemVer.parse("v0.3.1"))
        assertEquals(SemVer(1, 2, 3, "beta.1"), SemVer.parse("V1.2.3-beta.1+42"))
    }

    @Test
    fun `rejects invalid version strings`() {
        assertNull(SemVer.parse(null))
        assertNull(SemVer.parse(""))
        assertNull(SemVer.parse("0.3"))
        assertNull(SemVer.parse("<html>404</html>"))
        assertNull(SemVer.parse("1.2.3-"))
        assertNull(SemVer.parse("1.2.x"))
    }

    @Test
    fun `compares major, minor, patch and prerelease precedence`() {
        assertTrue(SemVer.isNewer("0.3.1", "0.3.0"))
        assertTrue(SemVer.isNewer("v0.4.0", "0.3.9"))
        assertTrue(SemVer.isNewer("1.0.0", "0.9.9"))
        assertTrue(SemVer.isNewer("0.3.0", "0.3.0-beta.1"))
        assertTrue(SemVer.isNewer("0.3.0-beta.10", "0.3.0-beta.2"))

        assertFalse(SemVer.isNewer("0.3.0", "0.3.0"))
        assertFalse(SemVer.isNewer("0.2.9", "0.3.0"))
        assertFalse(SemVer.isNewer("0.3.0-beta.1", "0.3.0"))
        assertFalse(SemVer.isNewer("invalid", "0.3.0"))
        assertFalse(SemVer.isNewer("0.4.0", "invalid"))
    }
}

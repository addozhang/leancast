package io.leancast.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec: app-update — Release manifest field validation and sanitization. */
class UpdateManifestTest {

    private val validSha = "a".repeat(64)

    @Test
    fun `accepts valid manifest and normalizes version and sha256`() {
        val m = UpdateManifest(
            version = " v0.4.0 ",
            file = "LeanCast-0.4.0.apk",
            sha256 = "A".repeat(64),
            changelog = "  Bug fixes  ",
        ).validated()

        assertNotNull(m)
        assertEquals("0.4.0", m!!.version)
        assertEquals("LeanCast-0.4.0.apk", m.file)
        assertEquals(validSha, m.sha256)
        assertEquals("Bug fixes", m.changelog)
    }

    @Test
    fun `rejects invalid version, unsafe apk filename, or bad sha256`() {
        assertNull(UpdateManifest("", "LeanCast-0.4.0.apk", validSha).validated())
        assertNull(UpdateManifest("not-semver", "LeanCast-0.4.0.apk", validSha).validated())
        assertNull(UpdateManifest("0.4.0", "../evil.apk", validSha).validated())
        assertNull(UpdateManifest("0.4.0", "dir/LeanCast.apk", validSha).validated())
        assertNull(UpdateManifest("0.4.0", "LeanCast-0.4.0.exe", validSha).validated())
        assertNull(UpdateManifest("0.4.0", "LeanCast-0.4.0.apk", "short").validated())
        assertNull(UpdateManifest("0.4.0", "LeanCast-0.4.0.apk", "g".repeat(64)).validated())
    }
}

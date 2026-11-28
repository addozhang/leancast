package io.leancast.domain.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Spec: app-update — Parsing and validating `latest.json` without third-party JSON libs. */
class UpdateManifestParserTest {

    private val sha = "0123456789abcdef".repeat(4)

    @Test
    fun `parses valid multi-asset latest_json with escaped changelog`() {
        val json = """
            {
              "version": "0.4.0",
              "changelog": "- Opt-in updates\n- \"Fast\" fallback",
              "assets": {
                "android-universal": {
                  "file": "LeanCast-0.4.0.apk",
                  "sha256": "$sha"
                },
                "darwin-arm64": {
                  "file": "Other-0.4.0.dmg",
                  "sha256": "$sha"
                }
              }
            }
        """.trimIndent()

        val manifest = UpdateManifestParser.parse(json)
        assertNotNull(manifest)
        assertEquals("0.4.0", manifest!!.version)
        assertEquals("LeanCast-0.4.0.apk", manifest.file)
        assertEquals(sha, manifest.sha256)
        assertEquals("- Opt-in updates\n- \"Fast\" fallback", manifest.changelog)
    }

    @Test
    fun `rejects html error page, malformed json, missing asset, or unsafe filename`() {
        assertNull(UpdateManifestParser.parse("<html><body>502 Bad Gateway</body></html>"))
        assertNull(UpdateManifestParser.parse("{not-json"))
        assertNull(UpdateManifestParser.parse("""{"version":"0.4.0","assets":{}}"""))
        assertNull(
            UpdateManifestParser.parse(
                """{"version":"0.4.0","assets":{"android-universal":{"file":"../bad.apk","sha256":"$sha"}}}""",
            ),
        )
    }
}

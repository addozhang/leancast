package io.leancast.domain.update

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec: app-update — Ordered dl.solocraft.app primary + GitHub Releases fallback URLs. */
class UpdateUrlResolverTest {

    @Test
    fun `manifestUrls puts dl_solocraft_app first and GitHub Releases second`() {
        assertEquals(
            listOf(
                "https://dl.solocraft.app/leancast/latest.json",
                "https://github.com/addozhang/leancast/releases/latest/download/latest.json",
            ),
            UpdateUrlResolver.manifestUrls(),
        )
    }

    @Test
    fun `apkUrls normalizes trailing slashes and v-prefix`() {
        assertEquals(
            listOf(
                "https://dl.solocraft.app/leancast/v0.4.0/LeanCast-0.4.0.apk",
                "https://github.com/addozhang/leancast/releases/download/v0.4.0/LeanCast-0.4.0.apk",
            ),
            UpdateUrlResolver.apkUrls(
                version = "0.4.0",
                file = "LeanCast-0.4.0.apk",
                primaryBase = "https://dl.solocraft.app/",
            ),
        )
        assertEquals(
            listOf(
                "https://dl.solocraft.app/leancast/v0.4.0/LeanCast-0.4.0.apk",
                "https://github.com/addozhang/leancast/releases/download/v0.4.0/LeanCast-0.4.0.apk",
            ),
            UpdateUrlResolver.apkUrls(version = "v0.4.0", file = "LeanCast-0.4.0.apk"),
        )
    }
}

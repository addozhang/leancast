package io.leancast.domain.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: media-playback — Requirement: Compatible media fetching. */
class MediaHttpHeadersTest {

    @Test
    fun `browser-like user agent is attached to http and https URLs`() {
        listOf(
            "http://192.168.31.15:8000/e2e.mp4",
            "https://upos-sz-mirrorbilivideo1.bilivideo.com/x.m3u8",
            "http://vali-gb.com.cn/v.fhv",
        ).forEach { uri ->
            val headers = MediaHttpHeaders.forUri(uri)
            val ua = headers["User-Agent"]
            assertTrue("UA missing for $uri", ua != null && ua.length > 40)
            assertTrue(
                "UA not browser-like for $uri",
                ua!!.contains("Mozilla/5.0") && ua.contains("Chrome"),
            )
        }
    }

    @Test
    fun `accept header defaults to anything`() {
        assertEquals("*/*", MediaHttpHeaders.forUri("https://x.example/v.m3u8")["Accept"])
    }

    @Test
    fun `non-http schemes get no headers`() {
        assertEquals(emptyMap<String, String>(), MediaHttpHeaders.forUri("file:///tmp/v.mp4"))
        assertEquals(emptyMap<String, String>(), MediaHttpHeaders.forUri("content://media/1"))
    }

    @Test
    fun `returned map is immutable`() {
        val headers = MediaHttpHeaders.forUri("https://x.example/v.mp4")
        try {
            @Suppress("UNCHECKED_CAST")
            (headers as MutableMap<String, String>)["X"] = "1"
            throw AssertionError("expected immutability")
        } catch (_: UnsupportedOperationException) {
        } catch (_: ClassCastException) {
        }
    }
}

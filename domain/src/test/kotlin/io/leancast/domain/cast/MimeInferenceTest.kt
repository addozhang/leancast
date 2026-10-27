package io.leancast.domain.cast

import io.leancast.domain.upnp.MimeInference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Spec: media-playback — Compatible media fetching (real-world senders put
 * "m3u8" mid-path or in query, e.g. youku /playlist/m3u8?vid=...).
 */
class MimeInferenceTest {

    @Test
    fun `hls by classic extension`() {
        assertEquals(
            "application/vnd.apple.mpegurl",
            MimeInference.guessMimeType("https://upos-sz-mirrorbilivideo1.bilivideo.com/x.m3u8?sign=1"),
        )
    }

    @Test
    fun `hls when m3u8 appears mid-path or in query`() {
        assertEquals(
            "application/vnd.apple.mpegurl",
            MimeInference.guessMimeType("http://pl-ali.youku.com/playlist/m3u8?vid=XNjA5&dlnaopt=2"),
        )
        assertEquals(
            "application/vnd.apple.mpegurl",
            MimeInference.guessMimeType("http://cdn.x/get?type=m3u8&qn=80"),
        )
    }

    @Test
    fun `dash by extension`() {
        assertEquals(
            "application/dash+xml",
            MimeInference.guessMimeType("https://x.example/v.mpd?token=t"),
        )
    }

    @Test
    fun `plain progressive returns null`() {
        assertEquals(null, MimeInference.guessMimeType("http://192.168.31.15:8000/e2e.mp4"))
        assertEquals(null, MimeInference.guessMimeType("http://x.example/v.flv"))
        assertEquals(null, MimeInference.guessMimeType("https://x.example/stream"))
    }
}

package io.leancast.domain.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec: media-playback / receiver-app — multiple observers must coexist (service + player UI). */
class CastSessionListenerTest {

    private class Recording : CastSession.Listener {
        val seen = mutableListOf<TransportState>()
        override fun onStateChanged(state: TransportState) { seen += state }
    }

    private class QuietPort : PlayerPort {
        override fun prepareAndPlay(uri: String) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(ms: Long) = Unit
        override fun stop() = Unit
        override fun currentPositionMs() = 0L
        override fun currentDurationMs() = 0L
    }

    @Test
    fun `all registered listeners are notified`() {
        val session = CastSession(QuietPort())
        val a = Recording()
        val b = Recording()
        session.addListener(a)
        session.addListener(b)

        session.setUri("http://x/v.mp4", null)
        session.play()

        assertEquals(listOf(TransportState.NO_MEDIA_PRESENT, TransportState.TRANSITIONING), a.seen)
        assertEquals(a.seen, b.seen)
    }

    @Test
    fun `removed listener stops receiving`() {
        val session = CastSession(QuietPort())
        val a = Recording()
        session.addListener(a)
        session.setUri("http://x/v.mp4", null)
        session.removeListener(a)
        session.play()

        assertEquals(listOf(TransportState.NO_MEDIA_PRESENT), a.seen)
    }

    @Test
    fun `listener removal during notification is safe`() {
        val session = CastSession(QuietPort())
        val suicidal = object : CastSession.Listener {
            override fun onStateChanged(state: TransportState) {
                session.removeListener(this)
            }
        }
        val other = Recording()
        session.addListener(suicidal)
        session.addListener(other)
        session.setUri("http://x/v.mp4", null)
        assertTrue(other.seen.isNotEmpty())
    }
}

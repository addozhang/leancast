package io.leancast.domain.cast

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spec: receiver-app / media-playback — Surfacing the fullscreen player when a
 * cast starts or begins playing from the background service.
 */
class PlayerLaunchPolicyTest {

    @Test
    fun `TRANSITIONING and PLAYING both surface the player activity`() {
        assertTrue(PlayerLaunchPolicy.shouldSurfacePlayer(TransportState.TRANSITIONING))
        assertTrue(PlayerLaunchPolicy.shouldSurfacePlayer(TransportState.PLAYING))
    }

    @Test
    fun `idle, paused, and stopped states do not surface the player activity`() {
        assertFalse(PlayerLaunchPolicy.shouldSurfacePlayer(TransportState.NO_MEDIA_PRESENT))
        assertFalse(PlayerLaunchPolicy.shouldSurfacePlayer(TransportState.PAUSED_PLAYBACK))
        assertFalse(PlayerLaunchPolicy.shouldSurfacePlayer(TransportState.STOPPED))
    }
}

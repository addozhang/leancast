package io.leancast.domain.cast

/**
 * Decides when the background receiver service should surface the fullscreen
 * player activity in response to transport state transitions.
 */
object PlayerLaunchPolicy {

    /**
     * Returns true when transitioning to [state] should bring the player
     * activity to the foreground.
     *
     * Covering both [TransportState.TRANSITIONING] and [TransportState.PLAYING]
     * ensures that if the initial `TRANSITIONING` launch occurs while the screen
     * or launcher is still settling, or when playback resumes from pause/prepare,
     * the player is surfaced reliably.
     */
    fun shouldSurfacePlayer(state: TransportState): Boolean = when (state) {
        TransportState.TRANSITIONING, TransportState.PLAYING -> true
        TransportState.NO_MEDIA_PRESENT, TransportState.PAUSED_PLAYBACK, TransportState.STOPPED -> false
    }
}

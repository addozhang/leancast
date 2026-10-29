package io.leancast.domain.cast

/**
 * Renders the `GetCurrentTransportActions` value for the current state, so
 * senders know which controls to enable instead of guessing (and instead of
 * getting a 401 for the action itself).
 */
object TransportActions {

    fun forState(state: TransportState, hasMedia: Boolean): String = when (state) {
        TransportState.PLAYING -> "Pause,Stop,Seek"
        TransportState.PAUSED_PLAYBACK -> "Play,Stop,Seek"
        TransportState.TRANSITIONING -> "Stop"
        TransportState.STOPPED -> if (hasMedia) "Play,Seek" else ""
        TransportState.NO_MEDIA_PRESENT -> ""
    }
}

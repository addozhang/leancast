package io.leancast.domain.cast

/**
 * Renders the `GetCurrentTransportActions` value for the current state, so
 * senders know which controls to enable instead of guessing (and instead of
 * getting a 401 for the action itself).
 */
object TransportActions {

    fun forState(state: TransportState, hasMedia: Boolean, isLive: Boolean = false): String =
        when (state) {
            // Seek is meaningless on a live stream: the window has no fixed
            // positions, so advertising it would invite seeks we cannot honour.
            TransportState.PLAYING -> if (isLive) "Pause,Stop" else "Pause,Stop,Seek"
            TransportState.PAUSED_PLAYBACK -> if (isLive) "Play,Stop" else "Play,Stop,Seek"
            TransportState.TRANSITIONING -> "Stop"
            TransportState.STOPPED -> when {
                !hasMedia -> ""
                isLive -> "Play"
                else -> "Play,Seek"
            }
            TransportState.NO_MEDIA_PRESENT -> ""
        }
}

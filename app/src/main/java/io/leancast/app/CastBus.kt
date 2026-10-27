package io.leancast.app

import io.leancast.domain.cast.CastSession

/** Process-wide wiring shared by the service, control server, and UI. */
object CastBus {
    @Volatile var session: CastSession? = null
    @Volatile var port: ExoPlayerPort? = null
    @Volatile var endpoint: String = "" // "192.168.x.y:port" for the status screen
}

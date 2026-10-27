package io.leancast.domain.upnp

import io.leancast.domain.cast.CastSession

/**
 * Pure SOAP control dispatcher: maps a parsed [SoapRequest] to an HTTP status +
 * response body, driving the [CastSession]. The Android control server is a thin
 * HTTP shell around this.
 */
class SoapRouter(private val session: CastSession) {

    data class Result(val httpStatus: Int, val body: String)

    fun route(request: SoapRequest?): Result {
        if (request == null) return Result(400, SoapResponses.fault(402, "Invalid Args"))
        val (service, action, args) = request
        return when (service) {
            UpnpService.AVT -> routeAvt(action, args)
            UpnpService.RCS -> routeRcs(action, args)
            UpnpService.CM -> routeCm(action, args)
        }
    }

    private fun ok(action: String, service: UpnpService) =
        Result(200, SoapResponses.empty(action, service))

    private fun invalidAction() = Result(401, SoapResponses.fault(401, "Invalid Action"))

    private fun routeAvt(action: String, args: Map<String, String>): Result = when (action) {
        "SetAVTransportURI" -> {
            session.setUri(args["CurrentURI"], args["CurrentURIMetaData"])
            ok(action, UpnpService.AVT)
        }
        "Play" -> {
            session.play()
            ok(action, UpnpService.AVT)
        }
        "Pause" -> {
            session.pause()
            ok(action, UpnpService.AVT)
        }
        "Stop" -> {
            session.stop()
            ok(action, UpnpService.AVT)
        }
        "Seek" -> {
            val unit = args["Unit"] ?: ""
            val target = args["Target"]
            if (unit == "REL_TIME" && target != null) {
                UpnpTime.parseHmsToMs(target)?.let { session.seekTo(it) }
            }
            ok(action, UpnpService.AVT)
        }
        "GetPositionInfo" -> Result(
            200,
            SoapResponses.getPositionInfo(
                track = if (session.currentUri != null) 1 else 0,
                relTimeMs = session.positionMs(),
                durationMs = session.durationMs(),
                trackMetaData = session.currentMetaData,
                currentUri = session.currentUri,
            ),
        )
        "GetTransportInfo" -> Result(
            200,
            SoapResponses.getTransportInfo(session.state.name),
        )
        "GetMediaInfo" -> Result(
            200,
            SoapResponses.getMediaInfo(
                nrTracks = if (session.currentUri != null) 1 else 0,
                durationMs = session.durationMs(),
                currentUri = session.currentUri,
            ),
        )
        else -> invalidAction()
    }

    private fun routeRcs(action: String, args: Map<String, String>): Result = when (action) {
        "SetVolume" -> {
            args["DesiredVolume"]?.toIntOrNull()?.let { session.setVolume(it) }
            ok(action, UpnpService.RCS)
        }
        "GetVolume" -> Result(200, SoapResponses.getVolume(session.volume))
        "SetMute" -> ok(action, UpnpService.RCS)
        "GetMute" -> Result(200, SoapResponses.getMute(false))
        else -> invalidAction()
    }

    private fun routeCm(action: String, args: Map<String, String>): Result = when (action) {
        "GetProtocolInfo" -> Result(200, SoapResponses.getProtocolInfo())
        else -> invalidAction()
    }
}

package io.leancast.app

import fi.iki.elonen.NanoHTTPD
import io.leancast.domain.upnp.DeviceDescriptionDoc
import io.leancast.domain.upnp.RendererDescription
import io.leancast.domain.upnp.SoapParser
import io.leancast.domain.upnp.SoapRouter
import java.util.UUID

/**
 * HTTP control plane: device description, SCPDs, SOAP control endpoints and
 * GENA subscription stubs. Thin shell around the domain layer.
 *
 * Note: NanoHTTPD 2.3.1 has no SUBSCRIBE/UNSUBSCRIBE methods, so GENA requests
 * are recognized by path + headers (`SID` for renewal, `CALLBACK`/`NT` for new).
 */
class ControlServer(
    port: Int,
    private val descriptionProvider: () -> RendererDescription,
    private val router: SoapRouter,
) : NanoHTTPD(port) {

    /** True-bound port (differs from requested when fallback to ephemeral). */
    val boundPort: Int get() = listeningPort

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri.trimEnd('/')
        android.util.Log.i(
            TAG,
            "req ${session.method} $path from ${session.remoteIpAddress ?: "?"}",
        )
        return when {
            path == "/desc.xml" -> xml(deviceDoc.deviceXml())

            path == "/scpd/avt.xml" -> xml(deviceDoc.scpdAvt())
            path == "/scpd/rcs.xml" -> xml(deviceDoc.scpdRcs())
            path == "/scpd/cmn.xml" -> xml(deviceDoc.scpdCmn())

            path.startsWith("/ctl/") -> serveControl(session)

            path.startsWith("/evt/") -> serveEvent(session)

            path == "" -> xml(deviceDoc.deviceXml())

            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND, MIME_PLAINTEXT, "",
            )
        }
    }

    private val deviceDoc: DeviceDescriptionDoc
        get() = DeviceDescriptionDoc(descriptionProvider())

    private fun serveControl(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "POST only",
            )
        }
        val body = readBody(session)
        val soapAction = session.headers["soapaction"] ?: session.headers["SOAPACTION"]
        android.util.Log.i(TAG, "control action=$soapAction len=${body.length}")
        val result = router.route(SoapParser.parse(soapAction, body))
        val status = Response.Status.lookup(result.httpStatus) ?: Response.Status.OK
        return newFixedLengthResponse(status, "text/xml; charset=\"utf-8\"", result.body)
    }

    /** GENA: accept subscriptions and renewals; event delivery is a v1 non-goal. */
    private fun serveEvent(session: IHTTPSession): Response {
        val sid = session.headers["sid"] ?: session.headers["SID"]
        val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        response.addHeader("SID", sid ?: "uuid:${UUID.randomUUID()}")
        response.addHeader("TIMEOUT", "Second-1800")
        return response
    }

    private fun readBody(session: IHTTPSession): String = try {
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            val buffer = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = session.inputStream.read(buffer, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buffer, 0, read, Charsets.UTF_8)
        } else {
            ""
        }
    } catch (_: Exception) {
        ""
    }

    private fun xml(text: String): Response =
        newFixedLengthResponse(Response.Status.OK, "text/xml; charset=\"utf-8\"", text)

    companion object {
        private const val TAG = "LeanCastServer"

        /** Starts on [preferredPort], falling back to an ephemeral port. */
        fun startWithFallback(
            preferredPort: Int,
            descriptionProvider: () -> RendererDescription,
            router: SoapRouter,
        ): ControlServer {
            return try {
                ControlServer(preferredPort, descriptionProvider, router).apply { start() }
            } catch (_: Exception) {
                ControlServer(0, descriptionProvider, router).apply { start() }
            }
        }
    }
}

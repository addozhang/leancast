package io.leancast.app

import android.util.Log
import io.leancast.domain.upnp.DeviceDescriptionDoc
import io.leancast.domain.upnp.RendererDescription
import io.leancast.domain.upnp.SoapParser
import io.leancast.domain.upnp.SoapRouter
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP control plane: device description, SCPDs, SOAP control endpoints and
 * GENA subscription endpoints.
 *
 * Deliberately hand-rolled rather than NanoHTTPD: that library rejects
 * `SUBSCRIBE`/`UNSUBSCRIBE` while parsing the request line (its verb list is a
 * closed enum), which made GENA unreachable no matter what the handler did.
 * Only the handful of request shapes a DLNA sender emits are supported, and
 * responses are short and unbuffered.
 */
class ControlServer(
    private val port: Int,
    private val descriptionProvider: () -> RendererDescription,
    private val router: SoapRouter,
    private val events: SubscriptionRegistry,
    private val eventBody: (String) -> String,
    private val serverToken: String,
    private val callbackParser: (String?) -> List<String> = { SubscriptionRegistry.parseCallbacks(it) },
) {

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var running = false
    private val workerSeq = AtomicInteger(1)
    private val workers = ThreadPoolExecutor(
        CORE_WORKERS,
        MAX_WORKERS,
        WORKER_KEEP_ALIVE_SEC,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_CONNECTIONS),
    ) { runnable ->
        Thread(runnable, "leancast-http-${workerSeq.getAndIncrement()}").apply { isDaemon = true }
    }.apply {
        allowCoreThreadTimeOut(true)
    }

    /** True-bound port (differs from requested when falling back to ephemeral). */
    var boundPort: Int = 0
        private set

    fun start() {
        val bound = bind(port) ?: bind(0) ?: error("no free port for the control server")
        socket = bound
        boundPort = bound.localPort
        running = true
        Thread({ acceptLoop(bound) }, "leancast-http-accept").apply { isDaemon = true }.start()
        Log.i(TAG, "control server listening on $boundPort")
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        workers.shutdownNow()
    }

    private fun bind(candidate: Int): ServerSocket? = try {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(candidate), BACKLOG)
        }
    } catch (e: Exception) {
        Log.w(TAG, "port $candidate unavailable: ${e.javaClass.simpleName}")
        null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: Exception) {
                if (running) Log.w(TAG, "accept failed: ${e.javaClass.simpleName}")
                break
            }
            try {
                workers.execute { serve(client) }
            } catch (_: RejectedExecutionException) {
                Log.w(TAG, "http worker pool saturated, dropping connection")
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val head = readHead(input) ?: return
            val lines = head.split("\r\n")
            val requestLine = lines.firstOrNull()?.split(" ") ?: return
            if (requestLine.size < 2) {
                respond(client, Status.BAD_REQUEST, null, "bad request line".toByteArray())
                return
            }
            val method = requestLine[0].uppercase(Locale.US)
            val path = requestLine[1].substringBefore('?').trimEnd('/')
            val headers = headersOf(lines)
            val rawLength = headers["content-length"]?.toLongOrNull()
            if (rawLength != null && rawLength > MAX_BODY) {
                respond(client, Status.PAYLOAD_TOO_LARGE, TEXT, ByteArray(0))
                return
            }
            val bodyLength = (rawLength ?: 0L).coerceAtLeast(0L).toInt()
            if (bodyLength > 0 && headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
                client.getOutputStream().apply {
                    write(CONTINUE_100)
                    flush()
                }
            }
            val body = readBody(input, bodyLength)

            val response = dispatch(method, path, headers, body)
            Log.i(
                TAG,
                "$method $path -> ${response.status.code}${response.logDetail?.let { " $it" } ?: ""}",
            )
            respond(client, response.status, response.contentType, response.body, response.extra, method == "HEAD")
            response.afterSend?.invoke()
        } catch (e: Exception) {
            Log.d(TAG, "request failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private class Response(
        val status: Status,
        val contentType: String?,
        val body: ByteArray,
        val extra: Map<String, String> = emptyMap(),
        val logDetail: String? = null,
        val afterSend: (() -> Unit)? = null,
    )

    private fun dispatch(method: String, path: String, headers: Map<String, String>, body: String): Response =
        when {
            method == "GET" || method == "HEAD" -> when {
                path == "" || path == "/desc.xml" ->
                    Response(Status.OK, XML, deviceDoc().deviceXml().toByteArray())
                path == "/scpd/avt.xml" -> Response(Status.OK, XML, deviceDoc().scpdAvt().toByteArray())
                path == "/scpd/rcs.xml" -> Response(Status.OK, XML, deviceDoc().scpdRcs().toByteArray())
                path == "/scpd/cmn.xml" -> Response(Status.OK, XML, deviceDoc().scpdCmn().toByteArray())
                else -> Response(Status.NOT_FOUND, TEXT, ByteArray(0))
            }
            method == "POST" && path.startsWith("/ctl/") -> serveControl(headers, body)
            (method == "SUBSCRIBE" || method == "UNSUBSCRIBE") && path.startsWith("/evt/") ->
                serveEvent(method, path, headers)
            method == "POST" && path.startsWith("/evt/") -> serveEvent("SUBSCRIBE", path, headers)
            else -> Response(Status.METHOD_NOT_ALLOWED, TEXT, ByteArray(0))
        }

    private fun serveControl(headers: Map<String, String>, body: String): Response {
        val soapAction = headers["soapaction"]
        val result = router.route(SoapParser.parse(soapAction, body))
        val action = soapAction?.substringAfterLast('#')?.trim('"')
        // UPnP Device Architecture §3.2.2 specifies HTTP 500 for UPnP SOAP faults
        // (such as UPnP error code 401 Invalid Action); map 401 explicitly rather
        // than relying on an enum fallback.
        val status = when (result.httpStatus) {
            200 -> Status.OK
            400 -> Status.BAD_REQUEST
            401 -> Status.SERVER_ERROR
            else -> Status.fromCode(result.httpStatus)
        }
        return Response(
            status,
            XML,
            result.body.toByteArray(Charsets.UTF_8),
            logDetail = buildString {
                append("action=")
                append(action ?: "-")
                if (result.httpStatus != 200) append(" FAILED")
            },
        )
    }

    /**
     * SUBSCRIBE creates a subscription and pushes the mandatory initial event
     * (SEQ 0) strictly *after* the 200 OK response carrying `SID` is flushed to
     * the client, avoiding a race where `NOTIFY` arrives before the sender knows
     * its own `SID`. A request carrying only SID renews; UNSUBSCRIBE drops it.
     */
    private fun serveEvent(method: String, path: String, headers: Map<String, String>): Response {
        val sid = headers["sid"]
        val callbacks = callbackParser(headers["callback"])
        val timeout = SubscriptionRegistry.parseTimeout(headers["timeout"])

        if (method == "UNSUBSCRIBE") {
            val removed = sid != null && events.unsubscribe(sid)
            return Response(
                if (removed) Status.OK else Status.PRECONDITION_FAILED,
                TEXT,
                ByteArray(0),
                logDetail = "unsubscribe sid=${sid ?: "-"} ok=$removed",
            )
        }

        if (callbacks.isEmpty() && sid != null) {
            val renewed = events.renew(sid, timeout)
            return Response(
                if (renewed) Status.OK else Status.PRECONDITION_FAILED,
                TEXT,
                ByteArray(0),
                extra = if (renewed) genaHeaders(sid, timeout) else emptyMap(),
                logDetail = "renew sid=$sid ok=$renewed",
            )
        }

        if (callbacks.isEmpty()) {
            return Response(Status.PRECONDITION_FAILED, TEXT, ByteArray(0), logDetail = "subscribe no callback")
        }

        val service = path.substringAfterLast('/')
        val newSid = events.subscribe(service, callbacks, timeout)
        val initialBody = eventBody(service)
        return Response(
            Status.OK,
            TEXT,
            ByteArray(0),
            extra = genaHeaders(newSid, timeout),
            logDetail = "subscribe service=$service callback=${callbacks.first()}",
            afterSend = { events.publishInitial(newSid, initialBody) },
        )
    }

    private fun genaHeaders(sid: String, timeoutSeconds: Int): Map<String, String> = mapOf(
        "SID" to sid,
        "TIMEOUT" to "Second-$timeoutSeconds",
        "SERVER" to serverToken,
        "DATE" to rfc1123(),
    )

    private fun deviceDoc(): DeviceDescriptionDoc = DeviceDescriptionDoc(descriptionProvider())

    private fun headersOf(lines: List<String>): Map<String, String> {
        val map = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) map[line.substring(0, colon).trim().lowercase(Locale.US)] = line.substring(colon + 1).trim()
        }
        return map
    }

    private fun readHead(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        var tail = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            buffer.write(b)
            tail = (tail shl 8) or b
            if (tail == CRLFCRLF) return buffer.toString("ISO-8859-1")
            if (buffer.size() > MAX_HEAD) return null
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(buffer, 0, read, Charsets.UTF_8)
    }

    private fun respond(
        client: Socket,
        status: Status,
        contentType: String?,
        body: ByteArray,
        extra: Map<String, String> = emptyMap(),
        headOnly: Boolean = false,
    ) {
        val out: OutputStream = client.getOutputStream()
        val header = buildString {
            append("HTTP/1.1 ${status.code} ${status.reason}\r\n")
            if (contentType != null) append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            extra.forEach { (k, v) -> append("$k: $v\r\n") }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        out.write(header)
        if (!headOnly && body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun rfc1123(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date())

    private enum class Status(val code: Int, val reason: String) {
        OK(200, "OK"),
        BAD_REQUEST(400, "Bad Request"),
        NOT_FOUND(404, "Not Found"),
        METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
        PRECONDITION_FAILED(412, "Precondition Failed"),
        PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
        SERVER_ERROR(500, "Internal Server Error"),
        ;

        companion object {
            fun fromCode(code: Int): Status = entries.firstOrNull { it.code == code } ?: SERVER_ERROR
        }
    }

    companion object {
        private const val TAG = "LeanCastHttp"
        private const val XML = "text/xml; charset=\"utf-8\""
        private const val TEXT = "text/plain; charset=\"utf-8\""
        private const val BACKLOG = 20
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 256 * 1024
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val CRLFCRLF = 0x0D0A0D0A
        private const val CORE_WORKERS = 4
        private const val MAX_WORKERS = 16
        private const val WORKER_KEEP_ALIVE_SEC = 60L
        private const val MAX_QUEUED_CONNECTIONS = 64
        private val CONTINUE_100 = "HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1)

        /** Starts on [preferredPort], falling back to an ephemeral port. */
        fun startWithFallback(
            preferredPort: Int,
            descriptionProvider: () -> RendererDescription,
            router: SoapRouter,
            events: SubscriptionRegistry,
            eventBody: (String) -> String,
            serverToken: String,
        ): ControlServer = ControlServer(
            preferredPort,
            descriptionProvider,
            router,
            events,
            eventBody,
            serverToken,
        ).apply { start() }
    }
}

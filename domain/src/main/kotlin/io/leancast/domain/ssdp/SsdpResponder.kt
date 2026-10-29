package io.leancast.domain.ssdp

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** One advertised (or searchable) target of the device. */
enum class SsdpTarget(val nt: String) {
    ROOT("upnp:rootdevice"),
    DEVICE("urn:schemas-upnp-org:device:MediaRenderer:1"),
    AVT("urn:schemas-upnp-org:service:AVTransport:1"),
    RCS("urn:schemas-upnp-org:service:RenderingControl:1"),
    CM("urn:schemas-upnp-org:service:ConnectionManager:1"),
    ;

    val usn: (String) -> String = { uuid -> "$uuid::$nt" }
}

/**
 * Pure SSDP message factory: search responses, aliveness notifications,
 * departure notifications, and MX-bounded response delays.
 */
class SsdpResponder(
    private val identity: SsdpIdentity,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    /** All search responses owed for an inbound M-SEARCH (empty when unsupported). */
    fun responsesFor(search: SsdpInbound.MSearch): List<ByteArray> {
        val st = search.st.trim()
        // A UDN-targeted search must be answered with the UDN itself as ST and
        // USN; senders that cached our identity look us up exactly this way.
        if (st.startsWith("uuid:", ignoreCase = true)) {
            if (!st.equals(identity.uuid, ignoreCase = true)) return emptyList()
            return listOf(renderResponse(SsdpTarget.ROOT, stOverride = identity.uuid, usnOverride = identity.uuid))
        }
        return targetsFor(st).map { renderResponse(it) }
    }

    fun aliveMessages(): List<ByteArray> = SsdpTarget.entries.map { renderAlive(it) }

    fun byebyeMessages(): List<ByteArray> = SsdpTarget.entries.map { renderByebye(it) }

    /**
     * Uniform delay in [0, min(MX,5)] seconds; MX missing/unparseable defaults to 1 s.
     * Pure and injectable-random for tests.
     */
    fun responseDelayMs(mxSeconds: Int?, random: Random = Random.Default): Long {
        val boundSeconds = (mxSeconds ?: 1).coerceIn(0, MAX_MX_SECONDS)
        return random.nextInt(boundSeconds * 1000 + 1).toLong()
    }

    private fun targetsFor(st: String): List<SsdpTarget> = when (st) {
        "ssdp:all" -> SsdpTarget.entries.toList()
        else -> SsdpTarget.entries.filter { it.nt == st }
    }

    private fun renderResponse(
        target: SsdpTarget,
        stOverride: String? = null,
        usnOverride: String? = null,
    ): ByteArray = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=${identity.maxAgeSeconds}\r\n")
        append("DATE: ${rfc1123()}\r\n")
        append("EXT:\r\n")
        append("LOCATION: ${identity.location}\r\n")
        append("SERVER: ${identity.serverToken}\r\n")
        append("ST: ${stOverride ?: target.nt}\r\n")
        append("USN: ${usnOverride ?: target.usn(identity.uuid)}\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    private fun renderAlive(target: SsdpTarget): ByteArray = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: $SSDP_HOST\r\n")
        append("CACHE-CONTROL: max-age=${identity.maxAgeSeconds}\r\n")
        append("LOCATION: ${identity.location}\r\n")
        append("NT: ${target.nt}\r\n")
        append("NTS: ssdp:alive\r\n")
        append("SERVER: ${identity.serverToken}\r\n")
        append("USN: ${target.usn(identity.uuid)}\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    private fun renderByebye(target: SsdpTarget): ByteArray = buildString {
        append("NOTIFY * HTTP/1.1\r\n")
        append("HOST: $SSDP_HOST\r\n")
        append("NT: ${target.nt}\r\n")
        append("NTS: ssdp:byebye\r\n")
        append("USN: ${target.usn(identity.uuid)}\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    private fun rfc1123(): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("GMT") }
            .format(Date(clockMs()))

    companion object {
        const val SSDP_HOST = "239.255.255.250:1900"
        const val SSDP_PORT = 1900
        const val MAX_MX_SECONDS = 5
    }
}

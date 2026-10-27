package io.leancast.domain.ssdp

import java.net.InetAddress

/** Inbound SSDP datagram, classified. */
sealed interface SsdpInbound {

    /** A discovery request we may answer. */
    data class MSearch(
        val st: String,
        val mxSeconds: Int?,
        val man: String?,
        val from: InetAddress,
        val fromPort: Int = 0,
    ) : SsdpInbound

    /** Anything we do not act on (other devices' traffic, malformed frames). */
    data class Ignored(val reason: String) : SsdpInbound
}

/** Parses raw SSDP datagrams. Pure; no sockets here. */
object SsdpMessageParser {

    fun parse(datagram: ByteArray, from: InetAddress, fromPort: Int = 0): SsdpInbound {
        val text = datagram.decodeToString().trimEnd('\u0000')
        val firstLineEnd = text.indexOf("\r\n").let { if (it == -1) text.length else it }
        val startLine = text.substring(0, firstLineEnd).trim { it == ' ' || it == '\r' || it == '\n' }
        if (!startLine.startsWith("M-SEARCH")) return SsdpInbound.Ignored("not M-SEARCH: $startLine")

        val headers = parseHeaders(text.substring(firstLineEnd))
        val man = headers["MAN"]?.replace("\"", "")?.trim()
        if (man.isNullOrEmpty()) return SsdpInbound.Ignored("missing MAN")
        if (!man.contains("ssdp:discover", ignoreCase = true)) {
            return SsdpInbound.Ignored("unknown MAN: $man")
        }
        val st = headers["ST"]?.trim()
        if (st.isNullOrEmpty()) return SsdpInbound.Ignored("missing ST")

        return SsdpInbound.MSearch(
            st = st,
            mxSeconds = headers["MX"]?.trim()?.toIntOrNull(),
            man = man,
            from = from,
            fromPort = fromPort,
        )
    }

    private fun parseHeaders(block: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        block.split("\r\n").forEach { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line.substring(0, idx).trim().uppercase()] = line.substring(idx + 1).trim()
            }
        }
        return map
    }
}

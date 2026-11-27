package io.leancast.domain.upnp

import java.net.URI

/** Pure GENA header parsing, callback validation, and event sequence arithmetic. */
object GenaProtocol {

    const val DEFAULT_TIMEOUT = 1800
    const val MIN_TIMEOUT = 60
    const val MAX_TIMEOUT = 86_400
    const val MAX_CALLBACKS_PER_SUB = 4
    private const val SECOND_PREFIX = "Second-"

    /** Parses `TIMEOUT: Second-1800` / `SECOND-300` / `Second-infinite`; defaults to 1800 s. */
    fun parseTimeout(raw: String?): Int {
        val value = raw?.trim() ?: return DEFAULT_TIMEOUT
        if (!value.startsWith(SECOND_PREFIX, ignoreCase = true)) return DEFAULT_TIMEOUT
        val tail = value.substring(SECOND_PREFIX.length).trim()
        if (tail.equals("infinite", ignoreCase = true)) return DEFAULT_TIMEOUT
        return tail.toIntOrNull()?.coerceIn(MIN_TIMEOUT, MAX_TIMEOUT) ?: DEFAULT_TIMEOUT
    }

    /**
     * Extracts valid HTTP callback URLs from a GENA `CALLBACK` header, rejecting
     * non-HTTP schemes, loopback/wildcard targets, and control characters, and
     * capping the list size per subscription.
     */
    fun parseCallbacks(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return Regex("<([^>]+)>")
            .findAll(raw)
            .map { it.groupValues[1].trim() }
            .filter(::isAllowedCallbackUrl)
            .distinct()
            .take(MAX_CALLBACKS_PER_SUB)
            .toList()
    }

    fun isAllowedCallbackUrl(url: String): Boolean {
        if (url.isEmpty() || url.any { it == '\r' || it == '\n' || it < ' ' }) return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host?.trim()?.lowercase() ?: return false
        if (host.isEmpty()) return false
        if (host == "localhost" || host == "0.0.0.0" || host.startsWith("127.")) return false
        if (host == "::1" || host == "[::1]") return false
        if (uri.port != -1 && uri.port !in 1..65535) return false
        return true
    }

    /**
     * Advances a GENA event key (`SEQ`) per UPnP Device Architecture §4.2.1:
     * unsigned 32-bit counter that starts at `0` for the initial event, then
     * increments `1..4294967295` and wraps from `4294967295` back to `1`.
     */
    fun nextSeq(current: Long): Long {
        val next = (current + 1L) and 0xFFFF_FFFFL
        return if (next == 0L) 1L else next
    }
}

package io.leancast.domain.ssdp

/**
 * Pure formatting and readiness rules for the receiver's LAN endpoint and
 * SSDP `LOCATION` URL.
 *
 * When a TV boots and starts the service via `BOOT_COMPLETED`, DHCP/Wi-Fi may
 * not have assigned a site-local IPv4 address yet (`lanIp == null`). The
 * receiver must treat that state as unready rather than freezing `0.0.0.0`
 * into its SSDP `LOCATION` header.
 */
object LanEndpointPolicy {

    /**
     * Returns true when [ip] is a usable, non-wildcard, non-loopback LAN IPv4
     * address string.
     */
    fun isUsableLanIp(ip: String?): Boolean {
        val trimmed = ip?.trim().orEmpty()
        if (trimmed.isEmpty()) return false
        if (trimmed == "0.0.0.0") return false
        if (trimmed.startsWith("127.")) return false
        return true
    }

    /**
     * Formats `<ip>:<port>` for the UI and `CastBus.endpoint`, or returns `""`
     * when no usable LAN IP is available yet.
     */
    fun formatEndpoint(ip: String?, port: Int): String {
        if (!isUsableLanIp(ip) || port <= 0) return ""
        return "${ip!!.trim()}:$port"
    }

    /**
     * Builds the absolute UPnP device description URL (`http://<ip>:<port>/desc.xml`),
     * or returns `null` when offline so SSDP never advertises `0.0.0.0`.
     */
    fun locationUrl(ip: String?, port: Int): String? {
        val ep = formatEndpoint(ip, port)
        if (ep.isEmpty()) return null
        return "http://$ep/desc.xml"
    }
}

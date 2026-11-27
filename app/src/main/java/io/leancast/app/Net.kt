package io.leancast.app

import java.net.Inet4Address
import java.net.NetworkInterface

/** LAN address helpers for composing SSDP LOCATION and description URLs. */
object Net {

    /** First non-loopback, site-local IPv4 address, or null when offline. */
    fun lanIpv4(): String? = lanInterfaceAndIpv4()?.second

    /**
     * Best active non-loopback [NetworkInterface] that carries a site-local
     * IPv4 address, paired with that address's host string.
     *
     * Prefers physical multicast-capable LAN interfaces (`eth*`/`wlan*`) and
     * skips VPN tunnels (`tun*`/`ppp*`), virtual, and Wi-Fi Direct (`p2p*`)
     * interfaces when a real LAN interface is present.
     */
    fun lanInterfaceAndIpv4(): Pair<NetworkInterface, String>? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.mapNotNull { nif ->
                val ipv4 = nif.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                ipv4?.hostAddress?.let { nif to it }
            }
            ?.toList()
            .orEmpty()

        if (candidates.isEmpty()) return@runCatching null

        candidates.minByOrNull { (nif, _) -> interfaceRank(nif) }
    }.getOrNull()

    private fun interfaceRank(nif: NetworkInterface): Int {
        val name = nif.name.orEmpty().lowercase()
        val isTunnelOrVirtual = nif.isPointToPoint || nif.isVirtual ||
            name.startsWith("tun") || name.startsWith("tap") ||
            name.startsWith("ppp") || name.startsWith("p2p") ||
            name.startsWith("rmnet") || name.startsWith("dummy")
        val multicast = runCatching { nif.supportsMulticast() }.getOrDefault(false)
        return when {
            !isTunnelOrVirtual && multicast && (name.startsWith("eth") || name.startsWith("wlan")) -> 0
            !isTunnelOrVirtual && multicast -> 1
            !isTunnelOrVirtual -> 2
            else -> 3
        }
    }
}

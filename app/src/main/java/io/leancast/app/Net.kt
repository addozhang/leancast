package io.leancast.app

import java.net.Inet4Address
import java.net.NetworkInterface

/** LAN address helpers for composing SSDP LOCATION and description URLs. */
object Net {

    /** First non-loopback, site-local IPv4 address, or null when offline. */
    fun lanIpv4(): String? = lanInterfaceAndIpv4()?.second

    /**
     * First active non-loopback [NetworkInterface] that carries a site-local
     * IPv4 address, paired with that address's host string.
     */
    fun lanInterfaceAndIpv4(): Pair<NetworkInterface, String>? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
        interfaces.asSequence()
            .filter { it.isUp && !it.isLoopback }
            .firstNotNullOfOrNull { nif ->
                val ipv4 = nif.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { it.isSiteLocalAddress }
                ipv4?.hostAddress?.let { nif to it }
            }
    }.getOrNull()
}

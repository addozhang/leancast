package io.leancast.app

import java.net.Inet4Address
import java.net.NetworkInterface

/** LAN address helpers for composing SSDP LOCATION and description URLs. */
object Net {

    /** First non-loopback, site-local IPv4 address, or null when offline. */
    fun lanIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

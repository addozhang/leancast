package io.leancast.domain.ssdp

/**
 * Everything SSDP messages need to know about this renderer.
 * Injected by the app layer — the domain never hardcodes a device or product name.
 */
data class SsdpIdentity(
    /** UDN value including the `uuid:` prefix, stable across restarts. */
    val uuid: String,
    /** Absolute device description URL, e.g. `http://192.168.31.47:49152/desc.xml`. */
    val location: String,
    /** SERVER header token, e.g. `Android/9 UPnP/1.0 <stack>/1.0`. */
    val serverToken: String,
    val maxAgeSeconds: Int = 1800,
)

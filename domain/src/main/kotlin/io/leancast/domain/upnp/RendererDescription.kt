package io.leancast.domain.upnp

/** Identifying data injected into the device description. Nothing here is hardcoded downstream. */
data class RendererDescription(
    val friendlyName: String,
    /** UDN including `uuid:` prefix, stable across restarts. */
    val uuid: String,
    val manufacturer: String,
    val modelName: String,
    /** e.g. `http://192.168.31.47:49152` — relative service URLs resolve against this. */
    val baseUrl: String,
)

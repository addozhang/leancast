package io.leancast.domain.upnp

/** The three UPnP AV services this renderer exposes. */
enum class UpnpService(val serviceType: String, val pathSegment: String) {
    AVT("urn:schemas-upnp-org:service:AVTransport:1", "avt"),
    RCS("urn:schemas-upnp-org:service:RenderingControl:1", "rcs"),
    CM("urn:schemas-upnp-org:service:ConnectionManager:1", "cmn"),
    ;

    companion object {
        fun fromUrn(urn: String?): UpnpService? =
            entries.firstOrNull { it.serviceType == urn }
    }
}

package io.leancast.domain.upnp

/**
 * Builds the GENA `NOTIFY` bodies for UPnP AV services.
 *
 * The AVT / RCS state models are evented through a single `LastChange` state
 * variable whose value is itself an escaped XML document — that is why the
 * inner `<Event>` payload goes through [XmlUtil.escape] rather than being
 * emitted as markup.
 */
object LastChangeEvent {

    private const val PROPSET_NS = "urn:schemas-upnp-org:event-1-0"
    private const val AVT_NS = "urn:schemas-upnp-org:metadata-1-0/AVT/"
    private const val RCS_NS = "urn:schemas-upnp-org:metadata-1-0/RCS/"

    /** Values AVTransport senders rely on; `CurrentTrackDuration` is `0:00:00` when unknown (live). */
    fun avTransport(
        transportState: String,
        currentUri: String?,
        currentTrackDuration: String,
        currentTrackMetaData: String? = null,
    ): String {
        val inner = buildString {
            append("<Event xmlns=\"$AVT_NS\"><InstanceID val=\"0\">")
            append("<TransportState val=\"$transportState\"/>")
            append("<TransportStatus val=\"OK\"/>")
            append("<TransportPlaySpeed val=\"1\"/>")
            append("<CurrentTrackURI val=\"${XmlUtil.escape(currentUri ?: "")}\"/>")
            append("<CurrentTrackDuration val=\"$currentTrackDuration\"/>")
            append("<CurrentMediaDuration val=\"$currentTrackDuration\"/>")
            if (currentTrackMetaData != null) {
                append("<CurrentTrackMetaData val=\"${XmlUtil.escape(currentTrackMetaData)}\"/>")
            }
            append("<CurrentTransportActions val=\"${XmlUtil.escape(actionsFor(transportState))}\"/>")
            append("</InstanceID></Event>")
        }
        return propertyset(inner)
    }

    fun renderingControl(volume: Int, mute: Boolean): String {
        val inner = "<Event xmlns=\"$RCS_NS\"><InstanceID val=\"0\">" +
            "<Volume channel=\"Master\" val=\"$volume\"/>" +
            "<Mute channel=\"Master\" val=\"${if (mute) 1 else 0}\"/>" +
            "</InstanceID></Event>"
        return propertyset(inner)
    }

    private fun propertyset(innerEvent: String): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\r\n" +
            "<e:propertyset xmlns:e=\"$PROPSET_NS\">" +
            "<e:property><LastChange>${XmlUtil.escape(innerEvent)}</LastChange></e:property>" +
            "</e:propertyset>"

    private fun actionsFor(transportState: String): String = when (transportState) {
        "PLAYING" -> "Pause,Stop,Seek"
        "PAUSED_PLAYBACK" -> "Play,Stop,Seek"
        "TRANSITIONING" -> "Stop"
        "STOPPED" -> "Play,Seek"
        else -> ""
    }
}

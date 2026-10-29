package io.leancast.domain.upnp

/** Renders SOAP 1.1 response envelopes for the renderer's actions. */
object SoapResponses {

    private const val ENV = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val ENC = "http://schemas.xmlsoap.org/soap/encoding/"
    private const val CRLF = "\r\n"

    /**
     * Wraps [inner] in a SOAP 1.1 envelope.
     *
     * Built by concatenation rather than a `"""` template: an XML declaration
     * must start at byte 0, and any leading indentation (which `trimIndent()`
     * does not remove once the interpolated body has zero-indent lines) makes
     * the document unparseable for strict clients. The `s:` prefix used by
     * `encodingStyle` must also be declared.
     */
    private fun envelope(inner: String): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>").append(CRLF)
        append("<SOAP-ENV:Envelope xmlns:SOAP-ENV=\"$ENV\" xmlns:s=\"$ENV\" s:encodingStyle=\"$ENC\">").append(CRLF)
        append("<SOAP-ENV:Body>").append(CRLF)
        append(inner.trim())
        append(CRLF)
        append("</SOAP-ENV:Body>").append(CRLF)
        append("</SOAP-ENV:Envelope>")
    }

    fun empty(action: String, service: UpnpService): String =
        envelope("<u:${action}Response xmlns:u=\"${service.serviceType}\"></u:${action}Response>")

    fun fault(upnpErrorCode: Int, description: String): String = envelope(
        """
        <SOAP-ENV:Fault>
          <faultcode>SOAP-ENV:Client</faultcode>
          <faultstring>UPnPError</faultstring>
          <detail>
            <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
              <errorCode>$upnpErrorCode</errorCode>
              <errorDescription>${XmlUtil.escape(description)}</errorDescription>
            </UPnPError>
          </detail>
        </SOAP-ENV:Fault>
        """.trimIndent(),
    )

    fun getPositionInfo(
        track: Int,
        relTimeMs: Long,
        durationMs: Long,
        trackMetaData: String?,
        currentUri: String?,
    ): String {
        val rel = UpnpTime.renderMsToHms(relTimeMs)
        val duration = UpnpTime.renderMsToHms(durationMs)
        // Empty rather than "NOT_IMPLEMENTED": clients commonly feed this field
        // straight into an XML/DIDL parser.
        val meta = XmlUtil.escape(trackMetaData)
        return envelope(
            """
            <u:GetPositionInfoResponse xmlns:u="${UpnpService.AVT.serviceType}">
              <Track>$track</Track>
              <TrackDuration>$duration</TrackDuration>
              <TrackMetaData>$meta</TrackMetaData>
              <TrackURI>${XmlUtil.escape(currentUri)}</TrackURI>
              <RelTime>$rel</RelTime>
              <AbsTime>$rel</AbsTime>
              <RelCount>2147483647</RelCount>
              <AbsCount>2147483647</AbsCount>
            </u:GetPositionInfoResponse>
            """.trimIndent(),
        )
    }

    fun getTransportInfo(state: String): String = envelope(
        """
        <u:GetTransportInfoResponse xmlns:u="${UpnpService.AVT.serviceType}">
          <CurrentTransportState>$state</CurrentTransportState>
          <CurrentTransportStatus>OK</CurrentTransportStatus>
          <CurrentSpeed>1</CurrentSpeed>
        </u:GetTransportInfoResponse>
        """.trimIndent(),
    )

    fun getMediaInfo(nrTracks: Int, durationMs: Long, currentUri: String?): String = envelope(
        """
        <u:GetMediaInfoResponse xmlns:u="${UpnpService.AVT.serviceType}">
          <NrTracks>$nrTracks</NrTracks>
          <MediaDuration>${UpnpTime.renderMsToHms(durationMs)}</MediaDuration>
          <CurrentURI>${XmlUtil.escape(currentUri)}</CurrentURI>
          <CurrentURIMetaData></CurrentURIMetaData>
        </u:GetMediaInfoResponse>
        """.trimIndent(),
    )

    fun getVolume(currentVolume: Int): String = envelope(
        """
        <u:GetVolumeResponse xmlns:u="${UpnpService.RCS.serviceType}">
          <CurrentVolume>$currentVolume</CurrentVolume>
        </u:GetVolumeResponse>
        """.trimIndent(),
    )

    fun getMute(currentMute: Boolean): String = envelope(
        """
        <u:GetMuteResponse xmlns:u="${UpnpService.RCS.serviceType}">
          <CurrentMute>${if (currentMute) 1 else 0}</CurrentMute>
        </u:GetMuteResponse>
        """.trimIndent(),
    )

    private val sinkProtocols = listOf(
        "http-get:*:video/mp4:*",
        "http-get:*:application/vnd.apple.mpegurl:*",
        "http-get:*:application/x-mpegurl:*",
        "http-get:*:video/mp2t:*",
        "http-get:*:video/x-flv:*",
        "http-get:*:video/x-matroska:*",
        "http-get:*:audio/mpeg:*",
        "http-get:*:audio/mp4:*",
        "http-get:*:image/jpeg:*",
        "http-get:*:image/png:*",
    ).joinToString(",")

    fun getProtocolInfo(): String = envelope(
        """
        <u:GetProtocolInfoResponse xmlns:u="${UpnpService.CM.serviceType}">
          <Source></Source>
          <Sink>$sinkProtocols</Sink>
        </u:GetProtocolInfoResponse>
        """.trimIndent(),
    )

    fun getTransportSettings(): String = envelope(
        """
        <u:GetTransportSettingsResponse xmlns:u="${UpnpService.AVT.serviceType}">
          <PlayMode>NORMAL</PlayMode>
          <RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>
        </u:GetTransportSettingsResponse>
        """.trimIndent(),
    )

    fun getDeviceCapabilities(): String = envelope(
        """
        <u:GetDeviceCapabilitiesResponse xmlns:u="${UpnpService.AVT.serviceType}">
          <PlayMedia>NETWORK,NONE</PlayMedia>
          <RecMedia>NOT_IMPLEMENTED</RecMedia>
          <RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>
        </u:GetDeviceCapabilitiesResponse>
        """.trimIndent(),
    )

    /** [actions] is a comma-separated subset of `Play,Pause,Stop,Seek`. */
    fun getCurrentTransportActions(actions: String): String = envelope(
        """
        <u:GetCurrentTransportActionsResponse xmlns:u="${UpnpService.AVT.serviceType}">
          <Actions>$actions</Actions>
        </u:GetCurrentTransportActionsResponse>
        """.trimIndent(),
    )

    fun getCurrentConnectionIDs(ids: String = "0"): String = envelope(
        """
        <u:GetCurrentConnectionIDsResponse xmlns:u="${UpnpService.CM.serviceType}">
          <ConnectionIDs>$ids</ConnectionIDs>
        </u:GetCurrentConnectionIDsResponse>
        """.trimIndent(),
    )

    fun getCurrentConnectionInfo(protocolInfo: String): String = envelope(
        """
        <u:GetCurrentConnectionInfoResponse xmlns:u="${UpnpService.CM.serviceType}">
          <RcsID>0</RcsID>
          <AVTransportID>0</AVTransportID>
          <ProtocolInfo>${XmlUtil.escape(protocolInfo)}</ProtocolInfo>
          <PeerConnectionManager></PeerConnectionManager>
          <PeerConnectionID>-1</PeerConnectionID>
          <Direction>Input</Direction>
          <Status>OK</Status>
        </u:GetCurrentConnectionInfoResponse>
        """.trimIndent(),
    )

    fun listPresets(): String = envelope(
        """
        <u:ListPresetsResponse xmlns:u="${UpnpService.RCS.serviceType}">
          <CurrentPresetNameList>FactoryDefaults</CurrentPresetNameList>
        </u:ListPresetsResponse>
        """.trimIndent(),
    )

    fun getVolumeDbRange(): String = envelope(
        """
        <u:GetVolumeDBRangeResponse xmlns:u="${UpnpService.RCS.serviceType}">
          <MinValue>-32768</MinValue>
          <MaxValue>32767</MaxValue>
        </u:GetVolumeDBRangeResponse>
        """.trimIndent(),
    )
}

package io.leancast.domain.upnp

/** Renders SOAP 1.1 response envelopes for the renderer's actions. */
object SoapResponses {

    private const val ENV = "http://schemas.xmlsoap.org/soap/envelope/"
    private const val ENC = "http://schemas.xmlsoap.org/soap/encoding/"

    private fun envelope(inner: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <SOAP-ENV:Envelope xmlns:SOAP-ENV="$ENV" s:encodingStyle="$ENC">
          <SOAP-ENV:Body>
        $inner
          </SOAP-ENV:Body>
        </SOAP-ENV:Envelope>
    """.trimIndent().replace("\n", "\r\n")

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
        val meta = trackMetaData?.let { XmlUtil.escape(it) } ?: "NOT_IMPLEMENTED"
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
          <CurrentURIMetaData>NOT_IMPLEMENTED</CurrentURIMetaData>
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
}

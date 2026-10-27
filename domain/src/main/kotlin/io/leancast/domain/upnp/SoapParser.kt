package io.leancast.domain.upnp

import java.io.StringReader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory

/** A parsed SOAP control request. */
data class SoapRequest(
    val service: UpnpService,
    val action: String,
    val args: Map<String, String>,
)

/** Parses SOAP 1.1 control envelopes (UPnP control encoding). */
object SoapParser {

    fun parse(soapActionHeader: String?, body: String): SoapRequest? = try {
        val headerRoute = soapActionHeader?.let { routeFromHeader(it) }
        val bodyRoute = routeFromBody(body)
        when {
            headerRoute != null -> SoapRequest(
                service = headerRoute.first,
                action = headerRoute.second,
                args = bodyArgs(body),
            )
            bodyRoute != null -> SoapRequest(
                service = bodyRoute.first,
                action = bodyRoute.second,
                args = bodyRoute.third,
            )
            else -> null
        }
    } catch (_: XmlPullParserException) {
        null
    } catch (_: Exception) {
        null
    }

    private fun routeFromHeader(header: String): Pair<UpnpService, String>? {
        val cleaned = header.trim().trim('"')
        val idx = cleaned.lastIndexOf('#')
        if (idx <= 0) return null
        val service = UpnpService.fromUrn(cleaned.substring(0, idx)) ?: return null
        val action = cleaned.substring(idx + 1).trim()
        if (action.isEmpty()) return null
        return service to action
    }

    /** Resolves service+action+args from the first element inside `Body`, prefix-agnostic. */
    private fun routeFromBody(body: String): Triple<UpnpService, String, Map<String, String>>? {
        val parser = newParser(body)
        var inBody = false
        var actionDepth = -1
        var action: String? = null
        var service: UpnpService? = null
        val args = mutableMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            val evt = parser.eventType
            val name = parser.name ?: continue
            when {
                evt == XmlPullParser.START_TAG && name == "Body" -> inBody = true
                evt == XmlPullParser.END_TAG && name == "Body" -> break
                inBody && evt == XmlPullParser.START_TAG && action == null -> {
                    action = name.substringAfterLast(':')
                    service = UpnpService.fromUrn(parser.namespace)
                    actionDepth = parser.depth
                }
                inBody && evt == XmlPullParser.START_TAG && action != null &&
                    parser.depth == actionDepth + 1 -> {
                    args[name.substringAfterLast(':')] = captureArgText(parser)
                }
            }
        }
        val a = action ?: return null
        val s = service ?: return null
        return Triple(s, a, args)
    }

    private fun bodyArgs(body: String): Map<String, String> =
        routeFromBody(body)?.third ?: emptyMap()

    /**
     * Captures an argument's text content, tolerating senders that embed raw
     * (unescaped) XML inside the argument element: child elements are
     * re-serialized into the returned string.
     */
    private fun captureArgText(parser: XmlPullParser): String {
        val depth = parser.depth
        val out = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.TEXT -> out.append(parser.text)
                XmlPullParser.START_TAG -> {
                    out.append('<').append(parser.name)
                    for (i in 0 until parser.attributeCount) {
                        out.append(' ').append(parser.getAttributeName(i))
                            .append("=\"").append(parser.getAttributeValue(i)).append('"')
                    }
                    out.append('>')
                }
                XmlPullParser.END_TAG -> {
                    if (parser.depth == depth) return out.toString()
                    out.append("</").append(parser.name).append('>')
                }
            }
        }
        return out.toString()
    }

    private fun newParser(xml: String): XmlPullParser {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        return parser
    }
}

/** Extracts `dc:title` from a DIDL-Lite metadata blob; null when absent or malformed. */
object DidlParser {

    fun extractTitle(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        return try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(StringReader(metadata))
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && isDcTitle(parser)) {
                    val text = parser.nextText().trim()
                    if (text.isNotEmpty()) return text
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * `dc:title` may arrive with a declared namespace or an undeclared `dc:` prefix,
     * so accept either while rejecting other title-like elements with a foreign ns.
     */
    private fun isDcTitle(parser: XmlPullParser): Boolean {
        val raw = parser.name ?: return false
        val ns = parser.namespace ?: ""
        val nameMatches = raw == "title" || raw.endsWith(":title")
        return nameMatches && (ns.isEmpty() || ns.startsWith("http://purl.org/dc/"))
    }
}

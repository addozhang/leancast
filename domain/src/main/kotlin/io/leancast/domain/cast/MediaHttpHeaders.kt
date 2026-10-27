package io.leancast.domain.cast

/**
 * HTTP headers attached to media fetches so CDN front-ends that filter
 * non-browser clients (common with CN video CDNs) still serve the stream.
 */
object MediaHttpHeaders {

    /** Desktop-Chrome UA; long-standing format, avoids empty/odd UA blocks. */
    const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    fun forUri(uri: String?): Map<String, String> =
        when {
            uri == null -> emptyMap()
            uri.startsWith("http://", ignoreCase = true) ||
                uri.startsWith("https://", ignoreCase = true) ->
                java.util.Collections.unmodifiableMap(
                    linkedMapOf(
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*",
                    ),
                )
            else -> emptyMap()
        }
}

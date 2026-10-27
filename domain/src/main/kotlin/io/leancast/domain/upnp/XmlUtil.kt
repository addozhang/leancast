package io.leancast.domain.upnp

internal object XmlUtil {
    fun escape(text: String?): String = buildString {
        for (c in text ?: return "") {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}

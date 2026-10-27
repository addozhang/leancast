package io.leancast.domain.upnp

/** UPnP `H:MM:SS(.fraction)` ↔ milliseconds. */
object UpnpTime {

    private val pattern = Regex("""^(\d+):(\d{1,2}):(\d{1,2})(\.\d+)?$""")

    /** Parses `H:MM:SS` or `H:MM:SS.fractionalSeconds`; returns null when malformed. */
    fun parseHmsToMs(text: String?): Long? {
        val m = pattern.matchEntire(text?.trim() ?: return null) ?: return null
        val hours = m.groupValues[1].toLong()
        val minutes = m.groupValues[2].toLong()
        val seconds = m.groupValues[3].toLong()
        val fractionSeconds = m.groupValues[4].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
        return hours * 3_600_000 + minutes * 60_000 + seconds * 1_000 + (fractionSeconds * 1_000).toLong()
    }

    /** Renders milliseconds as `H:MM:SS`, truncating sub-second remainder. */
    fun renderMsToHms(ms: Long): String {
        val total = (ms.coerceAtLeast(0)) / 1000
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }
}

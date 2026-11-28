package io.leancast.domain.update

/**
 * Semantic version (`major.minor.patch[-prerelease][+build]`) used to decide
 * whether a remote release is strictly newer than the installed app.
 */
data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        if (preRelease == other.preRelease) return 0
        if (preRelease == null) return 1
        if (other.preRelease == null) return -1
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        if (preRelease.isNullOrEmpty()) "$major.$minor.$patch" else "$major.$minor.$patch-$preRelease"

    companion object {
        private val CORE_REGEX = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
        private val PRERELEASE_REGEX = Regex("""^[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*$""")

        fun parse(raw: String?): SemVer? {
            if (raw == null) return null
            val trimmed = raw.trim().removePrefix("v").removePrefix("V")
            if (trimmed.isEmpty()) return null

            val withoutBuild = trimmed.substringBefore('+')
            val dashIdx = withoutBuild.indexOf('-')
            val corePart = if (dashIdx >= 0) withoutBuild.substring(0, dashIdx) else withoutBuild
            val prePart = if (dashIdx >= 0) withoutBuild.substring(dashIdx + 1) else null

            val match = CORE_REGEX.matchEntire(corePart) ?: return null
            if (prePart != null && !PRERELEASE_REGEX.matches(prePart)) return null

            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].toIntOrNull() ?: return null
            return SemVer(major, minor, patch, prePart)
        }

        /** Returns `true` only when both versions parse cleanly and `remote > current`. */
        fun isNewer(remote: String?, current: String?): Boolean {
            val remoteVer = parse(remote) ?: return false
            val currentVer = parse(current) ?: return false
            return remoteVer > currentVer
        }

        private fun comparePreRelease(a: String, b: String): Int {
            val aParts = a.split('.')
            val bParts = b.split('.')
            val minLen = minOf(aParts.size, bParts.size)
            for (i in 0 until minLen) {
                val ap = aParts[i]
                val bp = bParts[i]
                if (ap == bp) continue
                val an = ap.toIntOrNull()
                val bn = bp.toIntOrNull()
                return when {
                    an != null && bn != null -> an.compareTo(bn)
                    an != null -> -1
                    bn != null -> 1
                    else -> ap.compareTo(bp)
                }
            }
            return aParts.size.compareTo(bParts.size)
        }
    }
}

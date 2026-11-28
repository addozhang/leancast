package io.leancast.domain.update

/**
 * Validated update manifest extracted from `latest.json`'s `assets["android-universal"]`.
 */
data class UpdateManifest(
    val version: String,
    val file: String,
    val sha256: String,
    val changelog: String = "",
) {
    /**
     * Returns a normalized [UpdateManifest] if all required fields are safe and valid,
     * or `null` if any field is malformed (e.g. HTML error page, path-traversal filename,
     * invalid SemVer, or non-64-hex SHA-256 digest).
     */
    fun validated(): UpdateManifest? {
        val cleanVersion = version.trim().removePrefix("v").removePrefix("V")
        if (SemVer.parse(cleanVersion) == null) return null

        val cleanFile = file.trim()
        if (!SAFE_APK_FILENAME.matches(cleanFile) || cleanFile.contains("..")) return null

        val cleanSha = sha256.trim().lowercase()
        if (!SHA256_HEX.matches(cleanSha)) return null

        return UpdateManifest(
            version = cleanVersion,
            file = cleanFile,
            sha256 = cleanSha,
            changelog = changelog.trim(),
        )
    }

    companion object {
        const val ASSET_KEY_ANDROID_UNIVERSAL = "android-universal"
        private val SAFE_APK_FILENAME = Regex("""^[A-Za-z0-9._-]+\.apk$""")
        private val SHA256_HEX = Regex("""^[0-9a-f]{64}$""")
    }
}

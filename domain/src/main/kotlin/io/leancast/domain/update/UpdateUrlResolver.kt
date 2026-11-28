package io.leancast.domain.update

/**
 * Resolves ordered candidate URLs for `latest.json` and versioned APK downloads:
 * first-party `dl.solocraft.app` primary source followed by direct GitHub Releases fallback.
 */
object UpdateUrlResolver {

    const val DEFAULT_APP_SLUG = "leancast"
    const val DEFAULT_PRIMARY_BASE = "https://dl.solocraft.app"
    const val DEFAULT_GITHUB_REPO = "addozhang/leancast"

    fun manifestUrls(
        appSlug: String = DEFAULT_APP_SLUG,
        primaryBase: String = DEFAULT_PRIMARY_BASE,
        githubRepo: String = DEFAULT_GITHUB_REPO,
    ): List<String> {
        val base = primaryBase.trimEnd('/')
        val slug = appSlug.trim('/')
        val repo = githubRepo.trim('/')
        return listOf(
            "$base/$slug/latest.json",
            "https://github.com/$repo/releases/latest/download/latest.json",
        )
    }

    fun apkUrls(
        version: String,
        file: String,
        appSlug: String = DEFAULT_APP_SLUG,
        primaryBase: String = DEFAULT_PRIMARY_BASE,
        githubRepo: String = DEFAULT_GITHUB_REPO,
    ): List<String> {
        val base = primaryBase.trimEnd('/')
        val slug = appSlug.trim('/')
        val repo = githubRepo.trim('/')
        val tag = version.trim().let { if (it.startsWith("v") || it.startsWith("V")) "v${it.drop(1)}" else "v$it" }
        val cleanFile = file.trim().trimStart('/')
        return listOf(
            "$base/$slug/$tag/$cleanFile",
            "https://github.com/$repo/releases/download/$tag/$cleanFile",
        )
    }
}

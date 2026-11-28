package io.leancast.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import io.leancast.domain.update.SemVer
import io.leancast.domain.update.UpdateManifest
import io.leancast.domain.update.UpdateManifestParser
import io.leancast.domain.update.UpdateUrlResolver
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Opt-in update checker, verified APK downloader, and Android TV installer handoff.
 *
 * Uses only JDK `HttpURLConnection` + `MessageDigest` and `FileProvider` so the APK
 * remains pure Kotlin with zero added dependencies.
 */
object AppUpdater {

    private const val USER_AGENT = "LeanCast-Updater/1"
    private const val MANIFEST_CONNECT_TIMEOUT_MS = 8_000
    private const val MANIFEST_READ_TIMEOUT_MS = 15_000
    private const val APK_CONNECT_TIMEOUT_MS = 10_000
    private const val APK_READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 5
    private const val MAX_MANIFEST_BYTES = 256 * 1024

    sealed class CheckResult {
        data class UpdateAvailable(val manifest: UpdateManifest) : CheckResult()
        data class UpToDate(val currentVersion: String) : CheckResult()
        data class Failed(val reason: String) : CheckResult()
    }

    sealed class DownloadStage {
        data class Downloading(val percent: Int) : DownloadStage()
        object Verifying : DownloadStage()
    }

    sealed class DownloadResult {
        data class Ready(val apkFile: File) : DownloadResult()
        data class IntegrityError(val expected: String, val actual: String) : DownloadResult()
        data class Failed(val reason: String) : DownloadResult()
    }

    enum class InstallOutcome {
        LAUNCHED,
        PERMISSION_REQUIRED,
        INSTALLER_UNAVAILABLE,
    }

    fun updatesCacheDir(context: Context): File = File(context.cacheDir, "updates")

    /**
     * Checks ordered [manifestUrls] until one returns HTTP 2xx with a valid `latest.json`.
     * Call off the main thread.
     */
    fun checkForUpdate(
        currentVersion: String,
        manifestUrls: List<String> = UpdateUrlResolver.manifestUrls(),
    ): CheckResult {
        var lastError = "No update endpoints configured"
        for (url in manifestUrls) {
            try {
                val body = fetchText(url)
                val manifest = UpdateManifestParser.parse(body)
                    ?: throw IOException("Invalid latest.json payload from $url")
                return if (SemVer.isNewer(manifest.version, currentVersion)) {
                    CheckResult.UpdateAvailable(manifest)
                } else {
                    CheckResult.UpToDate(currentVersion)
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        return CheckResult.Failed(lastError)
    }

    /**
     * Downloads the APK referenced by [manifest] into [targetDir], verifying SHA-256
     * before atomically moving it into place. Reuses an already-verified cached file.
     * Call off the main thread.
     */
    fun downloadAndVerifyApk(
        manifest: UpdateManifest,
        targetDir: File,
        apkUrls: List<String> = UpdateUrlResolver.apkUrls(manifest.version, manifest.file),
        onProgress: (DownloadStage) -> Unit = {},
    ): DownloadResult {
        val safe = manifest.validated() ?: return DownloadResult.Failed("Invalid manifest")
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return DownloadResult.Failed("Cannot create updates cache directory")
        }

        val finalFile = File(targetDir, safe.file)
        if (finalFile.isFile && sha256OfFile(finalFile).equals(safe.sha256, ignoreCase = true)) {
            onProgress(DownloadStage.Downloading(100))
            onProgress(DownloadStage.Verifying)
            return DownloadResult.Ready(finalFile)
        }

        val partFile = File(targetDir, "${safe.file}.part")
        var lastPercent = -1
        var lastFailure: DownloadResult = DownloadResult.Failed("No download endpoints configured")

        for (url in apkUrls) {
            partFile.delete()
            try {
                val actualSha = streamDownloadWithSha256(url, partFile) { pct ->
                    if (pct > lastPercent) {
                        lastPercent = pct
                        onProgress(DownloadStage.Downloading(pct))
                    }
                }
                onProgress(DownloadStage.Verifying)
                if (!actualSha.equals(safe.sha256, ignoreCase = true)) {
                    partFile.delete()
                    lastFailure = DownloadResult.IntegrityError(safe.sha256, actualSha)
                    continue
                }
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    partFile.delete()
                }
                pruneStaleFiles(targetDir, keepName = safe.file)
                return DownloadResult.Ready(finalFile)
            } catch (e: Exception) {
                partFile.delete()
                lastFailure = DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        return lastFailure
    }

    /**
     * Hands a verified APK to the Android system package installer via [FileProvider].
     * On API 26+, guides the user to Unknown App Sources settings if not yet granted.
     */
    fun launchInstaller(context: Context, apkFile: File): InstallOutcome {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                val permIntent = Intent(
                    AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(permIntent)
            }
            return InstallOutcome.PERMISSION_REQUIRED
        }

        return try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            InstallOutcome.LAUNCHED
        } catch (_: ActivityNotFoundException) {
            InstallOutcome.INSTALLER_UNAVAILABLE
        } catch (_: SecurityException) {
            InstallOutcome.INSTALLER_UNAVAILABLE
        }
    }

    private fun fetchText(urlString: String): String {
        val conn = openFollowingRedirects(
            urlString = urlString,
            connectTimeoutMs = MANIFEST_CONNECT_TIMEOUT_MS,
            readTimeoutMs = MANIFEST_READ_TIMEOUT_MS,
        )
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            conn.inputStream.use { input ->
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_MANIFEST_BYTES) {
                        throw IOException("Manifest exceeds $MAX_MANIFEST_BYTES bytes")
                    }
                    out.write(buf, 0, n)
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            conn.disconnect()
        }
    }

    private fun streamDownloadWithSha256(
        urlString: String,
        partFile: File,
        onPercent: (Int) -> Unit,
    ): String {
        val conn = openFollowingRedirects(
            urlString = urlString,
            connectTimeoutMs = APK_CONNECT_TIMEOUT_MS,
            readTimeoutMs = APK_READ_TIMEOUT_MS,
        )
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code")
            }
            val totalBytes = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(16 * 1024)
            var downloaded = 0L

            conn.inputStream.use { input ->
                FileOutputStream(partFile).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        downloaded += n
                        if (totalBytes > 0L) {
                            val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            onPercent(pct)
                        }
                    }
                }
            }
            onPercent(100)
            return digest.digest().toHexLower()
        } finally {
            conn.disconnect()
        }
    }

    private fun openFollowingRedirects(
        urlString: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpURLConnection {
        var current = urlString
        for (hop in 0..MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code in 301..303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("Redirect without Location header")
                current = URL(URL(current), location).toString()
                continue
            }
            return conn
        }
        throw IOException("Too many redirects")
    }

    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(16 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().toHexLower()
    }

    private fun pruneStaleFiles(dir: File, keepName: String) {
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.name != keepName) {
                runCatching { f.delete() }
            }
        }
    }

    private fun ByteArray.toHexLower(): String = joinToString("") { "%02x".format(it) }
}

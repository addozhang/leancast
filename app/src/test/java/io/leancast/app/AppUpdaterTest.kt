package io.leancast.app

import io.leancast.domain.update.UpdateManifest
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Local-socket JVM tests for [AppUpdater]: manifest fallback, APK download,
 * monotonic progress reporting, and SHA-256 verification.
 */
class AppUpdaterTest {

    private lateinit var server: ServerSocket
    private val pool = Executors.newCachedThreadPool()
    private val handlers = LinkedHashMap<String, () -> Pair<Int, ByteArray>>()
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "leancast-updater-test-${System.nanoTime()}").apply {
            mkdirs()
        }
        server = ServerSocket(0)
        pool.execute {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                pool.execute {
                    sock.use { s ->
                        val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                        val reqLine = reader.readLine() ?: return@use
                        val path = reqLine.split(" ").getOrNull(1) ?: "/"
                        while (true) {
                            val line = reader.readLine()
                            if (line.isNullOrEmpty()) break
                        }
                        val (code, body) = handlers[path]?.invoke()
                            ?: (404 to "Not Found".toByteArray())
                        val out = s.getOutputStream()
                        val header = "HTTP/1.1 $code OK\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        out.write(header.toByteArray())
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        runCatching { server.close() }
        pool.shutdownNow()
        tempDir.deleteRecursively()
    }

    private fun url(path: String): String = "http://127.0.0.1:${server.localPort}$path"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `falls back from 502 and HTML 200 to valid latest_json and detects newer version`() {
        val apkBytes = "fake-apk-binary-payload".toByteArray()
        val digest = sha256(apkBytes)
        val manifestJson = """
            {
              "version": "0.4.0",
              "changelog": "- Opt-in updater",
              "assets": {
                "android-universal": {
                  "file": "LeanCast-0.4.0.apk",
                  "sha256": "$digest"
                }
              }
            }
        """.trimIndent().toByteArray()

        handlers["/bad-502"] = { 502 to "Bad Gateway".toByteArray() }
        handlers["/bad-html"] = { 200 to "<html><body>Rate limited</body></html>".toByteArray() }
        handlers["/good.json"] = { 200 to manifestJson }

        val result = AppUpdater.checkForUpdate(
            currentVersion = "0.3.0",
            manifestUrls = listOf(url("/bad-502"), url("/bad-html"), url("/good.json")),
        )
        assertTrue(result is AppUpdater.CheckResult.UpdateAvailable)
        val available = result as AppUpdater.CheckResult.UpdateAvailable
        assertEquals("0.4.0", available.manifest.version)
        assertEquals("LeanCast-0.4.0.apk", available.manifest.file)
        assertEquals(digest, available.manifest.sha256)
    }

    @Test
    fun `reports UpToDate when installed version matches or exceeds manifest`() {
        val digest = "a".repeat(64)
        handlers["/latest.json"] = {
            200 to """{"version":"0.3.0","changelog":"","assets":{"android-universal":{"file":"LeanCast-0.3.0.apk","sha256":"$digest"}}}""".toByteArray()
        }
        val result = AppUpdater.checkForUpdate("0.3.0", listOf(url("/latest.json")))
        assertEquals(AppUpdater.CheckResult.UpToDate("0.3.0"), result)
    }

    @Test
    fun `downloads APK, verifies SHA-256, reports monotonic progress, and prunes stale cache`() {
        val apkBytes = ByteArray(64 * 1024) { (it % 251).toByte() }
        val digest = sha256(apkBytes)
        val stale = File(tempDir, "LeanCast-0.2.0.apk").apply { writeText("old") }

        handlers["/corrupt.apk"] = { 200 to "corrupted-bytes".toByteArray() }
        handlers["/good.apk"] = { 200 to apkBytes }

        val manifest = UpdateManifest("0.4.0", "LeanCast-0.4.0.apk", digest)
        val percents = mutableListOf<Int>()
        var sawVerifying = false

        val result = AppUpdater.downloadAndVerifyApk(
            manifest = manifest,
            targetDir = tempDir,
            apkUrls = listOf(url("/corrupt.apk"), url("/good.apk")),
        ) { stage ->
            when (stage) {
                is AppUpdater.DownloadStage.Downloading -> percents.add(stage.percent)
                AppUpdater.DownloadStage.Verifying -> sawVerifying = true
            }
        }

        assertTrue(result is AppUpdater.DownloadResult.Ready)
        val ready = result as AppUpdater.DownloadResult.Ready
        assertTrue(ready.apkFile.isFile)
        assertEquals(apkBytes.size.toLong(), ready.apkFile.length())
        assertFalse(stale.exists())
        assertTrue(sawVerifying)
        assertTrue(percents.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun `deletes part file and returns IntegrityError when all sources fail SHA-256`() {
        val expected = "b".repeat(64)
        handlers["/bad.apk"] = { 200 to "tampered".toByteArray() }

        val manifest = UpdateManifest("0.4.0", "LeanCast-0.4.0.apk", expected)
        val result = AppUpdater.downloadAndVerifyApk(
            manifest = manifest,
            targetDir = tempDir,
            apkUrls = listOf(url("/bad.apk")),
        )

        assertTrue(result is AppUpdater.DownloadResult.IntegrityError)
        assertFalse(File(tempDir, "LeanCast-0.4.0.apk").exists())
        assertFalse(File(tempDir, "LeanCast-0.4.0.apk.part").exists())
    }
}

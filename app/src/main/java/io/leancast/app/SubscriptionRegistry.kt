package io.leancast.app

import android.util.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * GENA subscriptions and event delivery.
 *
 * Senders learn about playback state through `NOTIFY` callbacks rather than by
 * polling, so a renderer that accepts subscriptions but never notifies leaves
 * the sender's cast session stuck in a "connecting" state — and many clients
 * eventually abort the session even though the media is playing.
 *
 * Delivery happens on a single background thread: a slow or dead callback must
 * never delay a SOAP response.
 */
class SubscriptionRegistry {

    private class Subscriber(
        val sid: String,
        val service: String,
        val callbacks: List<String>,
        @Volatile var seq: Int = 0,
        @Volatile var expiresAtMs: Long,
        @Volatile var failures: Int = 0,
    )

    private val subs = ConcurrentHashMap<String, Subscriber>()
    private val lastBody = ConcurrentHashMap<String, String>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "leancast-gena").apply { isDaemon = true }
    }
    private val pending = AtomicInteger()

    /** Registers a subscription and returns its SID. */
    fun subscribe(service: String, callbacks: List<String>, timeoutSeconds: Int): String {
        val sid = "uuid:${UUID.randomUUID()}"
        subs[sid] = Subscriber(sid, service, callbacks, expiresAtMs = expiry(timeoutSeconds))
        return sid
    }

    /** Extends an existing subscription; false when the SID is unknown or expired. */
    fun renew(sid: String, timeoutSeconds: Int): Boolean {
        pruneExpired()
        val sub = subs[sid] ?: return false
        sub.expiresAtMs = expiry(timeoutSeconds)
        return true
    }

    fun unsubscribe(sid: String): Boolean {
        pruneExpired()
        return subs.remove(sid) != null
    }

    /** Delivers [body] to every subscriber of [service] (e.g. `avt`, `rcs`). */
    fun publish(service: String, body: String) {
        pruneExpired()
        // Only real changes are evented; re-sending an identical payload would
        // just burn a SEQ number and make senders re-read the whole state.
        if (lastBody.put(service, body) == body) return
        val targets = subs.values.filter { it.service == service }
        if (targets.isEmpty()) return
        pending.incrementAndGet()
        worker.execute {
            try {
                targets.forEach { deliver(it, body) }
            } finally {
                pending.decrementAndGet()
            }
        }
    }

    /** Sends the mandatory initial event right after a subscription is accepted. */
    fun publishInitial(sid: String, body: String) {
        val sub = subs[sid] ?: return
        worker.execute { deliver(sub, body) }
    }

    fun shutdown() {
        subs.clear()
        worker.shutdownNow()
    }

    private fun deliver(sub: Subscriber, body: String) {
        if (subs[sub.sid] == null) return
        val payload = body.toByteArray(Charsets.UTF_8)
        for (url in sub.callbacks) {
            if (post(url, sub.sid, sub.seq, payload)) {
                sub.seq = (sub.seq + 1) and 0xFFFF_FFFF.toInt()
                sub.failures = 0
                return
            }
        }
        sub.failures++
        if (sub.failures >= MAX_FAILURES) {
            Log.w(TAG, "dropping dead subscriber ${sub.sid} after ${sub.failures} failures")
            subs.remove(sub.sid)
        }
    }

    /**
     * Sends one NOTIFY over a raw socket.
     *
     * `HttpURLConnection` cannot be used here: `setRequestMethod` only accepts
     * its fixed verb whitelist and throws for `NOTIFY`, which is exactly the
     * verb GENA requires.
     */
    private fun post(url: String, sid: String, seq: Int, body: ByteArray): Boolean {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return false
            val port = if (uri.port > 0) uri.port else 80
            val path = buildString {
                append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                uri.rawQuery?.let { append("?").append(it) }
            }
            java.net.Socket().use { sock ->
                sock.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                sock.soTimeout = READ_TIMEOUT_MS
                val head = buildString {
                    append("NOTIFY $path HTTP/1.1\r\n")
                    append("HOST: $host:$port\r\n")
                    append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
                    append("NT: upnp:event\r\n")
                    append("NTS: upnp:propchange\r\n")
                    append("SID: $sid\r\n")
                    append("SEQ: $seq\r\n")
                    append("CONTENT-LENGTH: ${body.size}\r\n")
                    append("CONNECTION: close\r\n\r\n")
                }.toByteArray(Charsets.ISO_8859_1)
                sock.getOutputStream().apply {
                    write(head)
                    write(body)
                    flush()
                }
                val status = sock.getInputStream().bufferedReader().readLine().orEmpty()
                status.contains(" 200")
            }
        } catch (e: Exception) {
            Log.d(TAG, "NOTIFY to $url failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        subs.values.removeAll { it.expiresAtMs <= now }
    }

    private fun expiry(timeoutSeconds: Int) = System.currentTimeMillis() + timeoutSeconds * 1000L

    companion object {
        private const val TAG = "LeanCastGena"
        private const val MAX_FAILURES = 3
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MS = 3_000

        /** Parses `TIMEOUT: Second-1800` / `Second-infinite`; defaults to 1800 s. */
        fun parseTimeout(raw: String?): Int {
            val value = raw?.trim() ?: return DEFAULT_TIMEOUT
            if (!value.startsWith("Second-", ignoreCase = true)) return DEFAULT_TIMEOUT
            val tail = value.removePrefix("Second-").removePrefix("second-")
            if (tail.equals("infinite", ignoreCase = true)) return DEFAULT_TIMEOUT
            return tail.toIntOrNull()?.coerceIn(60, 86_400) ?: DEFAULT_TIMEOUT
        }

        /** Extracts `<http://host:port/path>` (possibly several) from a CALLBACK header. */
        fun parseCallbacks(raw: String?): List<String> =
            raw?.let { Regex("<([^>]+)>").findAll(it).map { m -> m.groupValues[1].trim() }.toList() }
                .orEmpty()

        const val DEFAULT_TIMEOUT = 1800
    }
}

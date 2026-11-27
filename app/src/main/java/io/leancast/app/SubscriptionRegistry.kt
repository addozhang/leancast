package io.leancast.app

import android.util.Log
import io.leancast.domain.upnp.GenaProtocol
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * GENA subscriptions and event delivery.
 *
 * Senders learn about playback state through `NOTIFY` callbacks rather than by
 * polling, so a renderer that accepts subscriptions but never notifies leaves
 * the sender's cast session stuck in a "connecting" state — and many clients
 * eventually abort the session even though the media is playing.
 *
 * Delivery uses a bounded worker pool combined with a per-subscriber FIFO drain
 * queue: each subscriber receives its events (`SEQ 0, 1, 2...`) strictly in
 * order, while a slow or unreachable subscriber never stalls healthy ones.
 */
class SubscriptionRegistry {

    private class Subscriber(
        val sid: String,
        val service: String,
        val callbacks: List<String>,
        val createdOrder: Long,
        var seq: Long = 0L,
        @Volatile var expiresAtMs: Long,
        var failures: Int = 0,
        val queue: ArrayDeque<String> = ArrayDeque(),
        var draining: Boolean = false,
    )

    private val subs = ConcurrentHashMap<String, Subscriber>()
    private val lastBody = ConcurrentHashMap<String, String>()
    private val threadSeq = AtomicInteger(1)
    private val subOrder = AtomicLong(0L)
    private val worker = Executors.newFixedThreadPool(DELIVERY_THREADS) { runnable ->
        Thread(runnable, "leancast-gena-${threadSeq.getAndIncrement()}").apply { isDaemon = true }
    }

    /** Registers a subscription and returns its SID, capping total active subscriptions. */
    fun subscribe(service: String, callbacks: List<String>, timeoutSeconds: Int): String {
        pruneExpired()
        enforceCapacity()
        val now = System.currentTimeMillis()
        val sid = "uuid:${UUID.randomUUID()}"
        subs[sid] = Subscriber(
            sid = sid,
            service = service,
            callbacks = callbacks,
            createdOrder = subOrder.incrementAndGet(),
            expiresAtMs = now + timeoutSeconds * 1000L,
        )
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
        targets.forEach { enqueue(it, body) }
    }

    /** Sends the mandatory initial event right after a subscription response is sent. */
    fun publishInitial(sid: String, body: String) {
        val sub = subs[sid] ?: return
        enqueue(sub, body)
    }

    fun shutdown() {
        subs.clear()
        worker.shutdownNow()
    }

    private fun enqueue(sub: Subscriber, body: String) {
        val scheduleDrain = synchronized(sub) {
            if (subs[sub.sid] == null) return
            if (sub.queue.size >= MAX_PENDING_PER_SUB) {
                sub.queue.removeFirst()
            }
            sub.queue.addLast(body)
            if (!sub.draining) {
                sub.draining = true
                true
            } else {
                false
            }
        }
        if (scheduleDrain) {
            runCatching { worker.execute { drain(sub) } }.onFailure {
                synchronized(sub) { sub.draining = false }
            }
        }
    }

    private fun drain(sub: Subscriber) {
        while (true) {
            val nextBody = synchronized(sub) {
                if (subs[sub.sid] == null || sub.queue.isEmpty()) {
                    sub.draining = false
                    return
                }
                sub.queue.removeFirst()
            }
            deliverOne(sub, nextBody)
        }
    }

    private fun deliverOne(sub: Subscriber, body: String) {
        if (subs[sub.sid] == null) return
        val payload = body.toByteArray(Charsets.UTF_8)
        for (url in sub.callbacks) {
            if (post(url, sub.sid, sub.seq, payload)) {
                sub.seq = GenaProtocol.nextSeq(sub.seq)
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
    private fun post(url: String, sid: String, seq: Long, body: ByteArray): Boolean {
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

    private fun enforceCapacity() {
        while (subs.size >= MAX_SUBSCRIPTIONS) {
            val oldest = subs.values.minByOrNull { it.createdOrder } ?: break
            subs.remove(oldest.sid)
        }
    }

    private fun expiry(timeoutSeconds: Int) = System.currentTimeMillis() + timeoutSeconds * 1000L

    companion object {
        private const val TAG = "LeanCastGena"
        private const val MAX_FAILURES = 3
        private const val MAX_SUBSCRIPTIONS = 32
        private const val MAX_PENDING_PER_SUB = 8
        private const val DELIVERY_THREADS = 4
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val READ_TIMEOUT_MS = 2_000

        /** Parses `TIMEOUT: Second-1800` / `Second-infinite`; defaults to 1800 s. */
        fun parseTimeout(raw: String?): Int = GenaProtocol.parseTimeout(raw)

        /** Extracts validated HTTP callback URLs from a CALLBACK header. */
        fun parseCallbacks(raw: String?): List<String> = GenaProtocol.parseCallbacks(raw)

        const val DEFAULT_TIMEOUT = GenaProtocol.DEFAULT_TIMEOUT
    }
}

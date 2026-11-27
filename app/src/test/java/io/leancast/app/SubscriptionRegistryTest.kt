package io.leancast.app

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Real-socket JVM tests for [SubscriptionRegistry]: FIFO per-subscriber SEQ
 * ordering under concurrent publishes, deduplication, dead-subscriber isolation
 * and eviction, renewal, and capacity cap.
 */
class SubscriptionRegistryTest {

    private data class ReceivedNotify(val sid: String, val seq: Long, val body: String)

    private class MockCallbackServer(
        private val statusCode: Int = 200,
        private val delayMs: Long = 0L,
    ) : AutoCloseable {
        val socket = ServerSocket(0)
        val received = CopyOnWriteArrayList<ReceivedNotify>()
        @Volatile private var running = true
        @Volatile var latch: CountDownLatch? = null

        val url: String get() = "http://127.0.0.1:${socket.localPort}/cb"

        private val thread = Thread {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    client.soTimeout = 3_000
                    val input = BufferedInputStream(client.getInputStream())
                    val headBytes = ByteArrayOutputStream()
                    var tail = 0
                    while (true) {
                        val b = input.read()
                        if (b < 0) break
                        headBytes.write(b)
                        tail = (tail shl 8) or b
                        if (tail == 0x0D0A0D0A) break
                    }
                    val lines = headBytes.toString("ISO-8859-1").split("\r\n")
                    val headers = linkedMapOf<String, String>()
                    lines.drop(1).forEach { line ->
                        val c = line.indexOf(':')
                        if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
                    }
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    val buf = ByteArray(len)
                    var read = 0
                    while (read < len) {
                        val n = input.read(buf, read, len - read)
                        if (n < 0) break
                        read += n
                    }
                    if (delayMs > 0) Thread.sleep(delayMs)
                    received += ReceivedNotify(
                        sid = headers["sid"].orEmpty(),
                        seq = headers["seq"]?.toLongOrNull() ?: -1L,
                        body = String(buf, 0, read, Charsets.UTF_8),
                    )
                    client.getOutputStream().apply {
                        write("HTTP/1.1 $statusCode OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    latch?.countDown()
                } catch (_: Exception) {
                } finally {
                    runCatching { client.close() }
                }
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            running = false
            runCatching { socket.close() }
            thread.interrupt()
        }
    }

    private lateinit var registry: SubscriptionRegistry

    @Before
    fun setUp() {
        registry = SubscriptionRegistry()
    }

    @After
    fun tearDown() {
        registry.shutdown()
    }

    @Test
    fun `delivers initial event and subsequent publishes strictly in FIFO SEQ order`() {
        MockCallbackServer().use { cb ->
            val expectedCount = 6
            cb.latch = CountDownLatch(expectedCount)

            val sid = registry.subscribe("avt", listOf(cb.url), 300)
            registry.publishInitial(sid, "<state>INIT</state>")
            for (i in 1..5) {
                registry.publish("avt", "<state>STEP_$i</state>")
            }

            assertTrue("timed out waiting for notifications", cb.latch!!.await(5, TimeUnit.SECONDS))
            assertEquals(expectedCount, cb.received.size)
            assertEquals((0L until expectedCount.toLong()).toList(), cb.received.map { it.seq })
            assertEquals("<state>INIT</state>", cb.received[0].body)
            assertEquals("<state>STEP_5</state>", cb.received[5].body)
            assertTrue(cb.received.all { it.sid == sid })
        }
    }

    @Test
    fun `deduplicates identical consecutive publishes`() {
        MockCallbackServer().use { cb ->
            cb.latch = CountDownLatch(2)
            val sid = registry.subscribe("avt", listOf(cb.url), 300)
            registry.publish("avt", "<same/>")
            registry.publish("avt", "<same/>")
            registry.publish("avt", "<same/>")
            registry.publish("avt", "<changed/>")

            assertTrue(cb.latch!!.await(5, TimeUnit.SECONDS))
            Thread.sleep(150)
            assertEquals(2, cb.received.size)
            assertEquals(listOf(0L, 1L), cb.received.map { it.seq })
            assertEquals(listOf("<same/>", "<changed/>"), cb.received.map { it.body })
            assertTrue(registry.unsubscribe(sid))
        }
    }

    @Test
    fun `slow or failing subscriber does not block healthy subscriber and is dropped after 3 failures`() {
        MockCallbackServer(statusCode = 500, delayMs = 200L).use { bad ->
            MockCallbackServer(statusCode = 200).use { good ->
                bad.latch = CountDownLatch(3)
                good.latch = CountDownLatch(1)
                val badSid = registry.subscribe("avt", listOf(bad.url), 300)
                val goodSid = registry.subscribe("avt", listOf(good.url), 300)

                val start = System.currentTimeMillis()
                registry.publish("avt", "<ev>1</ev>")
                assertTrue(good.latch!!.await(2, TimeUnit.SECONDS))
                val elapsed = System.currentTimeMillis() - start
                assertTrue("healthy subscriber took too long ($elapsed ms)", elapsed < 1_500L)

                // Drive 2 more failures on bad subscriber so it gets dropped (total 3).
                registry.publish("avt", "<ev>2</ev>")
                registry.publish("avt", "<ev>3</ev>")
                assertTrue(bad.latch!!.await(5, TimeUnit.SECONDS))
                Thread.sleep(200)

                assertFalse("dead subscriber should be evicted after 3 failures", registry.renew(badSid, 300))
                assertTrue("healthy subscriber should remain active", registry.renew(goodSid, 300))
            }
        }
    }

    @Test
    fun `concurrent publishes from multiple threads never reorder SEQ for a subscriber`() {
        MockCallbackServer().use { cb ->
            val sid = registry.subscribe("avt", listOf(cb.url), 300)
            val total = 8
            cb.latch = CountDownLatch(total)
            val callers = Executors.newFixedThreadPool(4)
            try {
                for (i in 1..total) {
                    callers.execute {
                        registry.publish("avt", "<concurrent>$i</concurrent>")
                    }
                }
                assertTrue(cb.latch!!.await(5, TimeUnit.SECONDS))
                val seqs = cb.received.map { it.seq }
                assertEquals((0L until total.toLong()).toList(), seqs)
                assertTrue(cb.received.all { it.sid == sid })
            } finally {
                callers.shutdownNow()
            }
        }
    }

    @Test
    fun `enforces subscription capacity cap by evicting oldest`() {
        val sids = (1..35).map { i ->
            registry.subscribe("avt", listOf("http://198.51.100.$i:8080/ev"), 300)
        }
        // First 3 should have been evicted (cap = 32); latest remains active.
        assertFalse(registry.renew(sids.first(), 300))
        assertTrue(registry.renew(sids.last(), 300))
    }
}

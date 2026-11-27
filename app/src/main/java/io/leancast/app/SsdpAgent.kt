package io.leancast.app

import android.util.Log
import io.leancast.domain.ssdp.SsdpIdentity
import io.leancast.domain.ssdp.SsdpInbound
import io.leancast.domain.ssdp.SsdpMessageParser
import io.leancast.domain.ssdp.SsdpResponder
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * SSDP network agent: joins the multicast group, answers M-SEARCHes with
 * MX-paced unicast replies, and multicasts alive/byebye notifications.
 *
 * All socket bind/join/send operations run on background threads so calling
 * [start], [rebindToActiveNetwork], [reannounce], or [stop] from the Android
 * main thread never triggers `NetworkOnMainThreadException` or ANRs.
 *
 * Resilient to boot-time races where `BOOT_COMPLETED` fires before Wi-Fi or
 * Ethernet gets a DHCP lease: [rebindToActiveNetwork] re-joins multicast on the
 * active LAN interface and emits a fresh burst of `ssdp:alive` advertisements as
 * soon as a LAN IPv4 address becomes available or changes.
 */
class SsdpAgent(
    private val identity: SsdpIdentity,
    private val locationProvider: () -> String? = { identity.location },
    private val responder: SsdpResponder = SsdpResponder(
        identity = identity,
        locationProvider = locationProvider,
    ),
) {
    @Volatile private var running = false
    @Volatile private var socket: MulticastSocket? = null
    @Volatile private var joinedIp: String? = null
    private var listenThread: Thread? = null
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ssdp-scheduler").apply { isDaemon = true }
    }

    fun start() {
        if (running) return
        running = true
        val group = InetAddress.getByAddress(GROUP_BYTES)

        scheduler.execute {
            if (!running) return@execute
            openAndJoinSocket(group)
            announceAliveBurst(group)
        }

        listenThread = Thread {
            val buf = ByteArray(2048)
            while (running) {
                val sock = socket
                if (sock == null || sock.isClosed) {
                    try {
                        Thread.sleep(500L)
                    } catch (_: InterruptedException) {
                        if (!running) break
                    }
                    if (running) openAndJoinSocket(group)
                    continue
                }
                try {
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    if (packet.address.isLoopbackAddress) continue
                    val copy = packet.data.copyOf(packet.length)
                    when (val msg = SsdpMessageParser.parse(copy, packet.address, packet.port)) {
                        is SsdpInbound.MSearch -> scheduleResponses(msg)
                        is SsdpInbound.Ignored -> Unit
                    }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }.apply {
            name = "ssdp-listener"
            isDaemon = true
            start()
        }

        // Periodic refresh at max-age/2.
        val refreshSec = (identity.maxAgeSeconds / 2).toLong().coerceAtLeast(30L)
        scheduler.scheduleAtFixedRate(
            { multicast(responder.aliveMessages(), group) },
            refreshSec,
            refreshSec,
            TimeUnit.SECONDS,
        )
    }

    /**
     * Called when the device's LAN IPv4 address becomes available after boot or
     * switches interfaces. Re-creates and re-joins the multicast socket on the
     * active LAN interface and sends an `ssdp:alive` burst off the caller thread.
     */
    fun rebindToActiveNetwork() {
        if (!running) return
        val group = InetAddress.getByAddress(GROUP_BYTES)
        runCatching {
            scheduler.execute {
                if (!running) return@execute
                openAndJoinSocket(group, forceRecreate = true)
                announceAliveBurst(group)
            }
        }
    }

    /**
     * Sends `ssdp:byebye` followed by a fresh `ssdp:alive` burst so senders
     * re-fetch `/desc.xml` (e.g. after the user renames the receiver) without
     * restarting the service or interrupting active playback.
     */
    fun reannounce() {
        if (!running) return
        val group = InetAddress.getByAddress(GROUP_BYTES)
        runCatching {
            scheduler.execute {
                if (!running) return@execute
                multicast(responder.byebyeMessages(), group)
                announceAliveBurst(group, initialDelayMs = REANNOUNCE_GAP_MS)
            }
        }
    }

    @Synchronized
    private fun openAndJoinSocket(group: InetAddress, forceRecreate: Boolean = false) {
        val currentLan = Net.lanInterfaceAndIpv4()
        val currentIp = currentLan?.second
        val existing = socket
        if (!forceRecreate && existing != null && !existing.isClosed && currentIp != null && currentIp == joinedIp) {
            return
        }
        runCatching { existing?.close() }
        socket = null
        joinedIp = null

        runCatching {
            val sock = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(PORT))
                joinMulticastOnLan(this, group, currentLan?.first)
            }
            socket = sock
            joinedIp = currentIp
            Log.i(TAG, "SSDP socket bound on :$PORT (lanIp=${currentIp ?: "pending"})")
        }.onFailure { e ->
            Log.w(TAG, "SSDP socket bind/join deferred: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun joinMulticastOnLan(
        sock: MulticastSocket,
        group: InetAddress,
        lanInterface: NetworkInterface?,
    ) {
        if (lanInterface != null) {
            val joinedOnInterface = runCatching {
                sock.networkInterface = lanInterface
                sock.joinGroup(InetSocketAddress(group, PORT), lanInterface)
            }.isSuccess
            if (joinedOnInterface) return
        }
        runCatching { sock.joinGroup(group) }
    }

    private fun announceAliveBurst(group: InetAddress, initialDelayMs: Long = 0L) {
        // UPnP recommends several announcements at startup / network acquire.
        repeat(3) { burst ->
            runCatching {
                scheduler.schedule({
                    if (running) multicast(responder.aliveMessages(), group)
                }, initialDelayMs + burst * 350L, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        val sock = socket
        if (sock != null && !sock.isClosed) {
            val group = InetAddress.getByAddress(GROUP_BYTES)
            // Send byebye off the caller (Android main) thread so it never hits NetworkOnMainThreadException.
            val byebyeTask = runCatching {
                scheduler.submit {
                    responder.byebyeMessages().forEach { bytes ->
                        runCatching { sock.send(DatagramPacket(bytes, bytes.size, group, PORT)) }
                    }
                }
            }.getOrNull()
            runCatching { byebyeTask?.get(BYEBYE_WAIT_MS, TimeUnit.MILLISECONDS) }
        }
        scheduler.shutdownNow()
        listenThread?.interrupt()
        runCatching { sock?.close() }
        socket = null
        joinedIp = null
    }

    private fun scheduleResponses(msg: SsdpInbound.MSearch) {
        val responses = responder.responsesFor(msg)
        if (responses.isEmpty()) return
        val delayMs = responder.responseDelayMs(msg.mxSeconds)
        runCatching {
            scheduler.schedule({
                val sock = socket ?: return@schedule
                responses.forEach { bytes ->
                    runCatching {
                        val target = InetSocketAddress(msg.from, msg.fromPort)
                        sock.send(DatagramPacket(bytes, bytes.size, target))
                    }
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    private fun multicast(messages: List<ByteArray>, group: InetAddress) {
        if (messages.isEmpty()) return
        val sock = socket ?: return
        messages.forEach { bytes ->
            runCatching {
                sock.send(DatagramPacket(bytes, bytes.size, group, PORT))
            }
        }
    }

    companion object {
        private const val TAG = "LeanCastSsdp"
        const val GROUP = "239.255.255.250"
        private val GROUP_BYTES = byteArrayOf(239.toByte(), 255.toByte(), 255.toByte(), 250.toByte())
        const val PORT = 1900
        private const val BYEBYE_WAIT_MS = 300L
        private const val REANNOUNCE_GAP_MS = 150L
    }
}

package io.leancast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.PlayerLaunchPolicy
import io.leancast.domain.cast.TransportState
import io.leancast.domain.cast.VolumeMath
import io.leancast.domain.ssdp.LanEndpointPolicy
import io.leancast.domain.ssdp.SsdpIdentity
import io.leancast.domain.upnp.LastChangeEvent
import io.leancast.domain.upnp.RendererDescription
import io.leancast.domain.upnp.SoapRouter
import io.leancast.domain.upnp.UpnpTime
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that owns the whole receiver: multicast lock, SSDP agent,
 * HTTP control server, cast session, and the ExoPlayer-backed player port.
 */
class ReceiverService : Service() {

    private lateinit var settings: Settings
    private lateinit var session: CastSession
    private lateinit var playerPort: ExoPlayerPort
    private var server: ControlServer? = null
    private var ssdp: SsdpAgent? = null
    private lateinit var events: SubscriptionRegistry
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var audioManager: AudioManager
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lanWatcher: ScheduledExecutorService? = null
    @Volatile private var lastKnownLanIp: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Promote to foreground first: `startForegroundService` gives us only an
        // ~5 s window, so do it before the (slower) player/server wiring below.
        startForeground(NOTIFICATION_ID, buildNotification())
        settings = Settings(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        playerPort = ExoPlayerPort(this)
        session = CastSession(playerPort).also { s ->
            playerPort.session = s
            s.addListener(::onTransportStateChanged)
            s.setVolumeSink(::applyDeviceVolume)
            s.setVolume(initialVolumePercent())
        }

        CastBus.session = session
        CastBus.port = playerPort

        events = SubscriptionRegistry()
        val server = ControlServer.startWithFallback(
            preferredPort = PREFERRED_PORT,
            descriptionProvider = ::rendererDescription,
            router = SoapRouter(session),
            events = events,
            eventBody = ::eventBody,
            serverToken = serverToken(),
        )
        this.server = server

        acquireMulticastLock()
        refreshLanEndpoint(rebindSsdp = false)

        val identity = SsdpIdentity(
            uuid = settings.uuid(),
            location = currentLocationUrl() ?: "",
            serverToken = serverToken(),
        )
        ssdp = SsdpAgent(
            identity = identity,
            locationProvider = ::currentLocationUrl,
        ).also { it.start() }

        startNetworkMonitoring()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // When MainActivity or BootReceiver calls ReceiverService.start(...) on an
        // already-running service, re-check the LAN IP in case Wi-Fi came up after boot.
        refreshLanEndpoint(rebindSsdp = true)
        return START_STICKY
    }

    override fun onDestroy() {
        stopNetworkMonitoring()
        ssdp?.stop()
        server?.stop()
        events.shutdown()
        playerPort.release()
        releaseMulticastLock()
        CastBus.session = null
        CastBus.port = null
        CastBus.endpoint = ""
        super.onDestroy()
    }

    /**
     * Resolves the current `http://<lan-ip>:<port>/desc.xml` URL dynamically,
     * returning `null` while the TV has no usable LAN IPv4 address yet.
     */
    private fun currentLocationUrl(): String? {
        val port = server?.boundPort ?: return null
        val ip = Net.lanIpv4()
        val ep = LanEndpointPolicy.formatEndpoint(ip, port)
        if (ep.isNotEmpty()) {
            CastBus.endpoint = ep
        }
        return LanEndpointPolicy.locationUrl(ip, port)
    }

    /**
     * Updates [CastBus.endpoint] from the active LAN interface and, whenever the
     * IPv4 address becomes available after boot or changes, rebinds [SsdpAgent]
     * so SSDP multicast is joined on the real interface and advertised immediately.
     */
    @Synchronized
    private fun refreshLanEndpoint(rebindSsdp: Boolean) {
        val port = server?.boundPort ?: return
        val ip = Net.lanIpv4()
        CastBus.endpoint = LanEndpointPolicy.formatEndpoint(ip, port)
        if (ip != lastKnownLanIp) {
            val previous = lastKnownLanIp
            lastKnownLanIp = ip
            Log.i(TAG, "LAN IP changed: ${previous ?: "none"} -> ${ip ?: "none"}")
            if (rebindSsdp && LanEndpointPolicy.isUsableLanIp(ip)) {
                acquireMulticastLock()
                ssdp?.rebindToActiveNetwork()
            }
        }
    }

    private fun startNetworkMonitoring() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            connectivityManager = cm
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    refreshLanEndpoint(rebindSsdp = true)
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    refreshLanEndpoint(rebindSsdp = true)
                }

                override fun onLost(network: Network) {
                    refreshLanEndpoint(rebindSsdp = true)
                }
            }
            networkCallback = callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm?.registerDefaultNetworkCallback(callback)
            } else {
                cm?.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            }
        }
        // Lightweight fallback poll for TV boxes whose Ethernet/Wi-Fi drivers do
        // not reliably emit LinkProperties callbacks during boot.
        lanWatcher = Executors.newSingleThreadScheduledExecutor().also { exec ->
            exec.scheduleWithFixedDelay(
                { runCatching { refreshLanEndpoint(rebindSsdp = true) } },
                3L,
                5L,
                TimeUnit.SECONDS,
            )
        }
    }

    private fun stopNetworkMonitoring() {
        runCatching {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        connectivityManager = null
        lanWatcher?.shutdownNow()
        lanWatcher = null
    }

    private fun rendererDescription(): RendererDescription {
        val port = server?.boundPort ?: PREFERRED_PORT
        val ep = LanEndpointPolicy.formatEndpoint(Net.lanIpv4(), port)
            .ifEmpty { CastBus.endpoint.ifEmpty { "127.0.0.1:$port" } }
        return RendererDescription(
            friendlyName = settings.friendlyName(),
            uuid = settings.uuid(),
            manufacturer = getString(R.string.manufacturer),
            modelName = getString(R.string.model_name),
            baseUrl = "http://$ep",
        )
    }

    private fun serverToken(): String =
        "Android/${Build.VERSION.RELEASE} UPnP/1.0 ${getString(R.string.model_name)}/1"

    private fun onTransportStateChanged(state: TransportState) {
        val launchPlayer = PlayerLaunchPolicy.shouldSurfacePlayer(state)
        if (launchPlayer) {
            surfacePlayerActivity()
        }
        updateNotification(state, fullScreenLaunch = launchPlayer)
        events.publish("avt", eventBody("avt"))
    }

    /**
     * Brings [PlayerActivity] to the foreground when a cast starts.
     *
     * Combines a direct `startActivity(...)` with a `PendingIntent.send()` fallback
     * and a high-priority `fullScreenIntent` notification so background launches
     * succeed after silent boot autostart on custom TV launchers (e.g. Aerial).
     */
    private fun surfacePlayerActivity() {
        val playerIntent = playerLaunchIntent()
        runCatching { startActivity(playerIntent) }
        runCatching { playerPendingIntent().send() }
    }

    private fun playerLaunchIntent(): Intent =
        Intent(this, PlayerActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
        )

    private fun playerPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            REQUEST_CODE_PLAYER,
            playerLaunchIntent(),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** GENA `LastChange` payload for a service, reflecting the current session. */
    private fun eventBody(service: String): String = when (service) {
        "rcs" -> LastChangeEvent.renderingControl(session.volume, mute = false)
        else -> LastChangeEvent.avTransport(
            transportState = session.state.name,
            currentUri = session.currentUri,
            currentTrackDuration = UpnpTime.renderMsToHms(session.durationMs()),
            isLive = session.isLive(),
        )
    }

    private fun applyDeviceVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            VolumeMath.mapPercentToSteps(percent, max),
            0,
        )
        // Guarded: the initial volume is applied while onCreate is still wiring
        // up the eventing layer.
        if (::events.isInitialized) events.publish("rcs", eventBody("rcs"))
    }

    private fun initialVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return Math.round(current * 100.0 / max).toInt()
    }

    private fun acquireMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) return
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wifi.createMulticastLock("leancast-ssdp").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun buildNotification(
        state: TransportState = TransportState.NO_MEDIA_PRESENT,
        fullScreenLaunch: Boolean = false,
    ): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    PLAYBACK_CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }
        val contentPi = if (PlayerLaunchPolicy.shouldSurfacePlayer(state)) {
            playerPendingIntent()
        } else {
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val channel = if (fullScreenLaunch) PLAYBACK_CHANNEL_ID else CHANNEL_ID
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channel)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, state.name))
            .setContentIntent(contentPi)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
        if (fullScreenLaunch) {
            builder.setFullScreenIntent(playerPendingIntent(), true)
        }
        return builder.build()
    }

    private fun updateNotification(state: TransportState, fullScreenLaunch: Boolean = false) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state, fullScreenLaunch))
    }

    companion object {
        private const val TAG = "LeanCastService"
        private const val CHANNEL_ID = "receiver"
        private const val PLAYBACK_CHANNEL_ID = "receiver_playback"
        private const val NOTIFICATION_ID = 1
        private const val REQUEST_CODE_PLAYER = 2
        private const val PREFERRED_PORT = 49_152

        /**
         * Starts the receiver. Uses [Context.startForegroundService] on API 26+ so the
         * call is legal from a background context (e.g. [BootReceiver] after boot) —
         * a plain `startService` there throws `IllegalStateException` on Android 8+.
         */
        fun start(context: Context) {
            val intent = Intent(context, ReceiverService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ReceiverService::class.java))
        }
    }
}

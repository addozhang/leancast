package io.leancast.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.TransportState
import io.leancast.domain.cast.VolumeMath
import io.leancast.domain.ssdp.SsdpIdentity
import io.leancast.domain.upnp.RendererDescription
import io.leancast.domain.upnp.SoapRouter

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
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var audioManager: AudioManager

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

        val server = ControlServer.startWithFallback(
            preferredPort = PREFERRED_PORT,
            descriptionProvider = ::rendererDescription,
            router = SoapRouter(session),
        )
        this.server = server

        val lanIp = Net.lanIpv4()
        CastBus.endpoint = "${lanIp ?: "0.0.0.0"}:${server.boundPort}"

        val identity = SsdpIdentity(
            uuid = settings.uuid(),
            location = "http://${lanIp ?: "0.0.0.0"}:${server.boundPort}/desc.xml",
            serverToken = serverToken(),
        )
        ssdp = SsdpAgent(identity).also { it.start() }

        acquireMulticastLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        ssdp?.stop()
        server?.stop()
        playerPort.release()
        releaseMulticastLock()
        CastBus.session = null
        CastBus.port = null
        CastBus.endpoint = ""
        super.onDestroy()
    }

    private fun rendererDescription() = RendererDescription(
        friendlyName = settings.friendlyName(),
        uuid = settings.uuid(),
        manufacturer = getString(R.string.manufacturer),
        modelName = getString(R.string.model_name),
        baseUrl = "http://${CastBus.endpoint}",
    )

    private fun serverToken(): String =
        "Android/${Build.VERSION.RELEASE} UPnP/1.0 ${getString(R.string.model_name)}/1"

    private fun onTransportStateChanged(state: TransportState) {
        if (state == TransportState.TRANSITIONING) {
            // Surface the player when a cast starts.
            runCatching {
                startActivity(
                    Intent(this, PlayerActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK,
                    ),
                )
            }
        }
        updateNotification(state)
    }

    private fun applyDeviceVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            VolumeMath.mapPercentToSteps(percent, max),
            0,
        )
    }

    private fun initialVolumePercent(): Int {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return Math.round(current * 100.0 / max).toInt()
    }

    private fun acquireMulticastLock() {
        runCatching {
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

    private fun buildNotification(state: TransportState = TransportState.NO_MEDIA_PRESENT): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, state.name))
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: TransportState) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(state))
    }

    companion object {
        private const val CHANNEL_ID = "receiver"
        private const val NOTIFICATION_ID = 1
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

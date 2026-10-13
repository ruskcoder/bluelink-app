package com.ruskcoder.messages.imessage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ruskcoder.messages.MessagesApp
import com.ruskcoder.messages.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the Apple push connection open while the app is not in the foreground.
 *
 * A foreground service rather than a periodic worker because there is nothing to poll: iMessage
 * delivery is inbound over a socket Apple holds open, so a process that gets to sleep simply stops
 * receiving. This is the same shape OpenBubbles uses on Android for the same reason.
 */
class PushService : Service() {

    /**
     * Watches for the connection under us moving, and rebuilds the push socket when it does.
     *
     * A socket does not always die politely. Handing over from Wi-Fi to cellular, coming back from
     * a tunnel, a carrier NAT quietly dropping an idle mapping — none of those close anything, so
     * the reader stays parked on a connection that is never going to deliver again and the app goes
     * silent with nothing looking wrong. The retry loop inside the syncer cannot help: it only runs
     * once a read has *failed*, and this read never does.
     *
     * So the trigger comes from the platform instead. Registered against the *default* network
     * rather than against every network with an internet capability: a phone with Wi-Fi and mobile
     * data both up reports two, and watching both would rebuild the connection every time the one
     * we are not using came and went.
     */
    private val networks = object : ConnectivityManager.NetworkCallback() {
        /** Skips the callback that fires for the network we are already connected over. */
        private var known: Network? = null

        override fun onAvailable(network: Network) {
            monitor().setNetworkAvailable(true)
            if (network == known) return
            val first = known == null
            known = network
            // Registering the callback delivers the current network immediately; that one is what
            // the socket was built on, so rebuilding for it would drop a working connection on
            // every start.
            if (first) return
            Log.i(TAG, "network changed, rebuilding push connection")
            syncer().reconnect()
            (application as MessagesApp).container.webBridge.reconnect()
        }

        override fun onLost(network: Network) {
            // Only the default network going away is "no network". Wi-Fi dropping after cellular
            // has already taken over arrives here too, for a network that is no longer the known one.
            if (network == known) {
                known = null
                monitor().setNetworkAvailable(false)
            }
        }
    }

    /** Keeps the ongoing notification saying what the connection is actually doing. */
    private var statusJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        monitor().setServiceRunning(true)
        createChannel()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networks)
        }.onFailure { Log.w(TAG, "cannot watch for network changes", it) }
    }

    override fun onDestroy() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networks) }
        statusJob?.cancel()
        isRunning = false
        monitor().setServiceRunning(false)
        super.onDestroy()
    }

    private fun syncer() = (application as MessagesApp).container.imessageSyncer

    private fun monitor() = (application as MessagesApp).container.connectionMonitor

    /**
     * Mirrors the connection's state into the ongoing notification.
     *
     * It used to say "Connected" unconditionally, which is the one word it must not say while the
     * socket is down — it is the notification someone looks at when they wonder why nothing is
     * arriving.
     */
    private fun watchStatus() {
        if (statusJob?.isActive == true) return
        statusJob = (application as MessagesApp).container.applicationScope.launch {
            monitor().health.map { it.status }.distinctUntilChanged().collect { status ->
                val text = when (status) {
                    ConnectionHealth.Status.CONNECTED -> "Connected"
                    ConnectionHealth.Status.CONNECTING -> "Connecting…"
                    ConnectionHealth.Status.OFFLINE -> "Waiting for network"
                    ConnectionHealth.Status.FAILED -> "Can't reach iMessage"
                    ConnectionHealth.Status.NOT_SET_UP -> "Not signed in"
                    ConnectionHealth.Status.UNKNOWN -> "Starting…"
                }
                runCatching {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        // Idempotent: restarts deliver another onStartCommand, and the syncer ignores a second call
        // while its collector is still running.
        syncer().start()
        monitor().start()
        watchStatus()
        // The service is sticky, but sticky is a request the system does not always honour — see
        // [ConnectionWatchdog], which checks every quarter of an hour that it is still here.
        ConnectionWatchdog.schedule(this)
        // A registered phone number has to be re-proved by SMS before it lapses — see
        // [PhoneRegistration.renewIfDue]. Checked once now, then by the worker.
        val container = (application as MessagesApp).container
        container.applicationScope.launch {
            if (PhoneRegistration.registeredNumbers(container.imessageService).isNotEmpty()) {
                PhoneRenewalWorker.schedule(this@PushService)
                PhoneRegistration.renewIfDue(this@PushService, container.imessageService)
            }
        }
        // Browsers linked in Settings → Web ride on the same foreground service: a phone that can
        // receive iMessage can be reached from the web, and one that can't has nothing to offer it.
        (application as MessagesApp).container.webBridge.start()

        // Restarted after the system reclaims us, because losing the socket means losing messages
        // rather than merely delaying work.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(status: String = "Starting…"): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("iMessage")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "iMessage connection",
            // The lowest importance the system still shows a foreground notification at: it is
            // there because the platform requires it, not because it is worth reading.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "Keeps iMessage connected so messages arrive."
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        /**
         * Whether the service is alive in this process.
         *
         * Process-wide, which is exactly the question the watchdog is asking: a worker that wakes a
         * process the system had killed finds this false, because the service did not come back.
         */
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val TAG = "PushService"
        private const val CHANNEL_ID = "imessage_connection"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, PushService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PushService::class.java))
        }
    }
}

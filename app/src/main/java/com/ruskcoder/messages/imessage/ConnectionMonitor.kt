package com.ruskcoder.messages.imessage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.ruskcoder.messages.MainActivity
import com.ruskcoder.messages.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How the connection to Apple is doing, in the terms a person needs rather than the ones the
 * session speaks in.
 *
 * The failure this exists for is the quiet one. A reverse-engineered push connection can be down —
 * no network, a socket that died without closing, an account Apple has stopped answering — while
 * every screen in the app looks exactly as it does when all is well. Nothing arriving looks like
 * nobody writing. So the state is published, the chat list says so when it is not connected, and
 * Settings → Connection shows the rest.
 */
data class ConnectionHealth(
    val status: Status = Status.UNKNOWN,
    /** Apple's own wording for a failure, where there is any. */
    val detail: String? = null,
    /** When [status] last changed, in epoch millis — what "down for how long" is measured from. */
    val since: Long = System.currentTimeMillis(),
    val networkAvailable: Boolean = true,
    /** When the connection last came up, in epoch millis; 0 for never. Survives restarts. */
    val lastConnectedAt: Long = 0L,
    /** When anything last arrived from Apple, in epoch millis; 0 for never. Survives restarts. */
    val lastEventAt: Long = 0L,
    /** How many times the connection has been re-established since the process started. */
    val reconnects: Int = 0,
    /** Whether the foreground service holding the socket is alive. */
    val serviceRunning: Boolean = false,
) {
    enum class Status {
        /** Nothing has started watching yet — never shown as a problem. */
        UNKNOWN,
        CONNECTED,
        CONNECTING,
        /** The phone has no network, so nothing could connect anyway. */
        OFFLINE,
        FAILED,
        /** No hardware identity or no sign-in: there is no connection to have. */
        NOT_SET_UP,
    }

    /** Whether this is a state worth telling somebody about. */
    val isProblem: Boolean
        get() = status == Status.CONNECTING || status == Status.OFFLINE || status == Status.FAILED
}

/**
 * Watches the session and the network, publishes [ConnectionHealth], and runs the catch-up work a
 * reconnection owes — resuming sends that were cut off, fetching attachments that never arrived.
 *
 * Takes the service as a provider for the same reason the rest of the container does: publishing a
 * health object for the chat list to read must not be what loads the Rust library. Only [start]
 * touches it, and only the push service calls that, which already requires a registration.
 */
class ConnectionMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val service: () -> IMessageService,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _health = MutableStateFlow(
        ConnectionHealth(
            lastConnectedAt = prefs.getLong(KEY_LAST_CONNECTED, 0L),
            lastEventAt = prefs.getLong(KEY_LAST_EVENT, 0L),
        ),
    )
    val health: StateFlow<ConnectionHealth> = _health.asStateFlow()

    private val network = MutableStateFlow(true)
    private var job: Job? = null
    private var connectedOnce = false
    private var eventPersistedAt = 0L

    /**
     * What to do once the connection has come up and stayed up for a moment.
     *
     * Set by the container, which is the only thing that can see the sender and the syncer both.
     */
    var onConnected: suspend () -> Unit = {}

    @Synchronized
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            combine(service().state, network) { state, online -> state to online }.collect { (state, online) ->
                val (status, detail) = statusOf(state, online)
                val now = System.currentTimeMillis()
                val becameConnected = status == ConnectionHealth.Status.CONNECTED &&
                    _health.value.status != ConnectionHealth.Status.CONNECTED
                _health.update { current ->
                    current.copy(
                        status = status,
                        detail = detail,
                        networkAvailable = online,
                        since = if (current.status != status) now else current.since,
                        lastConnectedAt = if (becameConnected) now else current.lastConnectedAt,
                        reconnects = if (becameConnected && connectedOnce) current.reconnects + 1 else current.reconnects,
                    )
                }
                if (!becameConnected) return@collect
                connectedOnce = true
                prefs.edit {
                    putLong(KEY_LAST_CONNECTED, now)
                    putBoolean(KEY_EVER_CONNECTED, true)
                }
                ConnectionAlerts.clear(context)
                scope.launch {
                    // A network coming back reports the session as connected for the moment before
                    // the rebuild replaces it, and catching up over a socket that is about to be
                    // closed only turns queued messages into failed ones. So it waits to be sure.
                    delay(SETTLE_MS)
                    if (_health.value.status != ConnectionHealth.Status.CONNECTED) return@launch
                    runCatching { onConnected() }.onFailure { Log.w(TAG, "catching up after connecting failed", it) }
                }
            }
        }
    }

    fun setNetworkAvailable(available: Boolean) {
        network.value = available
    }

    fun setServiceRunning(running: Boolean) {
        _health.update { it.copy(serviceRunning = running) }
    }

    /**
     * Something arrived from Apple.
     *
     * Kept in memory on every event and written to disk at most once a minute: it is read by a
     * person on a settings page and by a watchdog every fifteen minutes, and neither of those needs
     * a disk write per receipt.
     */
    fun noteEvent() {
        val now = System.currentTimeMillis()
        _health.update { it.copy(lastEventAt = now) }
        if (now - eventPersistedAt > EVENT_PERSIST_MS) {
            eventPersistedAt = now
            prefs.edit { putLong(KEY_LAST_EVENT, now) }
        }
    }

    private fun statusOf(state: IMessageService.State, online: Boolean): Pair<ConnectionHealth.Status, String?> =
        when (state) {
            IMessageService.State.NeedsHardware, IMessageService.State.NeedsLogin ->
                ConnectionHealth.Status.NOT_SET_UP to null
            // Without a network the session's own view is stale either way: a "connected" socket on
            // a phone with no network is a socket nothing will ever arrive on.
            IMessageService.State.Connecting ->
                (if (online) ConnectionHealth.Status.CONNECTING else ConnectionHealth.Status.OFFLINE) to null
            is IMessageService.State.Connected ->
                (if (online) ConnectionHealth.Status.CONNECTED else ConnectionHealth.Status.OFFLINE) to null
            is IMessageService.State.Failed ->
                if (online) ConnectionHealth.Status.FAILED to state.message else ConnectionHealth.Status.OFFLINE to null
        }

    companion object {
        private const val TAG = "ConnectionMonitor"
        private const val PREFS = "connection_health"
        private const val KEY_LAST_CONNECTED = "last_connected"
        private const val KEY_LAST_EVENT = "last_event"
        private const val KEY_EVER_CONNECTED = "ever_connected"

        /** How long a fresh connection has to hold before the catch-up work runs over it. */
        private const val SETTLE_MS = 2_000L
        private const val EVENT_PERSIST_MS = 60_000L

        /** Whether this install has ever had a working connection — the watchdog's reason to exist. */
        fun everConnected(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_EVER_CONNECTED, false)
    }
}

/**
 * The one notification that says messages may not be arriving.
 *
 * Its own channel, at default importance, and never posted for a blip: the watchdog only raises it
 * once the connection has been down long enough that someone would otherwise conclude their phone
 * had simply gone quiet. Taken down the moment the connection comes back.
 */
object ConnectionAlerts {
    private const val CHANNEL_ID = "imessage_connection_alerts"
    private const val NOTIFICATION_ID = 1002

    fun show(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Connection problems", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Tells you when iMessage has stopped receiving messages."
                    },
                )
            }
        }
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("iMessage isn't connected")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }
}

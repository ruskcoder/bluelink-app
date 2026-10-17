package com.ruskcoder.messages.facetime

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.ruskcoder.messages.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the process alive for the length of a call and owns its notification.
 *
 * The notification is the incoming-call UI (a CallStyle notification with a full-screen intent,
 * which the system turns into the ringing screen on a locked phone) and, once answered, the
 * ongoing-call chip. The foreground service types grow with the call: only `phoneCall` while it
 * rings, since a background start may not claim the microphone or camera; `microphone` and
 * `camera` once it is answered from the foreground; `mediaProjection` while the screen is shared.
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var screenShare = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannels(this)
        promote(CallManager.state.value)
        scope.launch {
            CallManager.state
                .map { it?.let { s -> Triple(s.phase, s.cameraOn, s.muted) } to it }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, state) ->
                    if (state == null || state.phase == CallPhase.ENDED) {
                        stopSelf()
                    } else {
                        promote(state)
                    }
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCREEN_SHARE -> {
                screenShare = true
                promote(CallManager.state.value)
                // Only now, with the projection type held, may the projection be created.
                val data = intent.getParcelableExtraCompat<Intent>(EXTRA_PROJECTION_DATA)
                if (data != null) CallManager.startScreenShare(intent.getIntExtra(EXTRA_PROJECTION_CODE, 0), data)
            }
            ACTION_STOP_SCREEN_SHARE -> {
                screenShare = false
                promote(CallManager.state.value)
            }
            ACTION_HANG_UP -> CallManager.hangUp()
            ACTION_DECLINE -> CallManager.decline()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun promote(state: CallUiState?) {
        val notification = buildNotification(state)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceTypes(state))
    }

    private fun serviceTypes(state: CallUiState?): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        val answered = state != null && state.phase != CallPhase.RINGING_IN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && answered) {
            if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (state.cameraOn && granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (screenShare) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        return types
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun buildNotification(state: CallUiState?): Notification {
        val caller = Person.Builder()
            .setName(state?.displayName ?: "FaceTime")
            .setImportant(true)
            .build()
        val content = PendingIntent.getActivity(
            this, 0, CallActivity.intent(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = servicePending(ACTION_HANG_UP, 1)

        val incoming = state?.phase == CallPhase.RINGING_IN
        val builder = NotificationCompat.Builder(this, if (incoming) CHANNEL_INCOMING else CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(content)
            .setContentText(if (state?.videoCall == true) "FaceTime Video" else "FaceTime Audio")

        if (incoming) {
            val answer = PendingIntent.getActivity(
                this, 2, CallActivity.intent(this).setAction(CallActivity.ACTION_ANSWER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setFullScreenIntent(content, true)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, servicePending(ACTION_DECLINE, 3), answer)
                    .setIsVideo(state.videoCall))
        } else {
            builder
                .setUsesChronometer(state?.connectedAt != null)
                .apply { state?.connectedAt?.let { setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - it)) } }
                .setStyle(NotificationCompat.CallStyle.forOngoingCall(caller, hangUp).setIsVideo(state?.cameraOn == true))
        }
        return builder.build().apply {
            // Ring until answered, rather than playing the channel sound once.
            if (incoming) flags = flags or Notification.FLAG_INSISTENT
        }
    }

    private fun servicePending(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, CallService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val NOTIFICATION_ID = 0xFA_CE
        private const val CHANNEL_INCOMING = "facetime_incoming"
        private const val CHANNEL_ONGOING = "facetime_ongoing"

        private const val ACTION_SCREEN_SHARE = "com.ruskcoder.messages.facetime.SCREEN_SHARE"
        private const val ACTION_STOP_SCREEN_SHARE = "com.ruskcoder.messages.facetime.STOP_SCREEN_SHARE"
        private const val ACTION_HANG_UP = "com.ruskcoder.messages.facetime.HANG_UP"
        private const val ACTION_DECLINE = "com.ruskcoder.messages.facetime.DECLINE"
        private const val EXTRA_PROJECTION_CODE = "projection_code"
        private const val EXTRA_PROJECTION_DATA = "projection_data"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }

        /** Hand the MediaProjection consent to the service, which takes the type and then starts sharing. */
        fun promoteForScreenShare(context: Context, resultCode: Int, data: Intent) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java)
                    .setAction(ACTION_SCREEN_SHARE)
                    .putExtra(EXTRA_PROJECTION_CODE, resultCode)
                    .putExtra(EXTRA_PROJECTION_DATA, data),
            )
        }

        fun demoteFromScreenShare(context: Context) {
            context.startService(Intent(context, CallService::class.java).setAction(ACTION_STOP_SCREEN_SHARE))
        }

        private fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_INCOMING) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_INCOMING, "Incoming FaceTime calls", NotificationManager.IMPORTANCE_HIGH).apply {
                        setSound(
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build(),
                        )
                        enableVibration(true)
                    },
                )
            }
            if (manager.getNotificationChannel(CHANNEL_ONGOING) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ONGOING, "Ongoing FaceTime calls", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)

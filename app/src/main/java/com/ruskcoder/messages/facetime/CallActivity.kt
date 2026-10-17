package com.ruskcoder.messages.facetime

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ruskcoder.messages.ui.theme.MessagesTheme

/**
 * The full-screen call UI: ringing, in-call, and the moment after it ends.
 *
 * Shown over the lock screen and allowed to wake the display, because it is what the incoming
 * call's full-screen intent opens. It holds no call state of its own — closing it leaves the call
 * running in [CallService], and reopening it from the ongoing notification picks it straight back up.
 */
class CallActivity : ComponentActivity() {

    /** What to do once the microphone/camera permissions come back. */
    private var afterPermissions: (() -> Unit)? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPermissions?.invoke()
        afterPermissions = null
    }

    private val screenConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            CallService.promoteForScreenShare(this, result.resultCode, data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        enableEdgeToEdge()
        if (CallManager.state.value == null) {
            finish()
            return
        }
        handleAction(intent)

        setContent {
            val state by CallManager.state.collectAsStateWithLifecycle()
            LaunchedEffect(state == null) { if (state == null) finish() }
            MessagesTheme {
                state?.let { call ->
                    InCallScreen(
                        state = call,
                        onAccept = { video -> withPermissions(video) { CallManager.accept(video) } },
                        onDecline = CallManager::decline,
                        onHangUp = CallManager::hangUp,
                        onMute = CallManager::setMuted,
                        onCamera = { on -> if (on) withPermissions(true) { CallManager.setCameraOn(true) } else CallManager.setCameraOn(false) },
                        onFlip = CallManager::flipCamera,
                        onSpeaker = CallManager::setSpeaker,
                        onScreenShare = { on -> if (on) requestScreenShare() else CallManager.stopScreenShare() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    private fun handleAction(intent: Intent?) {
        if (intent?.action == ACTION_ANSWER) {
            val video = CallManager.state.value?.videoCall == true
            withPermissions(video) { CallManager.accept(video) }
        }
    }

    private fun withPermissions(video: Boolean, action: () -> Unit) {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (video) add(Manifest.permission.CAMERA)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) {
            action()
        } else {
            // Answer regardless of the outcome: a call without a camera is still a call, and a
            // denied microphone just means the far end hears silence.
            afterPermissions = action
            permissions.launch(wanted.toTypedArray())
        }
    }

    private fun requestScreenShare() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        screenConsent.launch(manager.createScreenCaptureIntent())
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    companion object {
        const val ACTION_ANSWER = "com.ruskcoder.messages.facetime.ANSWER"

        fun intent(context: Context) = Intent(context, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

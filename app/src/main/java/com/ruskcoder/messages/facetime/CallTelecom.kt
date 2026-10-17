package com.ruskcoder.messages.facetime

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * FaceTime calls as self-managed Telecom calls.
 *
 * Self-managed means the app draws its own call UI but the system still knows a call is in
 * progress: Bluetooth headsets and cars can answer and hang up, a cellular call arriving mid-call is
 * arbitrated against this one, and Do Not Disturb treats it as a call. Below API 26 there is no
 * self-managed mode, and the call runs on the notification and [CallService] alone.
 */
object CallTelecom {

    private const val TAG = "CallTelecom"
    private const val ACCOUNT_ID = "facetime"
    internal const val EXTRA_VIDEO = "com.ruskcoder.messages.facetime.VIDEO"
    internal const val EXTRA_NAME = "com.ruskcoder.messages.facetime.NAME"

    @Volatile internal var connection: FaceTimeConnection? = null

    private fun supported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handle(context: Context) =
        PhoneAccountHandle(ComponentName(context, FaceTimeConnectionService::class.java), ACCOUNT_ID)

    /** Register the FaceTime phone account. Idempotent; cheap enough to call before every call. */
    fun ensureAccount(context: Context): Boolean {
        if (!supported()) return false
        return runCatching {
            val account = PhoneAccount.builder(handle(context), "FaceTime")
                .setCapabilities(
                    PhoneAccount.CAPABILITY_SELF_MANAGED or
                        PhoneAccount.CAPABILITY_VIDEO_CALLING or
                        PhoneAccount.CAPABILITY_SUPPORTS_VIDEO_CALLING,
                )
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL, PhoneAccount.SCHEME_SIP))
                .build()
            telecom(context).registerPhoneAccount(account)
            true
        }.onFailure { Log.w(TAG, "registerPhoneAccount failed", it) }.getOrDefault(false)
    }

    fun reportIncoming(context: Context, name: String, handle: String?, video: Boolean) {
        if (!ensureAccount(context)) return
        runCatching {
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, address(handle))
                putBoolean(EXTRA_VIDEO, video)
                putString(EXTRA_NAME, name)
                if (video) putInt(TelecomManager.EXTRA_INCOMING_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
            }
            telecom(context).addNewIncomingCall(handle(context), extras)
        }.onFailure { Log.w(TAG, "addNewIncomingCall failed", it) }
    }

    @Suppress("MissingPermission") // MANAGE_OWN_CALLS is a normal permission, granted at install
    fun reportOutgoing(context: Context, name: String, handle: String?, video: Boolean) {
        if (!ensureAccount(context)) return
        runCatching {
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle(context))
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, video)
                if (video) putInt(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE, VideoProfile.STATE_BIDIRECTIONAL)
                putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, Bundle().apply {
                    putBoolean(EXTRA_VIDEO, video)
                    putString(EXTRA_NAME, name)
                })
            }
            telecom(context).placeCall(address(handle), extras)
        }.onFailure { Log.w(TAG, "placeCall failed", it) }
    }

    fun markActive() {
        if (!supported()) return
        connection?.setActive()
    }

    fun markEnded(error: Boolean) {
        if (!supported()) return
        connection?.let {
            it.setDisconnected(DisconnectCause(if (error) DisconnectCause.ERROR else DisconnectCause.LOCAL))
            it.destroy()
        }
        connection = null
    }

    private fun telecom(context: Context) = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager

    private fun address(handle: String?): Uri {
        val raw = handle.orEmpty().substringAfter(':')
        return if (raw.startsWith("+") || raw.all { it.isDigit() }) {
            Uri.fromParts(PhoneAccount.SCHEME_TEL, raw, null)
        } else {
            Uri.fromParts(PhoneAccount.SCHEME_SIP, raw, null)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class FaceTimeConnection(name: String?, address: Uri?, video: Boolean) : Connection() {
    init {
        connectionProperties = PROPERTY_SELF_MANAGED
        audioModeIsVoip = true
        videoState = if (video) VideoProfile.STATE_BIDIRECTIONAL else VideoProfile.STATE_AUDIO_ONLY
        address?.let { setAddress(it, android.telecom.TelecomManager.PRESENTATION_ALLOWED) }
        name?.let { setCallerDisplayName(it, android.telecom.TelecomManager.PRESENTATION_ALLOWED) }
        connectionCapabilities = CAPABILITY_MUTE
    }

    // Our own notification is the incoming UI; Telecom only needs to know it exists.
    override fun onShowIncomingCallUi() = Unit

    override fun onAnswer(videoState: Int) = CallManager.accept(VideoProfile.isVideo(videoState))
    override fun onAnswer() = CallManager.accept(false)
    override fun onReject() = CallManager.decline()
    override fun onDisconnect() = CallManager.hangUp()
    override fun onAbort() = CallManager.hangUp()
}

@RequiresApi(Build.VERSION_CODES.O)
class FaceTimeConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val extras = request.extras
        return FaceTimeConnection(extras?.getString(CallTelecom.EXTRA_NAME), request.address, extras?.getBoolean(CallTelecom.EXTRA_VIDEO) == true)
            .apply { setRinging() }
            .also { CallTelecom.connection = it }
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val extras = request.extras
        return FaceTimeConnection(extras?.getString(CallTelecom.EXTRA_NAME), request.address, extras?.getBoolean(CallTelecom.EXTRA_VIDEO) == true)
            .apply { setDialing() }
            .also { CallTelecom.connection = it }
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        // Telecom refused it (another call that cannot be held). Treat it as busy.
        CallManager.decline()
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest?) {
        // The FaceTime call still works without Telecom; it only loses the system integration.
        Log.w("FaceTimeConnectionSvc", "Outgoing connection refused by Telecom")
    }
}

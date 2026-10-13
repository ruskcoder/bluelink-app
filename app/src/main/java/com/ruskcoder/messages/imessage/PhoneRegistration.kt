package com.ruskcoder.messages.imessage

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ruskcoder.messages.MessagesApp
import com.ruskcoder.messages.imessage.eapaka.EapAkaChallenge
import com.ruskcoder.messages.imessage.eapaka.EapAkaResponse
import uniffi.imessage_core.SimChallengeResponder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Registering the SIM's phone number with iMessage, so an iPhone texting it gets a blue bubble that
 * lands here — the way an iPhone's own number is registered.
 *
 * Apple checks the number over SMS: we text `REG-REQ?v=3;t=<push token>;r=<nonce>;` to the
 * carrier's Apple gateway, and Apple texts back `REG-RESP?v=3;r=<nonce>;n=<number>;s=<signature>`.
 * The number and signature go to the Rust side, which authenticates a phone identity and registers
 * it next to the Apple ID. Switching it off registers the Apple ID alone, which takes the number off
 * iMessage: iPhones go back to green for it, and reach this phone over iMessage by email only.
 *
 * Whether a number is registered is read from the registration itself rather than stored as a
 * setting, so the switch can never say "on" for a number Apple has dropped.
 */
object PhoneRegistration {
    private const val TAG = "PhoneRegistration"

    /** Apple usually answers within seconds; carriers that relay through a data SMS can be slow. */
    private const val REPLY_TIMEOUT_MS = 90_000L

    /** Renew this far ahead, so a failed attempt has days of retries before the number lapses. */
    private const val RENEW_AHEAD_S = 3L * 24 * 3600

    /** Past this, a renewal that keeps failing gives the number up rather than risk the email. */
    private const val GIVE_UP_AHEAD_S = 6L * 3600

    /** How long to wait for the radio to say whether the text left. */
    private const val SEND_TIMEOUT_MS = 30_000L

    val permissions = arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

    /** Replies being waited for, by nonce. */
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Pair<String, String>>>()

    /** Send and receive are required; reading the inbox is only a fallback. */
    fun hasPermissions(context: Context): Boolean = permissions.take(2).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** The `tel:` handles registered on this device right now. Empty when the switch is off. */
    suspend fun registeredNumbers(service: IMessageService): List<String> =
        runCatching { service.session().phoneHandles() }.getOrDefault(emptyList())

    /**
     * The one-time grant the SIM route needs, since Android keeps SIM authentication to carrier
     * apps. Given from a computer:
     *
     *     adb shell appops set --uid com.ruskcoder.messages USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER allow
     */
    fun simAuthCommand(context: Context): String =
        "adb shell appops set --uid ${context.packageName} USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER allow"

    fun hasSimAuth(context: Context): Boolean = runCatching {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val op = "android:use_icc_auth_with_device_identifier"
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(op, android.os.Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION") appOps.checkOpNoThrow(op, android.os.Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** Either route can be tried: the SIM one with its grant, or SMS with its permissions. */
    fun canEnable(context: Context): Boolean = hasSimAuth(context) || hasPermissions(context)

    /**
     * Prove the number and register it. Returns every handle now registered.
     *
     * Through the SIM when the app has been granted that — it is what an iPhone does, and it works
     * on carriers that drop the SMS route's text, Verizon and the networks riding on it among them.
     * Otherwise, or when the carrier has no entitlement server for it, by SMS.
     */
    suspend fun enable(context: Context, service: IMessageService): List<String> {
        val session = service.session()
        var simError: Throwable? = null
        val handles = if (hasSimAuth(context)) {
            runCatching { viaSim(context, session) }
                .onFailure { simError = it; Log.w(TAG, "SIM verification failed; trying SMS", it) }
                .getOrNull()
        } else {
            null
        } ?: run {
            if (!hasPermissions(context)) {
                throw IllegalStateException(simError?.message ?: "SMS permission is needed to verify your number")
            }
            val (number, signature) = try {
                verify(context, session)
            } catch (e: Exception) {
                // Where the text goes unanswered, the SIM route is the way through — say so.
                val hint = if (hasSimAuth(context)) {
                    simError?.message?.let { "\n\nVerifying through the SIM also failed: $it" }.orEmpty()
                } else {
                    "\n\nYour carrier may only support verifying through the SIM. To allow that, run this " +
                        "from a computer with the phone connected, then try again:\n\n${simAuthCommand(context)}"
                }
                throw IllegalStateException((e.message ?: "Verification failed") + hint, e)
            }
            Log.i(TAG, "Apple verified the number by SMS; registering it")
            session.enablePhone(number, signature)
        }
        service.onRegistered(handles)
        PhoneRenewalWorker.schedule(context)
        return handles
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    private suspend fun viaSim(context: Context, session: uniffi.imessage_core.Session): List<String> {
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            ?: SubscriptionManager.getDefaultSubscriptionId()
        val telephony = context.getSystemService(TelephonyManager::class.java).createForSubscriptionId(subscription)
        val mccmnc = telephony.simOperator
        require(!mccmnc.isNullOrBlank()) { "No SIM card found" }
        // Both readable under the app-op, which is what grants "device identifier" access.
        val subscriber = EapAkaResponse.getImsiEap(mccmnc, telephony.subscriberId, "nai.epc")
            ?: throw IllegalStateException("Couldn't read the SIM's identity")
        val imei = runCatching { telephony.imei }.getOrNull().orEmpty()
        Log.i(TAG, "verifying through the SIM for carrier $mccmnc")
        val responder = object : SimChallengeResponder {
            override fun respond(challenge: String): String? = runCatching {
                EapAkaResponse.respondToEapAkaChallenge(
                    context, subscription, EapAkaChallenge.parseEapAkaChallenge(challenge), "nai.epc",
                ).response()
            }.onFailure { Log.w(TAG, "SIM would not answer the challenge", it) }.getOrNull()
        }
        return withContext(Dispatchers.IO) { session.enablePhoneViaSim(mccmnc, subscriber, imei, responder) }
    }

    /** Take the number off iMessage. The Apple ID stays registered. */
    suspend fun disable(context: Context, service: IMessageService): List<String> {
        val handles = service.session().disablePhone()
        service.onRegistered(handles)
        PhoneRenewalWorker.cancel(context)
        return handles
    }

    /**
     * Re-prove the number before its authentication lapses.
     *
     * A lapsed phone identity doesn't just drop the number: Apple refuses the device's whole
     * registration, and the email goes dark with it. So a renewal that cannot get through in time
     * takes the number off deliberately, leaving the email working.
     */
    suspend fun renewIfDue(context: Context, service: IMessageService) {
        val remaining = runCatching { service.session().phoneRenewInSecs() }.getOrNull() ?: return
        if (remaining > RENEW_AHEAD_S) return
        Log.i(TAG, "phone registration lapses in ${remaining}s; renewing")
        val renewed = runCatching { enable(context, service) }
            .onFailure { Log.w(TAG, "phone renewal failed", it) }
            .isSuccess
        if (!renewed && remaining < GIVE_UP_AHEAD_S) {
            Log.w(TAG, "phone registration about to lapse; removing it to keep the Apple ID registered")
            runCatching { disable(context, service) }
                .onFailure { Log.e(TAG, "could not remove the lapsing number", it) }
        }
    }

    private suspend fun verify(context: Context, session: uniffi.imessage_core.Session): Pair<String, String> {
        val subscription = SubscriptionManager.getDefaultSmsSubscriptionId()
        val telephony = context.getSystemService(TelephonyManager::class.java).let {
            if (subscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID) it.createForSubscriptionId(subscription) else it
        }
        val mccmnc = telephony.simOperator
        require(!mccmnc.isNullOrBlank()) { "No SIM card found" }

        val gateway = session.phoneGateway(mccmnc)
        val token = session.pushTokenHex()
        val nonce = Random.nextInt(1, Int.MAX_VALUE).toString()
        val reply = CompletableDeferred<Pair<String, String>>()
        waiting[nonce] = reply
        val sentAt = System.currentTimeMillis()
        try {
            // Whether the text actually left. Without a sent intent a refused send - no service, a
            // blocked short code, the carrier rejecting it - looked exactly like Apple not answering.
            val sent = CompletableDeferred<Int>()
            val sentAction = "${context.packageName}.REG_REQ_SENT.$nonce"
            val sentReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) { sent.complete(resultCode) }
            }
            ContextCompat.registerReceiver(context, sentReceiver, IntentFilter(sentAction), ContextCompat.RECEIVER_NOT_EXPORTED)
            try {
                val sentIntent = PendingIntent.getBroadcast(
                    context, nonce.hashCode(),
                    Intent(sentAction).setPackage(context.packageName),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                )
                withContext(Dispatchers.Main) {
                    smsManager(context, subscription)
                        .sendTextMessage(gateway, null, "REG-REQ?v=3;t=$token;r=$nonce;", sentIntent, null)
                }
                Log.i(TAG, "sending REG-REQ to $gateway for carrier $mccmnc (sub $subscription)")
                val code = withTimeoutOrNull(SEND_TIMEOUT_MS) { sent.await() }
                Log.i(TAG, "REG-REQ send result: ${code?.let(::describeSendResult) ?: "no answer from the radio"}")
                if (code != null && code != Activity.RESULT_OK) {
                    throw IllegalStateException("Your phone couldn't send the verification text (${describeSendResult(code)}).")
                }
            } finally {
                runCatching { context.unregisterReceiver(sentReceiver) }
            }

            withTimeoutOrNull(REPLY_TIMEOUT_MS) { reply.await() }?.let { return it }

            // The reply may have arrived without reaching the receiver - the default SMS app is the
            // only one guaranteed to see every text, and it files them all in the shared inbox.
            fromInbox(context, sentAt, nonce)?.let {
                Log.i(TAG, "found REG-RESP in the SMS inbox")
                return it
            }
            throw IllegalStateException(
                "Apple didn't reply to the verification text sent to $gateway. Check your messages app for a " +
                    "reply starting with REG-RESP; if there is none, your carrier may be blocking it.",
            )
        } finally {
            waiting.remove(nonce)
        }
    }

    private fun describeSendResult(code: Int): String = when (code) {
        Activity.RESULT_OK -> "sent"
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "generic failure"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "no service"
        SmsManager.RESULT_ERROR_NULL_PDU -> "null PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "radio off"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "send limit exceeded"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "short code not allowed"
        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "short code never allowed"
        else -> "error $code"
    }

    /** Apple's reply as filed in the SMS inbox, if it got there. Needs READ_SMS, which is optional. */
    private fun fromInbox(context: Context, since: Long, nonce: String): Pair<String, String>? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "no READ_SMS; can't check the inbox for a REG-RESP")
            return null
        }
        return runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                "${Telephony.Sms.DATE} >= ? AND ${Telephony.Sms.BODY} LIKE ?",
                arrayOf((since - 60_000).toString(), "%REG-RESP%"),
                "${Telephony.Sms.DATE} DESC",
            )?.use { cursor ->
                var found: Pair<String, String>? = null
                while (found == null && cursor.moveToNext()) {
                    val body = cursor.getString(1) ?: continue
                    Log.i(TAG, "inbox REG-RESP from ${cursor.getString(0)}")
                    val parsed = parse(body) ?: continue
                    if (parsed.first == nonce) found = parsed.second
                }
                found
            }
        }.onFailure { Log.w(TAG, "inbox query failed", it) }.getOrNull()
    }

    /** `REG-RESP?v=3;r=<nonce>;n=<number>;s=<sig>` to nonce and (number, signature). */
    private fun parse(body: String): Pair<String, Pair<String, String>>? {
        val start = body.indexOf("REG-RESP?")
        if (start < 0) return null
        val fields = body.substring(start + "REG-RESP?".length)
            .split(';')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .toMap()
        val nonce = fields["r"] ?: return null
        val number = fields["n"] ?: return null
        val signature = fields["s"] ?: return null
        return nonce to (number to signature)
    }

    private fun smsManager(context: Context, subscription: Int): SmsManager {
        val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION") SmsManager.getDefault()
        }
        return if (subscription != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            base.createForSubscriptionId(subscription)
        } else {
            base
        }
    }

    /** Hand a text to whoever is waiting for it. Anything that isn't Apple's reply is ignored. */
    internal fun onText(body: String) {
        if (!body.contains("REG-RESP")) return
        Log.i(TAG, "REG-RESP received: ${body.take(60)}")
        val (nonce, reply) = parse(body) ?: run {
            Log.w(TAG, "REG-RESP could not be parsed")
            return
        }
        waiting[nonce]?.complete(reply)
            ?: Log.i(TAG, "REG-RESP for a nonce nobody is waiting on")
    }
}

/**
 * Catches Apple's `REG-RESP`. Most carriers deliver it as an ordinary text; some (AT&T among them)
 * as a data SMS, which arrives on a different broadcast and has to be decoded by hand.
 */
class RegResponseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("PhoneRegistration", "SMS broadcast: ${intent.action} data=${intent.data}")
        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                val body = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    ?.joinToString("") { it.messageBody.orEmpty() }
                    .orEmpty()
                PhoneRegistration.onText(body)
            }
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION -> {
                @Suppress("DEPRECATION", "UNCHECKED_CAST")
                val pdus = intent.extras?.get("pdus") as? Array<Any> ?: return
                val format = intent.getStringExtra("format")
                pdus.mapNotNull { it as? ByteArray }.forEach { pdu -> decode(pdu, format)?.let(PhoneRegistration::onText) }
            }
        }
    }

    private fun decode(pdu: ByteArray, format: String?): String? {
        val formats = listOfNotNull(format, "3gpp", "3gpp2").distinct()
        for (f in formats) {
            val body = runCatching { SmsMessage.createFromPdu(pdu, f)?.messageBody }.getOrNull()
            if (body != null && body.contains("REG-RESP")) return body
            val data = runCatching { SmsMessage.createFromPdu(pdu, f)?.userData }.getOrNull()
            if (data != null) String(data, Charsets.US_ASCII).takeIf { it.contains("REG-RESP") }?.let { return it }
        }
        return String(pdu, Charsets.US_ASCII).takeIf { it.contains("REG-RESP") }
    }
}

/** Twice a day, renews the phone registration before it lapses. See [PhoneRegistration.renewIfDue]. */
class PhoneRenewalWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MessagesApp).container
        PhoneRegistration.renewIfDue(applicationContext, container.imessageService)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "phone-renewal"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PhoneRenewalWorker>(12, TimeUnit.HOURS).build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
        }
    }
}

package com.ruskcoder.messages.imessage

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uniffi.imessage_core.HwConfig
import uniffi.imessage_core.IncomingEvent
import uniffi.imessage_core.LoginStep
import uniffi.imessage_core.Session
import uniffi.imessage_core.configFromActivationPayload
import uniffi.imessage_core.configFromRelay
import uniffi.imessage_core.connect
import uniffi.imessage_core.connectWith
import uniffi.imessage_core.init as initCore
import uniffi.imessage_core.exportAccount as exportAccountNative
import uniffi.imessage_core.importAccount as importAccountNative
import uniffi.imessage_core.isRegistered
import uniffi.imessage_core.loadHwConfig
import uniffi.imessage_core.saveHwConfig
import uniffi.imessage_core.AccountCredential
import uniffi.imessage_core.savedAppleId
import uniffi.imessage_core.setupKeystore

/**
 * Owns the connection to Apple.
 *
 * Everything Apple-shaped stops here. Screens observe [state] to know which part of setup to show,
 * setup drives the login calls, and [IMessageSyncer] consumes [events] and writes them into Room —
 * so the chat UI keeps reading from the database exactly as it did before iMessage existed.
 *
 * One instance per process, held by `AppContainer`. The underlying Rust session owns a socket and a
 * tokio runtime, so a second one would mean two registrations fighting over the same identity.
 */
class IMessageService(
    context: Context,
    private val scope: CoroutineScope,
) {
    /**
     * Where the Rust side keeps its plists: push state, hardware identity, IDS users, key cache.
     *
     * Under `filesDir` rather than cache or external storage — losing it means re-registering the
     * device with Apple, which is a full setup round trip, not a cache miss.
     */
    val stateDir: String = File(context.filesDir, "imessage")
        .apply { mkdirs() }
        .absolutePath

    sealed interface State {
        /** No hardware identity yet: setup starts at the scanner. */
        data object NeedsHardware : State

        /** Hardware is known but no Apple ID has completed registration. */
        data object NeedsLogin : State

        data object Connecting : State

        data class Connected(val handles: List<String>) : State

        /** Connecting failed. [message] is Apple's wording where there is any. */
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state.asStateFlow()

    private val sessionLock = Mutex()
    private var session: Session? = null

    init {
        // Both must run before anything else touches the core, and exactly once per process:
        // `init` installs a global logger and `setupKeystore` a global keystore, and Rust panics
        // if either is installed twice.
        if (initialized.compareAndSet(false, true)) {
            initCore(stateDir)
            // `rustpush` stores the IDS identity wrapped by a key from here. Without it, the first
            // call that touches an identity panics — which is most of setup.
            setupKeystore(stateDir, AndroidNativeKeystore(context.applicationContext))
        }
    }

    /** The Apple ID a previous run signed in as, for a "continue as ..." affordance. */
    suspend fun savedAppleId(): String? = withContext(Dispatchers.IO) { savedAppleId(stateDir) }

    /** Whether setup can be skipped entirely. */
    suspend fun isRegistered(): Boolean = withContext(Dispatchers.IO) { isRegistered(stateDir) }

    /** The stored hardware identity, if setup ever got that far. */
    suspend fun hardware(): HwConfig? = withContext(Dispatchers.IO) { loadHwConfig(stateDir) }

    // --- backup and restore -------------------------------------------------------------------
    //
    // What leaves this device is a pair of credentials, never the state directory itself. Almost
    // everything in there is sealed under Android Keystore keys that cannot be exported, so a copy
    // would restore as ciphertext nothing on the far side could open. See `kotlin_api/backup.rs`.

    /**
     * The Mac hardware identity as an `OABS` payload, or null when there is nothing to export.
     *
     * The same bytes a shared activation QR carries, so a restore goes in through exactly the path
     * a scanned code does. Null covers both an install that never scanned one and a relay identity,
     * which has no machine of its own to describe — `to_activation_payload` refuses that one.
     */
    suspend fun exportHardwarePayload(): ByteArray? = withContext(Dispatchers.IO) {
        val hardware = loadHwConfig(stateDir) ?: return@withContext null
        // Marked shareable: a backup is meant to be restorable more than once, e.g. onto a
        // replacement handset after the first restore has already been used.
        runCatching { hardware.toActivationPayload(shareable = true) }
            .onFailure { Log.w(TAG, "hardware identity cannot be exported", it) }
            .getOrNull()
    }

    /** Adopt a hardware identity from a backup, with a fresh NGM identity to register under. */
    suspend fun importHardware(payload: ByteArray) = withContext(Dispatchers.IO) {
        saveHwConfig(stateDir, configFromActivationPayload(payload))
    }

    /** The saved Apple Account credential, decrypted, or null when nobody has signed in here. */
    suspend fun exportAccount(): SavedAccount? = withContext(Dispatchers.IO) {
        exportAccountNative(stateDir)?.let { SavedAccount(it.appleId, it.hashedPassword) }
    }

    /** Adopt an Apple Account from a backup, re-sealed under this device's own keystore key. */
    suspend fun importAccount(appleId: String, hashedPassword: ByteArray) = withContext(Dispatchers.IO) {
        importAccountNative(stateDir, AccountCredential(appleId, hashedPassword))
    }

    /**
     * Resolve a scanned or pasted code into a hardware identity.
     *
     * An activation payload is decoded offline — it already contains the Mac's identifiers, and this
     * is the only time that Mac is involved. A relay code is not decoded but *dialled*: the relay is
     * asked for the device's version info, both to learn what to register as and to establish that
     * it is answering at all, so this call needs the network and can fail on a relay that is down.
     */
    suspend fun applyActivation(input: ActivationInput): HwConfig =
        withContext(Dispatchers.IO) {
            when (input) {
                is ActivationInput.Payload -> configFromActivationPayload(input.bytes)
                is ActivationInput.Relay ->
                    configFromRelay(code = input.code, host = input.host, token = input.token)
            }
        }

    /**
     * Open (or reuse) the push connection.
     *
     * Serialised because two callers arriving at once — the setup screen and the push service, say
     * — must not each build a session and each register.
     */
    suspend fun session(pending: HwConfig? = null): Session = sessionLock.withLock {
        session?.let { return it }

        _state.value = State.Connecting
        val opened = try {
            // A config that has just been scanned is handed straight to the connection, which is
            // also what persists it — see `connect_with`. Only an install that is already set up
            // reads its hardware back off disk.
            if (pending != null) connectWith(stateDir, pending) else connect(stateDir)
        } catch (e: uniffi.imessage_core.ImException.NeedsHardware) {
            Log.w(TAG, "no hardware identity stored", e)
            _state.value = State.NeedsHardware
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "could not open push connection", e)
            _state.value = State.Failed(e.describe())
            throw e
        }

        session = opened
        _state.value = runCatching { opened.handles() }
            .fold(
                onSuccess = { State.Connected(it) },
                // Connected to push but not registered: the socket is fine, the account is not.
                onFailure = { State.NeedsLogin },
            )
        opened
    }

    /** Refresh [state] after registration completes, so screens can leave setup. */
    suspend fun onRegistered(handles: List<String>) {
        _state.value = State.Connected(handles)
    }

    /**
     * Discard the current session so the next caller builds a fresh one.
     *
     * The push socket is owned by the Rust session, so once it is gone the session cannot be
     * revived — reconnecting means constructing another. Closing the old one first releases the
     * handle Rust holds rather than leaving it to a finalizer.
     */
    suspend fun invalidateSession() = sessionLock.withLock {
        val had = session != null
        session?.let { runCatching { it.close() } }
        session = null
        // Until the next caller builds a fresh one this is a reconnection in progress, not a working
        // connection. Left at Connected, anything watching the state — the chat list's banner, the
        // send queue deciding whether to hold a message — went on believing a dead socket was fine.
        if (had && _state.value is State.Connected) _state.value = State.Connecting
    }

    /**
     * Every inbound push, as a cold flow.
     *
     * The Rust side exposes a suspending `nextEvent()` rather than calling back into Kotlin, so
     * this is an ordinary loop: nothing is decrypted until the previous event has been consumed,
     * and cancelling the collector stops the reader with it.
     */
    val events: Flow<IncomingEvent> = flow {
        val current = session()
        while (true) {
            val event = try {
                current.nextEvent()
            } catch (e: Exception) {
                Log.w(TAG, "push reader failed", e)
                null
            } ?: break
            emit(event)
        }
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val TAG = "IMessageService"

        /**
         * Guards the one-time global setup above. Process-wide rather than per-instance because the
         * globals it installs live in the Rust library, which outlives any one container.
         */
        val initialized = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}

/**
 * An Apple Account credential on its way into or out of a backup.
 *
 * [hashedPassword] is the SHA-256 that Grand Slam authenticates with rather than the password
 * itself — but it is password-equivalent, so nothing may write it anywhere it is not encrypted.
 *
 * Not a `data class`: an array's generated `equals` compares identity, which would quietly make
 * two copies of the same credential unequal.
 */
class SavedAccount(val appleId: String, val hashedPassword: ByteArray)

/** Login progress, mirrored out of the Rust surface so screens do not import it directly. */
typealias LoginProgress = LoginStep

/**
 * A message worth showing a user.
 *
 * UniFFI gives a field-less error variant an *empty* `message` rather than a null one, so the usual
 * `message ?: fallback` silently yields a blank string — which reaches the UI as an error bar with
 * nothing written on it. Anything blank falls back to the type name here instead.
 */
fun Throwable.describe(): String {
    message?.takeIf { it.isNotBlank() }?.let { return it }
    return when (this) {
        is uniffi.imessage_core.ImException.NeedsHardware ->
            "This device has no Mac hardware identity yet. Scan an activation code first."
        is uniffi.imessage_core.ImException.NotRegistered ->
            "Not registered with iMessage yet."
        else -> this::class.java.simpleName
    }
}

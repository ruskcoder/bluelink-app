package com.ruskcoder.messages.games

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "PigeonClient"

/** The pigeon app that runs the games — see `C:\Coding\pigeon`. */
private const val PIGEON_PACKAGE = "com.ruskcoder.messages.games"
private const val PIGEON_SERVICE_ACTION = "com.ruskcoder.messages.games.action.GAME_SESSION"
private const val PIGEON_GAME_ACTIVITY = "com.ruskcoder.messages.games.godot.GodotGameActivity"

/** How long to wait for the service to bind before giving up on an answer from it. */
private const val BIND_TIMEOUT_MS = 5_000L

/**
 * Talks to the pigeon app over its own AIDL ([IGameSession]).
 *
 * pigeon is a separate, optional app. It renders and runs the games; this host owns the message
 * side — turning a turn's `Map<String,String>` into an outgoing balloon and back. Every call fails
 * soft when pigeon is not installed, since a game invite must never depend on the sender, or the
 * recipient, actually having it.
 *
 * Held for the life of the process (see `AppContainer`): the move callbacks it registers have to
 * outlive whatever screen opened the game.
 */
class PigeonClient(private val appContext: Context) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var service: IGameSession? = null
    private var bound = false

    /** Actions waiting for the service to finish connecting. Drained on [onServiceConnected]. */
    private val pending = CopyOnWriteArrayList<(IGameSession) -> Unit>()

    /** The local-move listener per open session, re-registered after a reconnect. */
    private val listeners = HashMap<String, (Map<String, String>) -> Unit>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = IGameSession.Stub.asInterface(binder)
            service = svc
            // Re-attach every open session's move listener, then run whatever was queued.
            listeners.forEach { (session, listener) -> registerListener(svc, session, listener) }
            val queued = pending.toList()
            pending.clear()
            queued.forEach { action ->
                runCatching { action(svc) }.onFailure { Log.e(TAG, "queued pigeon action failed", it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(PIGEON_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun ensureBound(): Boolean {
        if (service != null) return true
        if (!bound) {
            if (!isInstalled()) return false
            val intent = Intent(PIGEON_SERVICE_ACTION).apply { setPackage(PIGEON_PACKAGE) }
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) Log.w(TAG, "bindService returned false for $PIGEON_PACKAGE")
        }
        return bound
    }

    /** Run [action] with a connected service — now if it is ready, otherwise once it connects. */
    private fun withService(action: (IGameSession) -> Unit) {
        val svc = service
        if (svc != null) {
            runCatching { action(svc) }.onFailure { Log.e(TAG, "pigeon action failed", it) }
            return
        }
        pending.add(action)
        if (!ensureBound()) {
            pending.remove(action)
            Log.w(TAG, "pigeon not available; dropping queued action")
        }
    }

    /**
     * The opening state for a new game of [gameId], straight from pigeon.
     *
     * Each game needs its own fields to start — Knockout a map and a generated board, Darts a mode
     * and an avatar — and only the app that runs the games knows which. Building the state here
     * instead is what made every invite arrive on an iPhone as "you need to update the app".
     *
     * Null when pigeon is not installed, does not answer, or does not have the game, in which case
     * the caller has nothing better than the bare envelope to send.
     */
    suspend fun newGameState(gameId: String): Map<String, String>? {
        if (!isInstalled()) {
            Log.w(TAG, "pigeon not installed; no opening state for game=$gameId")
            return null
        }
        val state = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                withService { svc ->
                    val answer = runCatching { svc.newGameData(gameId).toStringMap() }
                        .onFailure { Log.e(TAG, "newGameData failed for game=$gameId", it) }
                        .getOrNull()
                    if (continuation.isActive) continuation.resume(answer)
                }
            }
        }
        if (state.isNullOrEmpty()) {
            Log.w(TAG, "pigeon returned no opening state for game=$gameId")
            return null
        }
        return state
    }

    /**
     * Seed [session] with [state], register to hear the local player's move, then open pigeon's
     * game activity — all after the service is actually connected, so the activity never opens onto
     * a session pigeon has not been told about yet.
     *
     * Returns false only when pigeon is not installed. When it is, the work is queued and the game
     * opens as soon as the bind completes.
     */
    fun openGame(
        session: String,
        state: Map<String, String>,
        senderUuid: String,
        onLocalMove: (Map<String, String>) -> Unit,
    ): Boolean {
        if (!isInstalled()) {
            Log.w(TAG, "pigeon not installed; cannot open session=$session")
            return false
        }
        listeners[session] = onLocalMove
        withService { svc ->
            svc.startSession(session, state.toBundle(), senderUuid)
            registerListener(svc, session, onLocalMove)
            launchGameActivity(session, state)
        }
        return true
    }

    /** Push an opponent's incoming turn into a session pigeon may already have open. */
    fun pushOpponentUpdate(session: String, updates: Map<String, String>) {
        withService { svc ->
            svc.updateSession(updates.toBundle(), session, object : IUpdateGameSessionCallback.Stub() {
                override fun onFinished() {}
            })
        }
    }

    private fun registerListener(
        svc: IGameSession,
        session: String,
        listener: (Map<String, String>) -> Unit,
    ) {
        runCatching {
            svc.registerCallback(session, object : IMessageUpdatedCallback.Stub() {
                override fun onMessageUpdated(message: Bundle) {
                    val map = message.toStringMap()
                    main.post { listener(map) }
                }
            })
        }.onFailure { Log.e(TAG, "registerCallback failed for session=$session", it) }
    }

    private fun launchGameActivity(session: String, state: Map<String, String>) {
        runCatching {
            appContext.startActivity(
                Intent().apply {
                    component = ComponentName(PIGEON_PACKAGE, PIGEON_GAME_ACTIVITY)
                    putExtra("SESSION", session)
                    putExtra("GAME", state["game"])
                    putExtra("DISPLAY_GAME", state["game_name"])
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure { Log.e(TAG, "could not launch pigeon game activity", it) }
    }
}

private fun Map<String, String>.toBundle(): Bundle = Bundle().also { b ->
    for ((k, v) in this) b.putString(k, v)
}

private fun Bundle.toStringMap(): Map<String, String> =
    keySet().mapNotNull { key -> getString(key)?.let { key to it } }.toMap()

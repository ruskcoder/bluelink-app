package com.ruskcoder.messages.games

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import android.widget.RemoteViews
import com.bluebubbles.messaging.IKeyboardHandle
import com.bluebubbles.messaging.IMadridExtension
import com.bluebubbles.messaging.IMessageViewHandle
import com.bluebubbles.messaging.ITaskCompleteCallback
import com.bluebubbles.messaging.IViewUpdateCallback
import com.bluebubbles.messaging.MadridMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "OpenPigeonClient"

/** OpenPigeon's package, and the action its extension service answers on. */
const val OPENPIGEON_PACKAGE = "com.openbubbles.openpigeon"
private const val MADRID_ACTION = "com.openbubbles.messaging.MadridExtensionService"

/**
 * Talks to OpenPigeon over BlueBubbles' Madrid extension protocol.
 *
 * This is a different shape from [PigeonClient], and the difference is the whole point. pigeon
 * hands us a game's opening state and lets this app build the balloon and launch the activity;
 * OpenPigeon owns all of that itself. It draws its own game picker (as [RemoteViews] we display),
 * decides what a new game's message looks like, opens its own activities, and renders its own
 * bubble previews. We supply the two things only a messaging app can: a way to put a message on
 * the wire, and the message a bubble was tapped from.
 *
 * That is why the host cannot simply ask it for a game list. It is an *extension*, not a library —
 * the same contract BlueBubbles implements, which is why OpenPigeon's ~25 finished games work
 * through it without a line of game code living here.
 *
 * Held for the life of the process (see `AppContainer`): the callbacks registered here have to
 * outlive whatever screen opened a game.
 */
class OpenPigeonClient(private val appContext: Context) {

    @Volatile
    private var extension: IMadridExtension? = null
    private var bound = false

    /** Work waiting for the bind to finish. Drained on [ServiceConnection.onServiceConnected]. */
    private val pending = CopyOnWriteArrayList<(IMadridExtension) -> Unit>()

    /** Per-session hook for a move the local player just made, keyed by balloon session. */
    private val moveListeners = ConcurrentHashMap<String, (MadridMessage) -> Unit>()

    /** Where a game composed in the picker should be sent. Set while the picker is open. */
    @Volatile
    private var composeListener: ((MadridMessage) -> Unit)? = null

    /** The newest picker view, pushed by the extension as the user pages through it. */
    @Volatile
    private var pickerListener: ((RemoteViews) -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val ext = IMadridExtension.Stub.asInterface(binder)
            extension = ext
            val queued = pending.toList()
            pending.clear()
            queued.forEach { action ->
                runCatching { action(ext) }.onFailure { Log.e(TAG, "queued action failed", it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            extension = null
        }
    }

    fun isInstalled(): Boolean = try {
        appContext.packageManager.getPackageInfo(OPENPIGEON_PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * The iMessage balloon identity this extension claims, read from its service metadata.
     *
     * OpenPigeon declares GamePigeon's own bundle id, App Store id and display name on
     * `MadridExtensionService`, exactly as BlueBubbles expects to find them. Reading them rather
     * than hardcoding [GamePigeon.BUNDLE_ID] is what would let a second extension work here.
     */
    fun balloonIdentity(): BalloonIdentity? {
        val intent = Intent(MADRID_ACTION).apply { setPackage(OPENPIGEON_PACKAGE) }
        val info = appContext.packageManager
            .queryIntentServices(intent, PackageManager.GET_META_DATA)
            .firstOrNull()
            ?.serviceInfo
            ?: return null
        val meta = info.metaData ?: return null
        val bundleId = meta.getString("madrid_bundle_id") ?: return null
        return BalloonIdentity(
            bundleId = bundleId,
            appName = meta.getString("madrid_name") ?: "GamePigeon",
            appId = meta.getString("madrid_id")?.toLongOrNull() ?: GamePigeon.APP_ID,
        )
    }

    data class BalloonIdentity(val bundleId: String, val appName: String, val appId: Long)

    private fun ensureBound(): Boolean {
        if (extension != null) return true
        if (!bound) {
            if (!isInstalled()) return false
            val intent = Intent(MADRID_ACTION).apply { setPackage(OPENPIGEON_PACKAGE) }
            bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (!bound) Log.w(TAG, "bindService returned false for $OPENPIGEON_PACKAGE")
        }
        return bound
    }

    private fun withExtension(action: (IMadridExtension) -> Unit) {
        val ext = extension
        if (ext != null) {
            runCatching { action(ext) }.onFailure { Log.e(TAG, "extension call failed", it) }
            return
        }
        pending.add(action)
        if (!ensureBound()) {
            pending.remove(action)
            Log.w(TAG, "OpenPigeon not available; dropping queued action")
        }
    }

    /**
     * Open OpenPigeon's game picker.
     *
     * The extension composes the grid itself and hands back [RemoteViews] for us to display;
     * picking a game there calls back into [onCompose] with a finished message to send. [onView]
     * fires again whenever the extension redraws — it pages its own list, so the first view is not
     * the only one.
     *
     * [userCount] is how many people are in the conversation; some games only offer themselves
     * one-to-one.
     */
    fun openPicker(
        userCount: Int,
        onView: (RemoteViews) -> Unit,
        onCompose: (MadridMessage) -> Unit,
    ): Boolean {
        if (!isInstalled()) return false
        pickerListener = onView
        composeListener = onCompose
        withExtension { ext ->
            val views = ext.keyboardOpened(viewUpdates, keyboardHandle, userCount)
            if (views != null) onView(views)
        }
        return true
    }

    fun closePicker() {
        pickerListener = null
        composeListener = null
        withExtension { ext -> ext.keyboardClosed() }
    }

    /**
     * Open the game a bubble belongs to, and hear about the moves played in it.
     *
     * OpenPigeon launches its own activity for the game; [onLocalMove] fires when the player
     * finishes a turn there, with the message this app should put on the wire.
     */
    fun openGame(message: MadridMessage, userCount: Int, onLocalMove: (MadridMessage) -> Unit) {
        val session = message.session ?: message.messageGuid
        moveListeners[session] = onLocalMove
        withExtension { ext -> ext.didTapTemplate(message, messageViewHandle(session), userCount) }
    }

    /** Push an opponent's turn into a game OpenPigeon may already have open. */
    fun pushOpponentUpdate(message: MadridMessage) {
        withExtension { ext -> ext.messageUpdated(message) }
    }

    /**
     * The bubble's own rendering, drawn by the extension.
     *
     * Returns null when OpenPigeon is not there to draw it, in which case the caller falls back to
     * this app's own invite tile.
     */
    fun liveView(message: MadridMessage, userCount: Int, onUpdate: (RemoteViews) -> Unit): Boolean {
        if (!isInstalled()) return false
        val session = message.session ?: message.messageGuid
        liveViewListeners[session] = onUpdate
        withExtension { ext ->
            val views = ext.getLiveView(liveUpdates(session), message, messageViewHandle(session), userCount)
            if (views != null) onUpdate(views)
        }
        return true
    }

    private val liveViewListeners = ConcurrentHashMap<String, (RemoteViews) -> Unit>()

    // ---------------------------------------------------------------- callbacks we implement

    /** Picker redraws. */
    private val viewUpdates = object : IViewUpdateCallback.Stub() {
        override fun updateView(views: RemoteViews?) {
            if (views != null) pickerListener?.invoke(views)
        }
    }

    private fun liveUpdates(session: String) = object : IViewUpdateCallback.Stub() {
        override fun updateView(views: RemoteViews?) {
            if (views != null) liveViewListeners[session]?.invoke(views)
        }
    }

    /** A game chosen in the picker: the extension has composed the opening message for us. */
    private val keyboardHandle = object : IKeyboardHandle.Stub() {
        override fun addMessage(message: MadridMessage?) {
            if (message == null) return
            val listener = composeListener
            if (listener == null) {
                Log.w(TAG, "a game was composed with no picker open; dropping it")
                return
            }
            listener(message)
        }
    }

    /**
     * The handle an open game holds onto. `updateMessage` is a played turn; the lock and the
     * notification suppression are the extension asking us not to disturb a game in progress.
     */
    private fun messageViewHandle(session: String) = object : IMessageViewHandle.Stub() {
        override fun updateMessage(message: MadridMessage?, callback: ITaskCompleteCallback?) {
            if (message != null) moveListeners[session]?.invoke(message)
            runCatching { callback?.complete() }
        }

        override fun setSuppressNotifications(suppress: Boolean) {
            suppressed[session] = suppress
        }

        override fun lock() = Unit

        override fun unlock() = Unit
    }

    /** Sessions whose game is on screen right now, so a turn in them does not also buzz. */
    private val suppressed = ConcurrentHashMap<String, Boolean>()

    fun isSuppressed(session: String): Boolean = suppressed[session] == true
}

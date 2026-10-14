package com.ruskcoder.messages.web

import android.content.Context
import android.util.Log
import com.ruskcoder.messages.data.datastore.SettingsRepository
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.data.repository.ContactsRepository
import com.ruskcoder.messages.imessage.IMessageSender
import com.ruskcoder.messages.util.appJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.random.Random

/**
 * Keeps this phone reachable by the browsers linked to it.
 *
 * One outbound WebSocket per linked browser, to the relay room named at pairing. Outbound because
 * that is what works everywhere — on cellular, behind a carrier NAT, on a Wi-Fi the computer isn't
 * on — and it is the same reason the Apple push connection is outbound. The relay is blind: every
 * frame is sealed with keys only this phone and that browser hold. See messages-web's
 * docs/CONNECTIVITY.md.
 *
 * Lives on the app scope and is started by [com.ruskcoder.messages.imessage.PushService], so a
 * browser can reach the phone for as long as iMessage itself can.
 */
class WebBridge(
    context: Context,
    private val scope: CoroutineScope,
    private val chats: ChatRepository,
    private val settings: SettingsRepository,
    contacts: ContactsRepository,
    sender: () -> IMessageSender,
    /** Who is typing where, keyed by chat. A lambda because reading it builds the iMessage syncer. */
    private val typing: () -> StateFlow<Map<String, List<String>>>,
    transcriptions: () -> com.ruskcoder.messages.transcribe.VoiceTranscriptions?,
) {
    enum class LinkState {
        /** Relay unreachable; retrying with backoff. */
        OFFLINE,
        CONNECTING,

        /** In the room, browser not open. */
        READY,

        /** The browser has the page open right now. */
        ACTIVE,
    }

    private val store = WebPairingStore(context.applicationContext)
    private val rpc = WebRpc(context.applicationContext, chats, contacts, settings, sender, transcriptions)

    private val _pairings = MutableStateFlow(store.load())
    val pairings: StateFlow<List<WebPairing>> = _pairings.asStateFlow()

    private val _states = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    val states: StateFlow<Map<String, LinkState>> = _states.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // A relay socket is silent for long stretches; liveness is the ping loop's job, not a read timeout's.
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val links = ConcurrentHashMap<String, Link>()
    @Volatile private var started = false

    /** Idempotent. */
    fun start() {
        if (started) return
        started = true
        _pairings.value.forEach { pairing -> links.getOrPut(pairing.room) { Link(pairing, hello = null).also { it.connect() } } }
    }

    /** The default network changed: sockets on the old one may be silently dead, so don't wait for the ping loop. */
    fun reconnect() {
        links.values.forEach { it.restart() }
    }

    /**
     * Links the browser whose QR code this is.
     *
     * Suspends until the relay has accepted the connection and the phone's half of the handshake is
     * on its way, so the screen can say "linked" truthfully — or throws with a reason worth
     * showing, and leaves nothing behind.
     */
    suspend fun pair(code: String): WebPairing = withContext(Dispatchers.Default) {
        val offer = WebPairingOffer.parse(code)
        val keyPair = WebCrypto.generateKeyPair()
        val keys = WebCrypto.deriveSessionKeys(keyPair, offer.key, offer.secret, offer.room)
        val pairing = WebPairing(
            room = offer.room,
            relay = offer.relay,
            name = offer.name,
            sendKey = WebCrypto.b64(keys.send),
            receiveKey = WebCrypto.b64(keys.receive),
            pairedAt = System.currentTimeMillis(),
        )

        links.remove(pairing.room)?.close()
        val link = Link(pairing, hello = WebCrypto.b64(WebCrypto.rawPublicKey(keyPair)))
        links[pairing.room] = link
        started = true
        link.connect()

        val opened = withTimeoutOrNull(PAIR_TIMEOUT_MS) { runCatching { link.opened.await() } }
        if (opened == null || opened.isFailure) {
            links.remove(pairing.room)?.close()
            _states.update { it - pairing.room }
            val host = runCatching { URI(offer.relay).host }.getOrNull() ?: offer.relay
            val cause = opened?.exceptionOrNull()?.message?.let { " ($it)" }.orEmpty()
            throw IOException("Couldn't reach the relay at $host$cause")
        }

        persist(_pairings.value.filterNot { it.room == pairing.room } + pairing)
        pairing
    }

    /** Unlinks a browser. It is told first, so the page signs itself out instead of waiting forever. */
    fun remove(room: String) {
        links.remove(room)?.let { link ->
            link.sendEvent("unpaired", JsonObject(emptyMap()))
            link.close()
        }
        _states.update { it - room }
        persist(_pairings.value.filterNot { it.room == room })
    }

    private fun persist(list: List<WebPairing>) {
        _pairings.value = list
        scope.launch(Dispatchers.IO) { store.save(list) }
    }

    private inner class Link(private val pairing: WebPairing, @Volatile private var hello: String?) {
        private val sendKey = WebCrypto.unb64(pairing.sendKey)
        private val receiveKey = WebCrypto.unb64(pairing.receiveKey)

        /** Completed once the relay accepts this socket; only awaited while pairing. */
        val opened = CompletableDeferred<Unit>()

        @Volatile private var socket: WebSocket? = null
        @Volatile private var closed = false
        @Volatile private var lastPong = 0L
        @Volatile private var browserPresent = false
        private var attempt = 0
        private var keepalive: Job? = null
        private var chatsJob: Job? = null
        private var typingJob: Job? = null
        private var settingsJob: Job? = null
        private var watchJob: Job? = null

        /**
         * Frames in arrival order. Handling them concurrently would let a later `thread.watch`
         * finish before an earlier one and leave the wrong conversation watched.
         */
        private val inbox = Channel<String>(Channel.UNLIMITED)
        private val consumer = scope.launch { for (text in inbox) handle(text) }

        /** IVs of recently accepted requests, for replay rejection. */
        private val seen = LinkedHashMap<String, Long>()

        fun connect() {
            if (closed) return
            setState(LinkState.CONNECTING)
            val url = "${pairing.relay.trimEnd('/')}/room/${pairing.room}?role=phone"
            val request = runCatching { Request.Builder().url(url).build() }.getOrElse {
                opened.completeExceptionally(it)
                setState(LinkState.OFFLINE)
                return
            }
            socket = http.newWebSocket(request, Listener())
        }

        fun restart() {
            socket?.cancel()
        }

        fun close() {
            closed = true
            keepalive?.cancel()
            stopStreaming()
            consumer.cancel()
            socket?.close(1000, "unlinked")
            socket = null
        }

        private inner class Listener : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                lastPong = System.currentTimeMillis()
                hello?.let { key ->
                    // Our public key goes in the clear; the event after it is the first thing sealed
                    // with the new keys, and decrypting it is how the browser knows they agree.
                    webSocket.send("""{"t":"hello","k":"$key"}""")
                    sendEvent("paired", buildJsonObject { put("device", WebRpc.deviceName()) }, webSocket)
                    hello = null
                }
                opened.complete(Unit)
                setState(LinkState.READY)
                keepalive?.cancel()
                keepalive = scope.launch { ping(webSocket) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text == "pong") {
                    lastPong = System.currentTimeMillis()
                    return
                }
                inbox.trySend(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, t)
        }

        private suspend fun ping(webSocket: WebSocket) {
            while (true) {
                delay(PING_INTERVAL_MS)
                if (System.currentTimeMillis() - lastPong > PONG_TIMEOUT_MS) {
                    Log.i(TAG, "relay stopped answering, reconnecting")
                    webSocket.cancel()
                    return
                }
                webSocket.send("ping")
            }
        }

        private fun dropped(webSocket: WebSocket, error: Throwable?) {
            if (webSocket !== socket) return
            socket = null
            keepalive?.cancel()
            stopStreaming()
            if (hello != null) {
                // Still pairing: fail fast with the real reason rather than retrying behind a spinner.
                opened.completeExceptionally(error ?: IOException("closed"))
                return
            }
            if (closed) return
            setState(LinkState.OFFLINE)
            error?.let { Log.i(TAG, "relay connection lost: ${it.message}") }
            val backoff = minOf(MAX_BACKOFF_MS, 1000L shl minOf(attempt++, 6))
            scope.launch {
                delay(backoff + Random.nextLong(500))
                connect()
            }
        }

        private suspend fun handle(text: String) {
            val frame = runCatching { appJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (frame["t"]?.jsonPrimitive?.contentOrNull) {
                "presence" -> onPresence(frame["web"]?.jsonPrimitive?.booleanOrNull == true)
                "m" -> onSealed(frame["d"]?.jsonPrimitive?.contentOrNull ?: return)
            }
        }

        private fun onPresence(web: Boolean) {
            if (web == browserPresent) return
            browserPresent = web
            setState(if (web) LinkState.ACTIVE else LinkState.READY)
            if (web) {
                chatsJob = scope.launch { streamChats() }
                typingJob = scope.launch { streamTyping() }
                settingsJob = scope.launch { streamSettings() }
            } else {
                stopStreaming()
            }
        }

        private suspend fun onSealed(data: String) {
            val (iv, plaintext) = runCatching { WebCrypto.open(receiveKey, data) }.getOrNull() ?: return
            val inner = runCatching { appJson.parseToJsonElement(plaintext.decodeToString()).jsonObject }.getOrNull() ?: return
            if (inner["type"]?.jsonPrimitive?.contentOrNull != "req") return
            val id = inner["id"]?.jsonPrimitive?.intOrNull ?: return
            val method = inner["method"]?.jsonPrimitive?.contentOrNull ?: return

            // The relay can't forge a request, but it could record one and play it back; a replayed
            // `message.send` is a duplicate text somebody receives. Fresh and never-seen-before only.
            val now = System.currentTimeMillis()
            val sentAt = inner["ts"]?.jsonPrimitive?.longOrNull ?: 0L
            if (abs(now - sentAt) > REPLAY_WINDOW_MS) {
                respond(id, error = "The clocks on your phone and computer disagree by more than 5 minutes")
                return
            }
            seen.entries.removeAll { now - it.value > REPLAY_WINDOW_MS * 2 }
            if (seen.put(iv, now) != null) return

            val params = inner["params"] as? JsonObject ?: JsonObject(emptyMap())
            val work = suspend {
                try {
                    respond(id, result = rpc.handle(method, params, ::watch))
                } catch (e: WebRpcException) {
                    respond(id, error = e.message)
                } catch (e: Exception) {
                    Log.w(TAG, "request $method failed", e)
                    respond(id, error = "Something went wrong on your phone")
                }
            }
            // Most requests run side by side, so twenty thumbnails loading don't hold up a send.
            // The few whose order matters are answered before the next frame is looked at.
            if (rpc.isOrdered(method)) work() else scope.launch { work() }
        }

        private fun watch(chatId: String?, limit: Int) {
            watchJob?.cancel()
            watchJob = chatId?.let { id -> scope.launch { streamThread(id, limit) } }
        }

        @OptIn(FlowPreview::class)
        private suspend fun streamTyping() {
            typing().debounce(DEBOUNCE_MS).collect { map ->
                val data = buildJsonObject {
                    map.forEach { (chatId, handles) ->
                        if (handles.isNotEmpty()) {
                            put(chatId, kotlinx.serialization.json.JsonArray(rpc.typingNames(chatId, handles).map { kotlinx.serialization.json.JsonPrimitive(it) }))
                        }
                    }
                }
                sendEvent("typing", data)
            }
        }

        /**
         * Settings as they change, so the page follows the phone's look without being reloaded.
         *
         * The chat list goes out again with them: each conversation's read-receipt and typing
         * switches are resolved against Settings before they leave the phone.
         */
        @OptIn(FlowPreview::class)
        private suspend fun streamSettings() {
            settings.settings.drop(1).debounce(DEBOUNCE_MS).collect { current ->
                sendEvent("settings", rpc.settingsSnapshot())
                sendEvent("chats", appJson.encodeToJsonElement(ListSerializer(WireChat.serializer()), chats.chats.value.map { it.toWire(current) }))
            }
        }

        @OptIn(FlowPreview::class)
        private suspend fun streamChats() {
            // The browser asks for the list itself on arrival; this carries only what changes after.
            chats.chats.drop(1).debounce(DEBOUNCE_MS).collect { list ->
                sendEvent("chats", appJson.encodeToJsonElement(ListSerializer(WireChat.serializer()), list.map { it.toWire(settings.settings.value) }))
            }
        }

        @OptIn(FlowPreview::class)
        private suspend fun streamThread(chatId: String, limit: Int) {
            // A transcription starting or failing changes nothing in storage, but it changes what the
            // bubble should say, so it re-sends the thread too.
            kotlinx.coroutines.flow.combine(chats.threadFlow(chatId), rpc.transcriptionChanges()) { messages, _ -> messages }
                .debounce(DEBOUNCE_MS).collect { messages ->
                if (messages.isEmpty()) return@collect
                // Halve until it fits: a thread of long messages must still arrive, just shorter.
                var take = limit
                while (true) {
                    val payload = buildJsonObject {
                        put("chatId", chatId)
                        put("messages", rpc.encodeThread(chatId, messages.takeLast(take), messages))
                    }
                    // Sealing adds a tag and base64 (4/3) on top of the JSON.
                    if (payload.toString().length * 4 / 3 + 256 <= MAX_FRAME_CHARS || take <= 10) {
                        sendEvent("thread", payload)
                        break
                    }
                    take /= 2
                }
            }
        }

        private fun stopStreaming() {
            chatsJob?.cancel()
            typingJob?.cancel()
            settingsJob?.cancel()
            watchJob?.cancel()
            chatsJob = null
            typingJob = null
            settingsJob = null
            watchJob = null
        }

        private fun respond(id: Int, result: JsonElement? = null, error: String? = null) {
            send(buildJsonObject {
                put("type", "res")
                put("id", id)
                put("ok", error == null)
                if (error == null) put("result", result ?: kotlinx.serialization.json.JsonNull) else put("error", error)
            })
        }

        fun sendEvent(event: String, data: JsonElement, target: WebSocket? = null) {
            send(
                buildJsonObject {
                    put("type", "evt")
                    put("event", event)
                    put("data", data)
                },
                target,
            )
        }

        private fun send(inner: JsonObject, target: WebSocket? = null) {
            val sealed = """{"t":"m","d":"${WebCrypto.seal(sendKey, inner.toString().toByteArray())}"}"""
            (target ?: socket)?.send(sealed)
        }

        private fun setState(state: LinkState) {
            if (closed) return
            _states.update { it + (pairing.room to state) }
        }
    }

    private companion object {
        const val TAG = "WebBridge"
        const val PAIR_TIMEOUT_MS = 15_000L
        const val PING_INTERVAL_MS = 25_000L
        const val PONG_TIMEOUT_MS = 70_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val REPLAY_WINDOW_MS = 5 * 60_000L
        const val DEBOUNCE_MS = 150L
        const val MAX_FRAME_CHARS = 900_000
    }
}

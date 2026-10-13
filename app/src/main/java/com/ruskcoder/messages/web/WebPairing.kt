package com.ruskcoder.messages.web

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.ruskcoder.messages.util.appJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One browser this phone has been linked to.
 *
 * Holds the two derived session keys rather than the key pair and secret they came from: those are
 * only needed for the moment of pairing, and a key that no longer exists cannot leak.
 */
@Serializable
data class WebPairing(
    /** The relay room, and this pairing's identity. */
    val room: String,
    val relay: String,
    /** What the browser called itself — "Chrome on Windows". */
    val name: String,
    val sendKey: String,
    val receiveKey: String,
    val pairedAt: Long,
)

/** What a browser's QR code says. See `packages/protocol/src/pairing.ts`. */
internal data class WebPairingOffer(
    val relay: String,
    val room: String,
    val key: ByteArray,
    val secret: ByteArray,
    val name: String,
) {
    @Serializable
    private data class Raw(val r: String, val i: String, val k: String, val s: String, val n: String = "Browser")

    companion object {
        private const val PREFIX = "mweb1:"
        private val ROOM = Regex("^[A-Za-z0-9_-]{22,64}$")

        fun parse(code: String): WebPairingOffer {
            require(code.startsWith(PREFIX)) { "That isn't a Messages for web code." }
            val raw = runCatching { appJson.decodeFromString(Raw.serializer(), code.removePrefix(PREFIX)) }
                .getOrElse { throw IllegalArgumentException("That code couldn't be read. Refresh the page and try again.") }
            require(raw.r.startsWith("ws://") || raw.r.startsWith("wss://")) { "The code names an invalid relay." }
            require(ROOM.matches(raw.i)) { "The code names an invalid room." }
            val key = WebCrypto.unb64(raw.k)
            val secret = WebCrypto.unb64(raw.s)
            require(key.size == 65 && secret.size == 32) { "The code's keys are malformed." }
            return WebPairingOffer(raw.r, raw.i, key, secret, raw.n.take(60))
        }
    }
}

/**
 * Linked browsers, kept in app-private preferences and sealed with an Android Keystore key.
 *
 * The Keystore wrap is the point: the session keys are what let a browser act as this phone, so
 * a copy of the preferences file pulled off a backup or a rooted device must not be enough to
 * reuse them.
 */
internal class WebPairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("web_pairings", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(WebPairing.serializer())

    fun load(): List<WebPairing> = runCatching {
        val sealed = prefs.getString(KEY, null) ?: return emptyList()
        val bytes = WebCrypto.unb64(sealed)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes, 0, 12))
        val json = cipher.doFinal(bytes, 12, bytes.size - 12).decodeToString()
        appJson.decodeFromString(serializer, json)
    }.getOrElse {
        Log.w(TAG, "linked browsers unreadable, starting empty", it)
        emptyList()
    }

    fun save(pairings: List<WebPairing>) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val sealed = cipher.iv + cipher.doFinal(appJson.encodeToString(serializer, pairings).toByteArray())
        prefs.edit().putString(KEY, WebCrypto.b64(sealed)).apply()
    }

    private fun wrappingKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }

    private companion object {
        const val TAG = "WebPairingStore"
        const val KEY = "pairings"
        const val ALIAS = "web_pairings"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

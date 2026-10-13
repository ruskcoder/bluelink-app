package com.ruskcoder.messages.imessage

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Turns whatever the user scanned or pasted into something the Rust side can accept.
 *
 * Apple will not register a client that cannot produce validation data derived from genuine Apple
 * hardware, and there are two ways to be able to produce it.
 *
 * An **activation code** carries a Mac's identifiers, which are stored on this device; validation
 * data is then computed locally from them, so the Mac is never contacted again and need not even be
 * switched on. That local computation lives in OpenAbsinthe, which is closed source — a build
 * without it can accept these codes but cannot sign in with one.
 *
 * A **relay code** points at an agent running on someone's Mac or iPhone that will produce
 * validation data on request. Nothing is computed here, so no OpenAbsinthe is involved, but the
 * relay has to be reachable on every registration.
 *
 * The formats are documented in `docs/IMESSAGE_PLAN.md` section 5.
 */
sealed interface ActivationInput {

    /** An identity to keep: bytes ready to hand to `configFromActivationPayload`. */
    class Payload(val bytes: ByteArray) : ActivationInput {
        // Arrays compare by identity by default, which would make two equal payloads look different.
        override fun equals(other: Any?) =
            this === other || (other is Payload && bytes.contentEquals(other.bytes))

        override fun hashCode() = bytes.contentHashCode()
    }

    /**
     * A relay to defer to, rather than an identity to keep.
     *
     * Nothing about the Apple device is copied here — only the address of the agent that will be
     * asked for validation data on every registration.
     */
    data class Relay(
        val code: String,
        val host: String,
        val token: String?,
    ) : ActivationInput
}

class ActivationCodeException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

@OptIn(ExperimentalEncodingApi::class)
object ActivationCodes {

    private const val OPENBUBBLES_ROOT = "https://hw.openbubbles.app/code"

    /** Beeper's public registration relay, which is the one a bare relay code belongs to. */
    private const val BEEPER_RELAY_HOST = "https://registration-relay.beeper.com"

    /**
     * Not a secret. The relay rejects calls without an access token, and this is the burner
     * account's, published in OpenBubbles for exactly this purpose. It identifies the client, not
     * the user, and it is the reason a relay code alone is enough to get started.
     */
    private const val BEEPER_RELAY_TOKEN = "5c175851953ecaf5209185d897591badb6c3e712"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolve raw QR bytes.
     *
     * A QR produced by OpenBubbles' "share hardware" screen encodes the identity directly as binary,
     * so when the scanner hands back bytes that already carry the `OABS` magic there is nothing to
     * fetch. Anything else is treated as text.
     */
    suspend fun resolve(rawBytes: ByteArray?, text: String): ActivationInput {
        if (rawBytes != null && rawBytes.size > 5 && rawBytes.copyOfRange(0, 4).decodeToString() == OABS_MAGIC) {
            return ActivationInput.Payload(rawBytes)
        }
        return resolve(text)
    }

    /** Resolve scanned or pasted text. */
    suspend fun resolve(text: String): ActivationInput {
        val trimmed = text.trim()

        if (trimmed.startsWith("$OPENBUBBLES_ROOT/")) {
            return fetchShortCode(trimmed.removePrefix("$OPENBUBBLES_ROOT/"))
        }

        // OpenBubbles' short activation codes look like `MBxxxx-xxxx-xxxx-xxxx`.
        val segments = trimmed.split("-")
        if (segments.size == 4 && trimmed.length == 21 && segments[0].length == 6) {
            return fetchShortCode(trimmed)
        }

        // A registration relay code is four groups of four, where the short code above carries a
        // six-character prefix. Shape is all that separates them, which is how OpenBubbles' own
        // `checkCode` decides too.
        if (segments.size == 4 && trimmed.length == 19 && segments[0].length == 4) {
            return ActivationInput.Relay(trimmed, BEEPER_RELAY_HOST, BEEPER_RELAY_TOKEN)
        }

        val decoded = runCatching { Base64.decode(trimmed) }.getOrElse {
            throw ActivationCodeException("That does not look like an activation code.")
        }
        return ActivationInput.Payload(decoded)
    }

    /**
     * Exchange a short activation code for the identity it stands for.
     *
     * The producer uploaded its identity encrypted under the code itself, filed under the code's
     * SHA-256. So the code never travels to the server in the clear, and the server never holds
     * anything it could decrypt.
     */
    private suspend fun fetchShortCode(code: String): ActivationInput =
        withContext(Dispatchers.IO) {
            val hash = sha256Hex(code)
            val connection = (URL("$OPENBUBBLES_ROOT/$hash").openConnection() as HttpURLConnection)
            val body = try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("X-OpenBubbles-Get", "")
                connection.connectTimeout = 30_000
                connection.readTimeout = 30_000

                when (connection.responseCode) {
                    200 -> connection.inputStream.readBytes().decodeToString()
                    404 -> throw ActivationCodeException("That code has expired or was already used.")
                    else -> throw ActivationCodeException(
                        "The hardware service returned ${connection.responseCode}."
                    )
                }
            } finally {
                connection.disconnect()
            }

            val encrypted = json.parseToJsonElement(body).jsonObject["data"]?.jsonPrimitive?.content
                ?: throw ActivationCodeException("The hardware service returned no data.")

            val decrypted = runCatching { CryptoJs.decrypt(encrypted, code) }.getOrElse {
                throw ActivationCodeException("Could not decrypt that code.", it)
            }
            ActivationInput.Payload(decrypted)
        }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    const val OABS_MAGIC = "OABS"
}

/**
 * CryptoJS's default AES envelope, which is what the hardware service stores.
 *
 * The format is OpenSSL's: `base64("Salted__" || salt[8] || AES-256-CBC-PKCS7(plaintext))`, with the
 * key and IV derived by `EVP_BytesToKey` — MD5, no iteration count, no HMAC. That is weak by modern
 * standards, but it is not ours to choose: it has to match what the producer wrote.
 */
@OptIn(ExperimentalEncodingApi::class)
internal object CryptoJs {

    private const val SALT_PREFIX = "Salted__"
    private const val KEY_LENGTH = 32
    private const val IV_LENGTH = 16

    fun decrypt(encoded: String, passphrase: String): ByteArray {
        val blob = Base64.decode(encoded)
        require(blob.size > 16) { "Ciphertext is too short" }
        require(blob.copyOfRange(0, 8).decodeToString() == SALT_PREFIX) {
            "Missing OpenSSL salt header"
        }

        val salt = blob.copyOfRange(8, 16)
        val ciphertext = blob.copyOfRange(16, blob.size)
        val (key, iv) = deriveKeyAndIv(passphrase.toByteArray(Charsets.UTF_8), salt)

        return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(ciphertext)
        }
    }

    /**
     * `EVP_BytesToKey`: hash passphrase and salt repeatedly, feeding each digest back in, until
     * there is enough output for both the key and the IV.
     */
    private fun deriveKeyAndIv(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val material = ByteArrayOutputStream()
        var previous = ByteArray(0)

        while (material.size() < KEY_LENGTH + IV_LENGTH) {
            md5.reset()
            md5.update(previous)
            md5.update(password)
            md5.update(salt)
            previous = md5.digest()
            material.write(previous)
        }

        val bytes = material.toByteArray()
        return bytes.copyOfRange(0, KEY_LENGTH) to
            bytes.copyOfRange(KEY_LENGTH, KEY_LENGTH + IV_LENGTH)
    }
}

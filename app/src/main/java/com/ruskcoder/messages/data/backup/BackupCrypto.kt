package com.ruskcoder.messages.data.backup

import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-sealed payloads for [BackupEnvelope].
 *
 * Ordinary JCA rather than the Android Keystore, and that is the point: a key the keystore holds
 * cannot leave the device, so a backup sealed with one would be unreadable on the phone it is meant
 * to be restored onto. The passphrase the user types is the only thing that travels.
 */
object BackupCrypto {
    /** Preferred derivation. Not on every device this app runs on — see [keyFactory]. */
    private const val PBKDF2_SHA256 = "PBKDF2WithHmacSHA256"

    /**
     * The fallback, for API 24 and 25 where `PBKDF2WithHmacSHA256` is not registered.
     *
     * Weaker per iteration, so [iterationsFor] compensates. Whichever ran is written into the
     * envelope, so a file sealed on an older phone still opens on a newer one and the reverse.
     */
    private const val PBKDF2_SHA1 = "PBKDF2WithHmacSHA1"

    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()

    /** The strongest PBKDF2 this device actually offers, and its JCA name. */
    private fun keyFactory(name: String? = null): Pair<SecretKeyFactory, String> {
        val candidates = if (name != null) listOf(name) else listOf(PBKDF2_SHA256, PBKDF2_SHA1)
        for (candidate in candidates) {
            val factory = runCatching { SecretKeyFactory.getInstance(candidate) }.getOrNull()
            if (factory != null) return factory to candidate
        }
        throw BackupException(
            if (name != null) "This device cannot perform $name, which the backup was written with."
            else "This device offers no supported key derivation.",
            NoSuchAlgorithmException(candidates.joinToString()),
        )
    }

    /**
     * Iteration count, matched to the digest so both land in the same ballpark of work.
     *
     * SHA-1 gets more because each of its rounds buys less. Chosen off OWASP's current guidance,
     * and recorded in the envelope rather than assumed, so this can be raised without breaking
     * files already written.
     */
    private fun iterationsFor(algorithm: String) = if (algorithm == PBKDF2_SHA1) 720_000 else 210_000

    private fun deriveKey(passphrase: String, salt: ByteArray, iterations: Int, algorithm: String): SecretKeySpec {
        val (factory, _) = keyFactory(algorithm)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** Seal `plaintext` under `passphrase`, returning the envelope fields that describe it. */
    fun seal(plaintext: ByteArray, passphrase: String, createdAt: Long): BackupEnvelope {
        val (_, algorithm) = keyFactory()
        val iterations = iterationsFor(algorithm)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations, algorithm), GCMParameterSpec(TAG_BITS, iv))

        return BackupEnvelope(
            createdAt = createdAt,
            kdf = algorithm,
            iterations = iterations,
            salt = salt.base64(),
            iv = iv.base64(),
            payload = cipher.doFinal(plaintext).base64(),
        )
    }

    /**
     * Open a sealed envelope.
     *
     * GCM authenticates as it decrypts, so a wrong passphrase and a truncated or edited file both
     * surface here as the same tag failure. That is reported as a wrong passphrase, which is what
     * it nearly always is — and a file the user cannot open is the same outcome either way.
     */
    fun open(envelope: BackupEnvelope, passphrase: String): ByteArray {
        val key = deriveKey(passphrase, envelope.salt.decodeBase64(), envelope.iterations, envelope.kdf)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, envelope.iv.decodeBase64()))
        return try {
            cipher.doFinal(envelope.payload.decodeBase64())
        } catch (e: javax.crypto.AEADBadTagException) {
            throw BackupException("That passphrase does not open this backup.", e)
        } catch (e: javax.crypto.BadPaddingException) {
            throw BackupException("That passphrase does not open this backup.", e)
        }
    }
}

/** Base64 without line breaks, so the result is safe to drop straight into a JSON string. */
fun ByteArray.base64(): String = android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)

fun String.decodeBase64(): ByteArray =
    runCatching { android.util.Base64.decode(this, android.util.Base64.NO_WRAP) }
        .getOrElse { throw BackupException("This backup file is damaged.", it) }

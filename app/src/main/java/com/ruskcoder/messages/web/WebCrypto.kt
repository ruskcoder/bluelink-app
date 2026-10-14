package com.ruskcoder.messages.web

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The phone's half of the browser link's encryption.
 *
 * A byte-for-byte mirror of `src/protocol/crypto.ts` in messages-web: P-256 ECDH, then
 * HKDF-SHA256 salted with the pairing secret from the QR code, then AES-256-GCM. Everything here
 * is in the platform's JCA, so the link adds no crypto dependency. Change one side, change both.
 *
 * The salt is what makes the relay harmless. It forwards both public keys, so without a secret it
 * could answer each side with a key of its own and sit in the middle; the secret only ever crossed
 * from the browser's screen to this phone's camera, so a relay that swaps keys derives nothing.
 */
internal object WebCrypto {
    private const val INFO_PREFIX = "messages-web v1 "
    private const val B64 = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
    private val random = SecureRandom()

    /** Directional keys: what this phone encrypts with, and what it decrypts with. */
    class SessionKeys(val send: ByteArray, val receive: ByteArray)

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, B64)
    fun unb64(value: String): ByteArray = Base64.decode(value, B64)

    fun generateKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }.generateKeyPair()

    /** The 65-byte uncompressed point WebCrypto's `exportKey("raw")` produces and accepts. */
    fun rawPublicKey(keyPair: KeyPair): ByteArray {
        val point = (keyPair.public as ECPublicKey).w
        return byteArrayOf(4) + fixed32(point.affineX) + fixed32(point.affineY)
    }

    fun deriveSessionKeys(own: KeyPair, peerRaw: ByteArray, secret: ByteArray, room: String): SessionKeys {
        require(peerRaw.size == 65 && peerRaw[0] == 4.toByte()) { "Invalid browser key" }
        val point = ECPoint(BigInteger(1, peerRaw.copyOfRange(1, 33)), BigInteger(1, peerRaw.copyOfRange(33, 65)))
        val peer = KeyFactory.getInstance("EC")
            .generatePublic(ECPublicKeySpec(point, (own.public as ECPublicKey).params))
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(own.private)
            doPhase(peer, true)
            generateSecret()
        }
        val bits = hkdf(shared, secret, (INFO_PREFIX + room).toByteArray(), 64)
        // The first half is browser→phone, the second phone→browser — the same split the web side
        // makes, read from the other end.
        return SessionKeys(send = bits.copyOfRange(32, 64), receive = bits.copyOfRange(0, 32))
    }

    /** base64url(iv || ciphertext || tag). */
    fun seal(key: ByteArray, plaintext: ByteArray): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return b64(iv + cipher.doFinal(plaintext))
    }

    /** Returns the IV alongside the plaintext, because the IV is what replay protection remembers. */
    fun open(key: ByteArray, data: String): Pair<String, ByteArray> {
        val bytes = unb64(data)
        require(bytes.size > 12 + 16) { "Frame too short" }
        val iv = bytes.copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return b64(iv) to cipher.doFinal(bytes, 12, bytes.size - 12)
    }

    /** RFC 5869, which is what WebCrypto's `HKDF` implements. */
    private fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    /** A coordinate as exactly 32 bytes: `BigInteger` drops leading zeros and may add a sign byte. */
    private fun fixed32(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }
}

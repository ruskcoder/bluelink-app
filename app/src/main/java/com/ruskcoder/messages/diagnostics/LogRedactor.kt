package com.ruskcoder.messages.diagnostics

/**
 * Strips identifying information out of a log before it leaves the device.
 *
 * A log that is useful for debugging a sign-in is, by construction, a log full of things that
 * identify the person signing in: the Apple ID went into the request, Apple answered with the
 * account's phone numbers, and `rustpush` logs whole plists at debug level. Handing that file to
 * someone is the point of exporting it, so the scrubbing has to happen before it is written, not as
 * advice afterwards.
 *
 * Two kinds of rule do the work, and they are not interchangeable:
 *
 *  - **Exact values** ([Secrets]) — the password especially. No pattern can recognise an arbitrary
 *    password, so the only way to be sure it is gone is to know what it is and delete every copy.
 *  - **Patterns** — e-mail addresses, phone numbers, credential fields, addresses. These catch
 *    everyone *else's* details, which this device never gets told about in advance.
 *
 * What it cannot do is recognise free text. A message body, a contact's name, or a nickname is
 * indistinguishable from any other string, so those survive. That is a real limit and the export
 * says so in the file rather than implying a guarantee it cannot keep.
 *
 * Deliberately free of Android imports so the rules can be tested on the JVM, which is the only way
 * to have any confidence in a regex that is supposed to stop a password reaching a stranger.
 */
object LogRedactor {

    /**
     * Values only this install knows, which no pattern could pick out on its own.
     *
     * Both are optional: a "continue as ..." sign-in never types a password, and a failure before
     * the Apple ID step has neither.
     */
    data class Secrets(
        val appleId: String? = null,
        val password: String? = null,
    )

    /** Redacted text, plus what was taken out of it — the tally is reported back to the user. */
    data class Result(
        val text: String,
        val replacements: Map<String, Int>,
    )

    /**
     * Replace everything identifying in [text].
     *
     * @throws IllegalStateException if a value from [secrets] somehow survives. That should be
     * impossible — the literal passes run first and run to completion — which is exactly why it is
     * worth asserting: the failure mode of a redaction bug is a password in a file the user is
     * about to send to someone, and refusing to write anything is the only safe way to be wrong.
     */
    fun redact(text: String, secrets: Secrets): Result {
        var out = text
        val counts = LinkedHashMap<String, Int>()

        fun apply(label: String, pattern: Regex, replacement: (MatchResult) -> String) {
            var hits = 0
            out = pattern.replace(out) { match ->
                hits++
                replacement(match)
            }
            if (hits > 0) counts[label] = (counts[label] ?: 0) + hits
        }

        fun applyLiteral(label: String, value: String) {
            apply(label, Regex(Regex.escape(value), RegexOption.IGNORE_CASE)) { label }
        }

        // Exact secrets go first. Once they are gone no later rule can rewrite them into something
        // that survives, and the patterns below get a shot at whatever is left over.
        secrets.password?.takeIf { it.isNotBlank() }?.let { password ->
            // Length is deliberately not checked. A short password would make a mess of the log by
            // matching ordinary words, and that is the correct trade: a mangled log is recoverable,
            // a leaked one is not.
            for (form in encodings(password)) applyLiteral(PASSWORD, form)
        }
        secrets.appleId?.takeIf { it.isNotBlank() }?.let { appleId ->
            for (form in encodings(appleId)) applyLiteral(USERNAME, form)
            // The local part turns up on its own in places that shorten the address. Only when it
            // is long enough to be a name rather than a word every log line contains.
            appleId.substringBefore('@').takeIf { it.length >= MIN_LOCAL_PART && it != appleId }
                ?.let { applyLiteral(USERNAME, it) }
        }

        // Credential-shaped fields, in the four spellings this app's logs actually use: HTTP
        // headers from Apple, JSON from the anisette server, plists from `rustpush`, and the
        // key=value pairs everything else falls back to.
        apply(REDACTED, HEADER_SECRET) {
            // Group 2 is the opening quote when the value was logged inside a map or a JSON body,
            // and empty when it was a real header. Re-emitting it keeps the line parseable.
            "${it.groupValues[1]}${it.groupValues[2]}$REDACTED${it.groupValues[2]}"
        }
        apply(REDACTED, JSON_SECRET) { "${it.groupValues[1]}\"$REDACTED\"" }
        apply(REDACTED, PLIST_SECRET) { "${it.groupValues[1]}$REDACTED${it.groupValues[3]}" }
        apply(REDACTED, KV_SECRET) { "${it.groupValues[1]}$REDACTED" }

        // Everyone else's details: the account's own contacts, the numbers Apple offers to text.
        apply(EMAIL, EMAIL_PATTERN) { EMAIL }
        apply(PHONE, PHONE_TEL_URI) { PHONE }
        apply(PHONE, PHONE_INTERNATIONAL) { PHONE }
        apply(PHONE, PHONE_GROUPED) { PHONE }
        apply(IP, IP_PATTERN) { IP }

        for (value in listOfNotNull(secrets.password, secrets.appleId)) {
            check(value.isBlank() || !out.contains(value, ignoreCase = true)) {
                "redaction left a secret in the log"
            }
        }

        return Result(out, counts)
    }

    /**
     * The spellings of [value] a log could hold it in.
     *
     * Plain text is the obvious one. Base64 matters because a plist stores a `Data` field that way,
     * and the sign-in password lived in `gsa.plist` as one before it was moved behind the keystore —
     * so an install old enough to have logged that file has the password in it, base64'd. Percent
     * encoding matters because the Apple ID goes into a form-encoded body.
     */
    private fun encodings(value: String): List<String> =
        listOf(value, base64(value.toByteArray(Charsets.UTF_8)), percentEncode(value)).distinct()

    /**
     * Base64, hand-rolled.
     *
     * `java.util.Base64` needs API 26 and this app supports 24; `android.util.Base64` would drag
     * the framework into a unit test that has no framework. Twelve lines is cheaper than either.
     */
    private fun base64(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val remaining = bytes.size - index
            val chunk = (bytes[index].toInt() and 0xFF shl 16) or
                (if (remaining > 1) bytes[index + 1].toInt() and 0xFF shl 8 else 0) or
                (if (remaining > 2) bytes[index + 2].toInt() and 0xFF else 0)
            out.append(alphabet[chunk ushr 18 and 0x3F])
            out.append(alphabet[chunk ushr 12 and 0x3F])
            out.append(if (remaining > 1) alphabet[chunk ushr 6 and 0x3F] else '=')
            out.append(if (remaining > 2) alphabet[chunk and 0x3F] else '=')
            index += 3
        }
        return out.toString()
    }

    /** Percent encoding, matching what a form-encoded request body would carry. */
    private fun percentEncode(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            if (char.isLetterOrDigit() && char.code < 0x80 || char in "-_.~") append(char)
            else append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }

    private const val PASSWORD = "<password>"
    private const val USERNAME = "<username>"
    private const val REDACTED = "<redacted>"
    private const val EMAIL = "<email>"
    private const val PHONE = "<phone>"
    private const val IP = "<ip>"

    /** Below this a local part is a word, not a name, and blanking it would just shred the log. */
    private const val MIN_LOCAL_PART = 4

    /**
     * Fields whose *value* is a credential or a device identity, wherever they appear.
     *
     * Kept narrow on purpose. Widening this to `key` or `id` would blank out most of a debug log,
     * including the anisette request id — the one value the server operator needs to find their
     * side of the failure.
     */
    private const val SENSITIVE =
        "password|passwd|pwd|pet|alt[_-]?dsid|adsid|dsid|cookie|session[_-]?token|auth[_-]?token|" +
            "token|secret|adi_pb|ptm|tk|spim|cpim|machine[_-]?id|client_secret|keychain_identifier|" +
            "serial[_-]?number|imei|meid|udid|unique[_-]?device[_-]?id|" +
            "signing_key|encryption_key|private_key|escrowed_signing_key"

    /**
     * Headers Apple's endpoints carry identity in.
     *
     * `X-Apple-I-MD-RINFO` is left alone: it is a routing number shared by every device of a kind,
     * it identifies nobody, and it is worth reading when provisioning goes wrong.
     */
    private const val SENSITIVE_HEADERS =
        "X-Apple-I-MD-M|X-Apple-I-MD-LU|X-Apple-I-MD|X-Apple-ADSID|X-Apple-Session-Token|" +
            "X-Apple-GS-Token|X-Mme-Device-Id|Authorization|Set-Cookie|Cookie"

    private val HEADER_SECRET =
        Regex("""\b((?:$SENSITIVE_HEADERS)"?\s*[:=]\s*)("?)[^\s,;"']+\2""", RegexOption.IGNORE_CASE)

    private val JSON_SECRET =
        Regex("""("(?:$SENSITIVE)"\s*:\s*)"[^"]*"""", RegexOption.IGNORE_CASE)

    /** `rustpush` logs whole plists, and a plist puts the value in the element after the key. */
    private val PLIST_SECRET = Regex(
        """(<key>(?:$SENSITIVE)</key>\s*<(string|data|integer|real)>)[^<]*(</\2>)""",
        RegexOption.IGNORE_CASE,
    )

    private val KV_SECRET =
        Regex("""\b((?:$SENSITIVE)\s*[:=]\s*)[^\s,;&)\]}"']+""", RegexOption.IGNORE_CASE)

    private val EMAIL_PATTERN =
        Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")

    /** `tel:5551234567` — the bare form, which the international pattern below would miss. */
    private val PHONE_TEL_URI = Regex("""(?<=tel:)\+?\d[\d\-(). ]{5,18}\d""")

    private val PHONE_INTERNATIONAL = Regex("""\+\d{7,15}\b""")

    /**
     * Grouped domestic numbers: `555-123-4567`, `555.123.4567`, `(555) 123-4567`.
     *
     * Whitespace is deliberately *not* a separator. `\d{3} \d{3} \d{4}` also describes three
     * ordinary numbers in a row — a size, a count, an offset — and blanking those would quietly gut
     * the log while looking like it had worked. Anchoring on the grouping also keeps it off logcat
     * timestamps and pid columns.
     */
    private val PHONE_GROUPED =
        Regex("""(\(\d{3}\)\s?|\b\d{3}[.\-])\d{3}[.\-]?\d{4}\b""")

    private val IP_PATTERN = Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""")
}

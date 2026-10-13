package com.ruskcoder.messages.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What must never survive an export.
 *
 * These are worth more than most tests here: the failure they guard against is a password reaching
 * whoever the user sends the file to, and that is not something a bug report gets to tell you about
 * afterwards. Every case is a shape these logs are actually written in — an `X-Apple-*` header from
 * Apple, a plist from `rustpush`, JSON from the anisette server.
 */
class LogRedactorTest {

    private val secrets = LogRedactor.Secrets(
        appleId = "jamie.appleseed@example.com",
        password = "hunter2-correct-horse",
    )

    private fun redact(text: String) = LogRedactor.redact(text, secrets).text

    @Test
    fun `the password never survives, in any casing`() {
        val log = """
            POST /grandslam/GsService2 password=hunter2-correct-horse&u=x
            retrying with HUNTER2-CORRECT-HORSE
        """.trimIndent()

        val out = redact(log)
        assertFalse(out.contains("hunter2", ignoreCase = true))
        assertTrue(out.contains("<password>"))
    }

    @Test
    fun `the password does not survive base64, the way a plist would hold it`() {
        // What `gsa.plist` looked like before the password moved behind the keystore.
        val encoded = "aHVudGVyMi1jb3JyZWN0LWhvcnNl"
        val out = redact("<key>password</key><data>$encoded</data>")

        assertFalse(out.contains(encoded))
    }

    @Test
    fun `the password does not survive percent encoding in a form body`() {
        val out = LogRedactor.redact(
            "grant_type=password&pass=a%20b%26c",
            LogRedactor.Secrets(password = "a b&c"),
        ).text

        assertFalse(out.contains("a%20b%26c"))
    }

    @Test
    fun `the apple id becomes username rather than a generic email`() {
        val out = redact("signing in as jamie.appleseed@example.com")

        assertEquals("signing in as <username>", out)
    }

    @Test
    fun `other people's addresses and numbers are replaced too`() {
        val log = """
            validated targets: mailto:someone.else@icloud.com, tel:+15551234567
            fallback number (555) 987-6543 and 555-111-2222
        """.trimIndent()

        val out = redact(log)
        assertFalse(out.contains("someone.else@icloud.com"))
        assertFalse(out.contains("5551234567"))
        assertFalse(out.contains("987-6543"))
        assertFalse(out.contains("555-111-2222"))
        assertTrue(out.contains("mailto:<email>"))
        assertTrue(out.contains("tel:<phone>"))
    }

    @Test
    fun `credential headers and plist fields lose their values`() {
        val log = """
            X-Apple-I-MD-M: AAAAB3NzaC1yc2EAAAADAQAB
            X-Mme-Device-Id: 1E2D3C4B-5A69-4788-9900-AABBCCDDEEFF
            <key>ptm</key><string>c2VjcmV0LXB0bQ==</string>
            {"result":"ProvisioningSuccess","adi_pb":"c2VjcmV0LWFkaQ=="}
            altDSID=001234.abcdef0123456789
        """.trimIndent()

        val out = redact(log)
        assertFalse(out.contains("AAAAB3NzaC1yc2EAAAADAQAB"))
        assertFalse(out.contains("1E2D3C4B-5A69-4788-9900-AABBCCDDEEFF"))
        assertFalse(out.contains("c2VjcmV0LXB0bQ=="))
        assertFalse(out.contains("c2VjcmV0LWFkaQ=="))
        assertFalse(out.contains("001234.abcdef0123456789"))
    }

    @Test
    fun `the anisette request id is kept, because it is the point of the export`() {
        // The whole reason someone exports these logs is so the failure can be traced on the far
        // side. Over-redacting into `<redacted>` here would make the file worth nothing.
        val log = "Anisette provisioning failed at EndProvisioning: " +
            "ADI returned -45061 (request id: 7c9a1f30-4b2e-4a11-8f0d-2c3b4a5d6e7f)"

        val out = redact(log)
        assertTrue(out.contains("-45061"))
        assertTrue(out.contains("7c9a1f30-4b2e-4a11-8f0d-2c3b4a5d6e7f"))
    }

    @Test
    fun `ordinary log lines are left readable`() {
        val log = """
            08-30 14:32:10.123  4321  4321 D IMessageSetup: sign in failed
            uploaded 320 480 1024 bytes in 250 ms
        """.trimIndent()

        // Timestamps, pids and runs of plain numbers must not read as phone numbers, or the log
        // arrives unreadable and the export is pointless.
        assertEquals(log, redact(log))
    }

    @Test
    fun `a run with no secrets configured still scrubs patterns`() {
        val out = LogRedactor.redact("wrote to someone@example.com", LogRedactor.Secrets()).text

        assertEquals("wrote to <email>", out)
    }

    @Test
    fun `the tally counts what was taken out`() {
        val result = LogRedactor.redact(
            "a@b.com and c@d.com and jamie.appleseed@example.com",
            secrets,
        )

        assertEquals(2, result.replacements["<email>"])
        assertEquals(1, result.replacements["<username>"])
    }
}

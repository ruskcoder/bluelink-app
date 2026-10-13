package com.ruskcoder.messages.imessage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Handle canonicalization, which decides which conversation a message belongs to.
 *
 * The bug these cover: a send addressed whatever the address book held — `(555) 123-4567`, with no
 * country code — while the reply came back from Apple as `+15551234567`. Compared as strings those
 * are two people, so replying to a message split the thread in two.
 *
 * `PhoneNumberUtils` is a framework class with no implementation on the JVM, so these exercise the
 * region-less fallback path. That is the path that matters here: it is what has to collapse the two
 * spellings when the device cannot say which country a bare number belongs to.
 */
class HandlesTest {

    @Test
    fun `same number written differently collapses to one key`() {
        val canonical = Handles.key("tel:+15551234567")
        assertEquals(canonical, Handles.key("tel:5551234567"))
        assertEquals(canonical, Handles.key("tel:+1 (555) 123-4567"))
        assertEquals(canonical, Handles.key("+1 555-123-4567"))
        assertEquals(canonical, Handles.key("  tel:+1 555 123 4567  "))
    }

    @Test
    fun `different numbers stay apart`() {
        assertNotEquals(Handles.key("tel:+15551234567"), Handles.key("tel:+15559999999"))
    }

    @Test
    fun `short numbers are not padded into collisions`() {
        // Shortcodes are shorter than the digits we key on, so they must compare whole rather than
        // being silently treated as a suffix of some longer number.
        assertEquals("tel:611", Handles.key("tel:611"))
        assertNotEquals(Handles.key("tel:611"), Handles.key("tel:5551234611"))
    }

    @Test
    fun `email handles are case-insensitive and keep their scheme`() {
        val canonical = Handles.key("mailto:someone@example.com")
        assertEquals(canonical, Handles.key("mailto:SomeOne@Example.COM"))
        assertEquals(canonical, Handles.key("someone@example.com"))
        assertEquals("mailto:someone@example.com", canonical)
    }

    @Test
    fun `an email is never confused with a number`() {
        assertNotEquals(Handles.key("mailto:5551234567@example.com"), Handles.key("tel:+15551234567"))
    }

    @Test
    fun `one-to-one chat id ignores which of our handles Apple listed`() {
        val mine = setOf(Handles.key("tel:+15550000000"), Handles.key("mailto:me@icloud.com"))
        val them = Handles.key("tel:+15551234567")

        // Outgoing: we addressed them from the address book, and Apple echoed our spelling back.
        val outgoing = Handles.chatId(
            participants = listOf("tel:5551234567", "mailto:me@icloud.com"),
            sender = "mailto:me@icloud.com",
            groupId = null,
            localKeys = mine,
        )
        // Incoming: Apple's own spelling, and it named our *other* handle as the recipient.
        val incoming = Handles.chatId(
            participants = listOf("tel:+15551234567", "tel:+15550000000"),
            sender = "tel:+15550000000",
            groupId = null,
            localKeys = mine,
        )

        assertEquals(them, outgoing)
        assertEquals(outgoing, incoming)
    }

    @Test
    fun `a conversation listing two of our handles is still not a group`() {
        val mine = setOf(Handles.key("tel:+15550000000"), Handles.key("mailto:me@icloud.com"))
        val id = Handles.chatId(
            participants = listOf("tel:+15551234567", "tel:+15550000000", "mailto:me@icloud.com"),
            sender = "tel:+15550000000",
            groupId = null,
            localKeys = mine,
        )
        // Only one other person is in it, however many of our own addresses Apple decided to list.
        assertEquals(Handles.key("tel:+15551234567"), id)
    }

    @Test
    fun `a one-to-one thread ignores Apple's per-thread identifier`() {
        // Apple stamps a conversation UUID on one-to-one threads too, and a reply can arrive under
        // a fresh one. Keying on it filed each reply as a new conversation with the same person.
        val mine = setOf(Handles.key("mailto:me@icloud.com"))
        val them = Handles.key("tel:+15551234567")

        // What we wrote when the chat was started from the address book: no identifier yet.
        val started = Handles.chatId(
            participants = listOf("tel:+15551234567", "mailto:me@icloud.com"),
            sender = "mailto:me@icloud.com",
            groupId = null,
            localKeys = mine,
        )
        // What came back on their reply, and again on a later one under a different UUID.
        val reply = Handles.chatId(
            participants = listOf("tel:+15551234567", "mailto:me@icloud.com"),
            sender = "mailto:me@icloud.com",
            groupId = "862707B0-92FB-4574-A904-DD55E23E4610",
            localKeys = mine,
        )
        val laterReply = Handles.chatId(
            participants = listOf("tel:+15551234567", "mailto:me@icloud.com"),
            sender = "mailto:me@icloud.com",
            groupId = "40D84DEC-FB5A-4650-92AB-4BEFF7562E88",
            localKeys = mine,
        )

        assertEquals(them, started)
        assertEquals(started, reply)
        assertEquals(reply, laterReply)
    }

    @Test
    fun `a conversation we cannot enumerate still falls back to the identifier`() {
        // Nothing else distinguishes it, so the identifier is all there is to key on.
        val id = Handles.chatId(
            participants = emptyList(),
            sender = "mailto:me@icloud.com",
            groupId = "F0B3B1E2-0000-4000-8000-000000000000",
            localKeys = setOf(Handles.key("mailto:me@icloud.com")),
        )
        assertEquals("F0B3B1E2-0000-4000-8000-000000000000", id)
    }

    @Test
    fun `a group keeps Apple's identifier regardless of membership`() {
        val id = Handles.chatId(
            participants = listOf("tel:+15551234567", "tel:+15557654321"),
            sender = "mailto:me@icloud.com",
            groupId = "F0B3B1E2-0000-4000-8000-000000000000",
            localKeys = emptySet(),
        )
        assertEquals("F0B3B1E2-0000-4000-8000-000000000000", id)
    }

    @Test
    fun `group members are order-independent`() {
        val a = Handles.chatId(
            participants = listOf("tel:+15551234567", "tel:+15557654321", "mailto:me@icloud.com"),
            sender = "mailto:me@icloud.com",
            groupId = null,
            localKeys = emptySet(),
        )
        val b = Handles.chatId(
            participants = listOf("tel:+15557654321", "mailto:me@icloud.com", "tel:+15551234567"),
            sender = "mailto:me@icloud.com",
            groupId = null,
            localKeys = emptySet(),
        )
        assertEquals(a, b)
    }
}

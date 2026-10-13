package com.ruskcoder.messages.ui.components

import com.ruskcoder.messages.data.model.Attachment
import com.ruskcoder.messages.data.model.Message
import com.ruskcoder.messages.data.model.ReplyPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the conversation lays itself out by. Every one of these was once a visible bug — a tail
 * on the wrong bubble, a date rule in the wrong place — so they are pinned down here rather than
 * rediscovered on a phone.
 */
class ThreadModelTest {

    private val base = 1_700_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun text(id: String, mine: Boolean, at: Long, replyTo: String? = null) = Message.Text(
        id = id,
        text = id,
        mine = mine,
        time = "",
        createdAt = at,
        replyTo = replyTo?.let { ReplyPreview(it, "", "") },
    )

    private fun photo(id: String, at: Long) = Message.AttachmentMessage(
        id = id,
        mine = true,
        time = "",
        createdAt = at,
        attachment = Attachment.Image(uri = "file://$id", width = 10, height = 10),
    )

    private fun build(messages: List<Message>, firstUnread: String? = null) =
        buildThreadModel(ThreadInputs(messages, emptyList(), isGroup = false, firstUnreadId = firstUnread))

    private fun rows(model: ThreadModel): Map<String, ThreadRow> =
        model.items.filterIsInstance<ThreadItem.Bubble>().associate { it.row.message.id to it.row }

    @Test
    fun `consecutive messages from one side form one run`() {
        val rows = rows(build(listOf(text("a", true, base), text("b", true, base + 1_000), text("c", false, base + 2_000))))
        assertTrue(rows.getValue("a").isFirstInRun)
        assertFalse(rows.getValue("a").isLastInRun)
        assertFalse(rows.getValue("b").isFirstInRun)
        assertTrue(rows.getValue("b").isLastInRun)
        assertTrue(rows.getValue("c").isFirstInRun && rows.getValue("c").isLastInRun)
    }

    @Test
    fun `items are newest first with the date above the oldest message`() {
        val model = build(listOf(text("a", true, base), text("b", false, base + 1_000)))
        assertEquals(listOf("b", "a", "section-a"), model.items.map { it.key })
    }

    @Test
    fun `a long silence starts a new section and ends the run before it`() {
        val model = build(listOf(text("a", true, base), text("b", true, base + 2 * hour)))
        assertEquals(listOf("b", "section-b", "a", "section-a"), model.items.map { it.key })
        val rows = rows(model)
        assertTrue(rows.getValue("a").isLastInRun)
        assertTrue(rows.getValue("b").isFirstInRun)
    }

    @Test
    fun `the unread rule sits directly above the first unread message`() {
        val model = build(listOf(text("a", false, base), text("b", false, base + 1_000)), firstUnread = "b")
        assertEquals(listOf("b", "unread-break", "a", "section-a"), model.items.map { it.key })
    }

    @Test
    fun `the receipt goes on the newest message that can carry one`() {
        val model = build(
            listOf(
                text("a", true, base),
                Message.Deleted(id = "d", mine = true, time = "", createdAt = base + 1_000),
            ),
        )
        assertEquals("a", model.lastMineId)
    }

    @Test
    fun `photos sent back to back fold into one group keyed by the newest`() {
        val model = build(listOf(photo("p1", base), photo("p2", base + 1_000), photo("p3", base + 2_000)))
        assertEquals(1, model.messages.size)
        val folded = model.messages.single() as Message.AttachmentMessage
        assertEquals("p3", folded.id)
        assertEquals(listOf("p1", "p2", "p3"), (folded.attachment as Attachment.MediaGroup).messageIds)
    }

    @Test
    fun `photos a long way apart are not grouped`() {
        val model = build(listOf(photo("p1", base), photo("p2", base + 5 * 60_000)))
        assertEquals(listOf("p1", "p2"), model.messages.map { it.id })
    }

    @Test
    fun `a reply directly under what it answers draws no reply count`() {
        val rows = rows(build(listOf(text("q", false, base), text("r", true, base + 1_000, replyTo = "q"))))
        assertTrue(rows.getValue("r").quoteAdjacent)
        assertEquals(0, rows.getValue("q").replyCount)
    }

    @Test
    fun `a reply out of sight counts the whole thread on the message it answers`() {
        val rows = rows(
            build(
                listOf(
                    text("q", false, base),
                    text("x", false, base + 1_000),
                    text("r", true, base + 2_000, replyTo = "q"),
                ),
            ),
        )
        assertFalse(rows.getValue("r").quoteAdjacent)
        assertEquals(2, rows.getValue("q").replyCount)
    }

    @Test
    fun `inputs are compared by the identity of the message list`() {
        val list = listOf(text("a", true, base))
        assertEquals(ThreadInputs(list, emptyList(), false, null), ThreadInputs(list, emptyList(), false, null))
        assertNotEquals(ThreadInputs(list, emptyList(), false, null), ThreadInputs(list.toList(), emptyList(), false, null))
    }
}

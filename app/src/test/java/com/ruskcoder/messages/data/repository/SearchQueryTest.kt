package com.ruskcoder.messages.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchQueryTest {

    @Test
    fun `each word becomes a quoted prefix`() {
        assertEquals("\"din\"* \"tom\"*", SearchQuery.ftsMatch("din tom"))
    }

    @Test
    fun `query syntax typed into the box is stripped rather than obeyed`() {
        assertEquals("\"hello\"* \"world\"*", SearchQuery.ftsMatch("\"hello\" -world*"))
        // Quoted, so an OR someone typed is a word to find rather than an operator.
        assertEquals("\"cats\"* \"OR\"* \"dogs\"*", SearchQuery.ftsMatch("cats OR dogs"))
    }

    @Test
    fun `nothing searchable gives null`() {
        assertNull(SearchQuery.ftsMatch("   "))
        assertNull(SearchQuery.ftsMatch("*** -- ()"))
    }

    @Test
    fun `letters outside ASCII survive`() {
        assertEquals("\"élan\"*", SearchQuery.ftsMatch("élan"))
    }

    @Test
    fun `a pathological paste is capped`() {
        val words = (1..40).joinToString(" ") { "w$it" }
        assertEquals(12, SearchQuery.ftsMatch(words)!!.split(" ").size)
    }
}

package com.ruskcoder.messages.util

import com.ruskcoder.messages.data.model.LinkPreview
import com.ruskcoder.messages.data.model.firstLinkIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkMetaParserTest {

    private val page = "https://www.example.com/articles/one"

    @Test
    fun `open graph tags win over the title tag`() {
        val html = """
            <html><head>
              <title>Fallback</title>
              <meta property="og:title" content="Real &amp; Title">
              <meta property="og:image" content="/img/card.png">
              <meta property="og:site_name" content="Example">
              <meta property="og:description" content="What it is about">
            </head><body><meta property="og:title" content="Not this one"></body></html>
        """.trimIndent()
        val meta = LinkMetaParser.parse(html, page)!!
        assertEquals("Real & Title", meta.title)
        assertEquals("https://www.example.com/img/card.png", meta.imageUrl)
        assertEquals("Example", meta.siteName)
        assertEquals("What it is about", meta.description)
    }

    @Test
    fun `falls back to the title tag and the host`() {
        val meta = LinkMetaParser.parse("<head><title>\n  Hello\n   there </title></head>", page)!!
        assertEquals("Hello there", meta.title)
        assertEquals("example.com", meta.siteName)
        assertNull(meta.imageUrl)
    }

    @Test
    fun `attribute order and quoting do not matter`() {
        val html = "<head><meta content='Short' name='twitter:title'><meta content=Desc name=description></head>"
        val meta = LinkMetaParser.parse(html, page)!!
        assertEquals("Short", meta.title)
        assertEquals("Desc", meta.description)
    }

    @Test
    fun `a page with nothing to show gives null`() {
        assertNull(LinkMetaParser.parse("<html><body>hello</body></html>", page))
    }

    @Test
    fun `an image that is not a web address is dropped`() {
        val html = """<head><meta property="og:title" content="T"><meta property="og:image" content="javascript:alert(1)"></head>"""
        assertNull(LinkMetaParser.parse(html, page)!!.imageUrl)
    }

    @Test
    fun `numeric and named entities decode`() {
        assertEquals("It’s “fine”", LinkMetaParser.decodeEntities("It&#8217;s &ldquo;fine&#x201D;"))
        assertEquals("&unknown;", LinkMetaParser.decodeEntities("&unknown;"))
    }

    @Test
    fun `the first link in a message is found without trailing punctuation`() {
        assertEquals("https://example.com/x", firstLinkIn("have you seen https://example.com/x."))
        assertEquals("http://a.b/c?d=e", firstLinkIn("(http://a.b/c?d=e)"))
        assertNull(firstLinkIn("no links in here"))
    }

    @Test
    fun `a card with neither title nor picture is not worth drawing`() {
        assertFalse(LinkPreview(url = "https://example.com").isUseful)
        assertTrue(LinkPreview(url = "https://example.com", title = "Example").isUseful)
    }
}

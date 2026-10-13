package com.ruskcoder.messages.imessage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an attachment off the wire is taken to be.
 *
 * The bug these cover: a photo from an iPhone that arrived with no usable `mime-type` landed in the
 * conversation as a document called `____.png` — a file bubble, which is also the one shape a
 * notification cannot show a picture in. Three labels now get a say instead of one, and only an
 * attachment that none of them identifies is left for the bytes to answer for.
 */
class AttachmentKindsTest {

    @Test
    fun `a labelled photo is a photo`() {
        assertEquals(MediaKind.IMAGE, mediaKindOf("image/png", "public.png", "IMG_0001.png"))
        assertEquals(MediaKind.IMAGE, mediaKindOf("image/heic", "public.heic", "IMG_0002.HEIC"))
    }

    @Test
    fun `a photo with no mime type is still a photo`() {
        // What the parser fills in when the sender left the attribute off entirely.
        assertEquals(MediaKind.IMAGE, mediaKindOf("application/octet-stream", "public.jpeg", "IMG_0003.jpeg"))
        assertEquals(MediaKind.IMAGE, mediaKindOf("", "", "IMG_0004.png"))
    }

    @Test
    fun `an unfamiliar apple type that names itself is believed`() {
        assertEquals(MediaKind.IMAGE, mediaKindOf("application/octet-stream", "public.some-new-image", "x"))
        assertEquals(MediaKind.VIDEO, mediaKindOf("application/octet-stream", "com.apple.quicktime-movie", "x"))
    }

    @Test
    fun `clips are clips`() {
        assertEquals(MediaKind.VIDEO, mediaKindOf("video/quicktime", "com.apple.quicktime-movie", "IMG_0005.MOV"))
        assertEquals(MediaKind.VIDEO, mediaKindOf("", "", "clip.mp4"))
    }

    @Test
    fun `a genmoji is a picture whatever else it says`() {
        assertEquals(MediaKind.IMAGE, mediaKindOf("application/octet-stream", "public.data", "glyph", genmoji = ""))
        assertEquals(
            MediaKind.IMAGE,
            mediaKindOf("application/octet-stream", "public.data", "x", genmoji = "a cat wearing sunglasses"),
        )
    }

    @Test
    fun `a document stays a document`() {
        assertEquals(MediaKind.FILE, mediaKindOf("application/pdf", "com.adobe.pdf", "lease.pdf"))
        assertEquals(MediaKind.FILE, mediaKindOf("text/vcard", "public.vcard", "Contact.vcf"))
        // A voice message: recognised elsewhere, and certainly not media to lay out as a tile.
        assertEquals(MediaKind.FILE, mediaKindOf("audio/x-caf", "com.apple.coreaudio-format", "Audio Message.caf"))
    }

    @Test
    fun `bytes nothing describes are left open`() {
        // Not FILE: claiming "document" here is a guess, and the download can simply look instead.
        assertEquals(MediaKind.UNKNOWN, mediaKindOf("application/octet-stream", "public.data", "attachment"))
        assertEquals(MediaKind.UNKNOWN, mediaKindOf("", "", "attachment"))
    }
}

package com.ruskcoder.messages.util

import java.util.UUID

/**
 * The GUID a stored outgoing message is sent under — the same one every time it is sent.
 *
 * Derived from the row rather than stored beside it, so a retry cannot come up with a different answer
 * and a row that failed before it was named on disk can still be recognised by the receipts Apple
 * sends for it. A send that times out has very often reached the other end anyway; sent again under a
 * new GUID it arrived there twice, and under this one the receiving device recognises a message it
 * already has.
 */
fun wireGuidFor(rowId: String): String =
    UUID.nameUUIDFromBytes("outgoing:$rowId".toByteArray()).toString().uppercase()

package com.ruskcoder.messages.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ReplyPreview(
    val id: String,
    val name: String,
    val text: String,
    /** Whether the quoted message was one of yours; decides which side iMessage hangs it on. */
    val mine: Boolean? = null,
)

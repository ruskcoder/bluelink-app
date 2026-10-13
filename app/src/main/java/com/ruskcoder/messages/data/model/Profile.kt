package com.ruskcoder.messages.data.model

/** What "you" is called before a name has been set in settings. */
const val USER_NAME = "Me"

val USER_INITIALS: String = initialsFor(USER_NAME)

/** The name to show for yourself: whatever settings hold, or the default. */
fun Settings.displayName(): String = profileName.ifBlank { USER_NAME }

fun Settings.displayInitials(): String = initialsFor(displayName())

package com.ruskcoder.messages.data.model

import kotlinx.serialization.Serializable

/** One person in a group conversation, as the group details screen lists them. */
@Serializable
data class GroupMember(
    val id: String,
    val name: String,
    val initials: String,
    val color: String,
    val phone: String? = null,
    /**
     * Address-book photo uri, when the handle matched a contact who has one.
     *
     * Persisted with the rest of the roster rather than resolved at render time: a group's members
     * are matched against the address book once, on the sync path, and re-running that match for
     * every bubble in a long conversation would put a contacts query on the scroll path.
     */
    val photoUri: String? = null,
)

/** A group's display name when it has no custom one: members' first names, WhatsApp-style. */
fun nameFromMembers(members: List<GroupMember>): String =
    members.joinToString(", ") { it.name.substringBefore(' ').filter { c -> c.isLetterOrDigit() || c == '\'' } }

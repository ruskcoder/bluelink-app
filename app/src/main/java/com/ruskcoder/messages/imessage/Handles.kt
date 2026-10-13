package com.ruskcoder.messages.imessage

import android.telephony.PhoneNumberUtils
import com.ruskcoder.messages.data.model.colorFor
import com.ruskcoder.messages.data.model.initialsFor
import uniffi.imessage_core.ChatTarget
import java.util.Locale

/**
 * Apple addresses people as URIs — `mailto:someone@example.com`, `tel:+15551234567` — and this app
 * addresses them as contacts. These are the conversions between the two, kept in one place because
 * getting them subtly wrong produces duplicate conversations rather than an error.
 */
object Handles {

    /** The human-readable half of a handle, for when no contact matches it. */
    fun display(handle: String): String = handle.substringAfter(':', handle)

    /**
     * The dialling region used to canonicalize bare phone numbers, set once at startup.
     *
     * Kept here rather than read from `TelephonyManager` on demand so this object stays free of
     * Android context and testable; [key] still behaves sensibly before it is set.
     */
    @Volatile
    private var region: String? = null

    fun configureRegion(iso: String?) {
        region = iso?.takeIf { it.isNotBlank() }?.uppercase(Locale.US)
            ?: Locale.getDefault().country.takeIf { it.isNotBlank() }
    }

    /**
     * The canonical form of a handle, for deciding whether two of them are the same person.
     *
     * Necessary because the two ends spell handles differently and never reconcile them. A send
     * addresses whatever the address book held — `(555) 123-4567` normalizes to `tel:5551234567`,
     * with no country code — and `validate_targets` hands that spelling straight back rather than
     * Apple's own. The reply then arrives as `tel:+15551234567`. Comparing those as strings mints a
     * second conversation for the same person, which is exactly the split this fixes.
     *
     * Only for identity and comparison. Sends still address [normalize]'s output, because that is
     * the spelling the other side was reachable at.
     */
    fun key(handle: String): String {
        val trimmed = handle.trim()
        val address = trimmed.substringAfter(':', trimmed)
        if (trimmed.startsWith("mailto:", ignoreCase = true) || address.contains('@')) {
            return "mailto:" + address.lowercase(Locale.US)
        }

        val digits = address.filter { it.isDigit() || it == '+' }
        // E.164 where the region makes it unambiguous. Without a region — no SIM, or a number the
        // platform cannot parse — fall back to the trailing digits, which is the same tolerance
        // `matchesNumber` already applies when naming a handle from the address book.
        val e164 = region?.let { runCatching { PhoneNumberUtils.formatNumberToE164(digits, it) }.getOrNull() }
        if (e164 != null) return "tel:$e164"

        val bare = digits.filter { it.isDigit() }
        return "tel:" + if (bare.length > NATIONAL_DIGITS) bare.takeLast(NATIONAL_DIGITS) else bare
    }

    /** Wrap a raw address in the scheme Apple expects. */
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("mailto:") || trimmed.startsWith("tel:")) return trimmed
        return if (trimmed.contains('@')) "mailto:$trimmed" else "tel:${trimmed.filter { it.isDigit() || it == '+' }}"
    }

    /**
     * Compare a handle against an address-book number.
     *
     * Matched on the last several digits rather than exactly: the address book stores whatever the
     * user typed, so `(555) 123-4567` has to match `tel:+15551234567`, and neither side can be
     * normalized into the other without knowing the dialing region.
     */
    fun matchesNumber(handle: String, number: String?): Boolean {
        if (number == null) return false
        if (handle.startsWith("mailto:")) {
            return handle.removePrefix("mailto:").equals(number.trim(), ignoreCase = true)
        }
        val a = handle.filter { it.isDigit() }
        val b = number.filter { it.isDigit() }
        if (a.length < SIGNIFICANT_DIGITS || b.length < SIGNIFICANT_DIGITS) return a == b
        return a.takeLast(SIGNIFICANT_DIGITS) == b.takeLast(SIGNIFICANT_DIGITS)
    }

    /**
     * A stable local id for an iMessage conversation.
     *
     * iMessage has no server-side chat id, so one has to be derived. A group has Apple's own group
     * identifier and uses it, which is what lets a group survive being renamed or losing a member.
     * A one-to-one conversation is identified by the other person's handle — sorted and joined for
     * the odd case of a group with no identifier, so the same set of people always lands on the
     * same id regardless of the order Apple listed them in.
     */
    fun chatId(target: ChatTarget, localKeys: Set<String> = emptySet()): String =
        chatId(target.participants, target.sender, target.groupId, localKeys)

    /**
     * The same derivation from stored columns rather than a live [ChatTarget], so a conversation
     * already on disk can be asked what its id *should* be. That is what lets duplicates created
     * before handles were canonicalized be recognised and folded together.
     */
    fun chatId(
        participants: List<String>,
        sender: String?,
        groupId: String?,
        localKeys: Set<String>,
    ): String {
        val mine = localKeys + setOfNotNull(sender?.takeIf { it.isNotBlank() }?.let { key(it) })
        val others = participants.map { key(it) }.filterNot { it in mine }.distinct()

        // Apple's identifier is what lets a group survive a rename or a change of membership, so a
        // group is keyed by it. But Apple puts one on a one-to-one conversation too, where it is
        // not an identity at all — it is a per-thread UUID, and a reply can arrive under a fresh
        // one. Keying on it there mints a second conversation for someone already on the list,
        // which is exactly the split this avoids: with a single other person, that person *is* the
        // conversation, and their handle is the only stable thing about it.
        //
        // "A single other person" rather than "not a group" on purpose — it is the same test
        // [isGroup] applies, so a group that has shrunk to one other member is keyed the way the
        // rest of the app already displays and treats it.
        //
        // A conversation whose participants we cannot enumerate has nothing else to be named by,
        // and still falls back to the identifier.
        if (others.size != 1) {
            groupId?.takeIf { it.isNotBlank() }?.let { return it }
        }

        return others.sorted().joinToString(";")
            .ifEmpty { sender?.takeIf { it.isNotBlank() }?.let { key(it) }.orEmpty() }
    }

    /**
     * Everyone in the conversation who is not us, canonicalized.
     *
     * Excludes *every* handle of ours rather than just `target.sender`. We can be registered at a
     * phone number and one or more addresses, and Apple lists whichever the other end used — so
     * removing a single handle can leave one of ours in the set, which both changes the derived id
     * and makes a two-person chat look like a group.
     */
    fun others(target: ChatTarget, localKeys: Set<String>): List<String> {
        val mine = localKeys + key(target.sender)
        return target.participants.map { key(it) }.filterNot { it in mine }.distinct()
    }

    /** Whether a stored conversation still carries an Apple identity we can re-derive an id from. */
    fun hasAppleIdentity(participants: List<String>?, groupId: String?): Boolean =
        !groupId.isNullOrBlank() || !participants.isNullOrEmpty()

    fun isGroup(target: ChatTarget, localKeys: Set<String> = emptySet()): Boolean =
        others(target, localKeys).size > 1

    /**
     * Seeded by the display name, not by the handle. A person appears under a handle here, a
     * contact row id in the address book, and a chat id in the list — hashing any of those gave the
     * same person a different colour depending on which surface drew them.
     */
    fun avatarColor(name: String): String = colorFor(name)

    fun avatarInitials(name: String): String = initialsFor(name)

    /** Enough digits to identify a number without demanding matching country codes. */
    private const val SIGNIFICANT_DIGITS = 7

    /**
     * Digits kept when a number cannot be put in E.164.
     *
     * Longer than [SIGNIFICANT_DIGITS] on purpose: that one only has to name a handle, where a
     * false match shows the wrong contact name, while this one decides which conversation a message
     * belongs to, where a false match files two people's messages in one thread.
     */
    private const val NATIONAL_DIGITS = 10
}

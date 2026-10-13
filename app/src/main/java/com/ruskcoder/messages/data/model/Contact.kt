package com.ruskcoder.messages.data.model

/** A row in the address book, normalized from the device's contacts. */
data class Contact(
    val id: String,
    val name: String,
    val about: String? = null,
    val color: String,
    val initials: String,
    /**
     * `content://` uri of the contact's photo, or null when they have none.
     *
     * Held as a uri rather than as decoded bytes because the address book is loaded in full on
     * several screens, and Coil resolves these lazily per visible row — decoding every photo up
     * front would cost far more than the list ever displays.
     */
    val photoUri: String? = null,
)

/**
 * Avatar fills, one per person — pale enough that every one of them takes dark lettering.
 *
 * The palette used to be saturated mid-tones carrying white letters, which at chat-list size read
 * as ten dark discs that were hard to tell apart. Pitched right up, each hue is distinct at a glance
 * and the monogram sits on it as ink on paper rather than as a highlight on a dark ground — which
 * is what lets the letter be set as large as it now is without dissolving into the fill.
 *
 * Being uniformly light is a contract, not a coincidence: [AVATAR_INK] is drawn over these
 * unconditionally, in the app and in a notification alike, so a fill added here has to be light
 * enough to carry it.
 */
private val AVATAR_COLORS = listOf(
    "#FFBCB0", // coral
    "#FFD199", // orange
    "#FFE68F", // amber
    "#C7F0B0", // green
    "#A2EEDD", // teal
    "#B3E7F8", // cyan
    "#BCD2FF", // blue
    "#CBCEFF", // indigo
    "#E1C6FF", // purple
    "#FFC5E2", // magenta
)

/**
 * Stands in for initials that cannot be taken, which is every conversation addressed by a bare
 * phone number. Named because it is written into the stored `initials` column and has to be
 * recognised again later, when an avatar decides between letters and a silhouette.
 */
const val NO_INITIALS = "#"

/**
 * The one letter a name is drawn as.
 *
 * First initial only. Two letters is a monogram — a thing you read — and at avatar size it has to
 * be set small enough to fit, which is how it ends up as a pair of marks you decode rather than a
 * face you recognise. One letter can be set large, and large is what makes a row findable when you
 * are scrolling past thirty of them.
 */
fun initialsFor(name: String): String {
    val initials = name.trim().split(Regex("\\s+"))
        .mapNotNull { it.firstOrNull() }
        .filter { it.isLetterOrDigit() }
        .joinToString("")
        .take(1)
        .uppercase()
    return initials.ifEmpty { NO_INITIALS }
}

/**
 * The one ink a monogram is ever drawn in.
 *
 * Fixed rather than chosen per fill. Every avatar in the app used to pick black or white from its
 * background's luminance, which was necessary while the palette straddled light and dark — but it
 * meant the same person's letter could be drawn one way in the list and the other way in a
 * notification if the two disagreed about the fill by even a shade. With the palette uniformly
 * light there is nothing left to decide, and one constant keeps the two surfaces honest.
 */
const val AVATAR_INK = "#1B1B1F"

/**
 * The fill for a name — the same fill every time that name is drawn.
 *
 * Seeded by the display name rather than by a contact id or an Apple handle, because those are not
 * the same string on every surface the person appears on: the chat list keys a conversation by its
 * derived chat id, the address book by the contact's row id, and a notification by the handle a
 * message arrived from. Hashing the name instead makes one person one colour everywhere, including
 * the monogram the shade draws beside their message — which is the only reason the two used to
 * disagree. A rename re-rolls the colour, which is the trade: a name is what the user sees, so a
 * colour that follows it stays explicable.
 */
fun colorFor(name: String): String {
    var hash = 0
    for (char in name) hash = (hash * 31 + char.code) % 9_973
    return AVATAR_COLORS[hash % AVATAR_COLORS.size]
}

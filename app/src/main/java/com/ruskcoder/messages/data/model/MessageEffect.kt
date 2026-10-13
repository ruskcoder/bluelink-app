package com.ruskcoder.messages.data.model

/**
 * The two families of send effect, which are two different things that happen to be picked from
 * the same place.
 *
 * A bubble effect is a property of the message: it changes how that one bubble arrives, and it is
 * still there to be replayed a week later. A screen effect is an event: it happens *to the
 * conversation* once, over everything, and leaves nothing behind but the note on the message that
 * says it happened.
 */
enum class EffectKind { BUBBLE, SCREEN }

/**
 * Apple's send effects, with the identifiers that actually go on the wire.
 *
 * The ids are not ours and are not guessable — `expressivesend.impact` for a slam, a `CK…Effect`
 * bundle name for anything full-screen — so they live on the enum and nothing else in the app ever
 * spells one out. Taken from the same table BlueBubbles uses, which is the reference for what the
 * other end will recognise.
 */
enum class MessageEffect(val wireId: String, val kind: EffectKind, val label: String) {
    SLAM("com.apple.MobileSMS.expressivesend.impact", EffectKind.BUBBLE, "Slam"),
    LOUD("com.apple.MobileSMS.expressivesend.loud", EffectKind.BUBBLE, "Loud"),
    GENTLE("com.apple.MobileSMS.expressivesend.gentle", EffectKind.BUBBLE, "Gentle"),
    INVISIBLE_INK("com.apple.MobileSMS.expressivesend.invisibleink", EffectKind.BUBBLE, "Invisible Ink"),
    ECHO("com.apple.messages.effect.CKEchoEffect", EffectKind.SCREEN, "Echo"),
    SPOTLIGHT("com.apple.messages.effect.CKSpotlightEffect", EffectKind.SCREEN, "Spotlight"),
    BALLOONS("com.apple.messages.effect.CKHappyBirthdayEffect", EffectKind.SCREEN, "Balloons"),
    CONFETTI("com.apple.messages.effect.CKConfettiEffect", EffectKind.SCREEN, "Confetti"),
    LOVE("com.apple.messages.effect.CKHeartEffect", EffectKind.SCREEN, "Love"),
    LASERS("com.apple.messages.effect.CKLasersEffect", EffectKind.SCREEN, "Lasers"),
    FIREWORKS("com.apple.messages.effect.CKFireworksEffect", EffectKind.SCREEN, "Fireworks"),
    CELEBRATION("com.apple.messages.effect.CKSparklesEffect", EffectKind.SCREEN, "Celebration");

    companion object {
        /** The four that change how one bubble lands, in the order iMessage offers them. */
        val bubble: List<MessageEffect> get() = entries.filter { it.kind == EffectKind.BUBBLE }

        /** The eight that take over the screen. */
        val screen: List<MessageEffect> get() = entries.filter { it.kind == EffectKind.SCREEN }

        /**
         * The effect an id names, or null.
         *
         * Null for an id this build has never heard of as well as for no id at all, and that is
         * deliberate: Apple adds effects, and a message carrying one we cannot draw should arrive
         * as an ordinary message rather than not arrive at all.
         */
        fun fromWire(id: String?): MessageEffect? = id?.let { wire -> entries.firstOrNull { it.wireId == wire } }
    }
}

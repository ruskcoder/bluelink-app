package com.ruskcoder.messages.data.model

import com.ruskcoder.messages.transcribe.SpeechModel
import kotlinx.serialization.Serializable

enum class ThemeMode { LIGHT, DARK }

/** Message bubble shape + chat wallpaper. */
enum class BubbleStyle { WHATSAPP, GOOGLE, APPLE }

/** The composer only has two looks: the Material bar and the translucent Apple one. */
enum class SendBarStyle { GOOGLE, APPLE }

/**
 * The send bar a bubble style comes with.
 *
 * The two used to be separate settings, which offered six combinations of which four nobody wants —
 * an Apple bar under Google bubbles is not a look, it is a mismatch. The bar follows the bubbles
 * now: Apple brings its own translucent one, and both Material styles share the Material one.
 */
fun BubbleStyle.sendBar(): SendBarStyle =
    if (this == BubbleStyle.APPLE) SendBarStyle.APPLE else SendBarStyle.GOOGLE

/**
 * The two backdrops the chat wallpaper comes in — a near-black blue-grey and a true black.
 *
 * There used to be six, plus a "match style" option that took its colour from the bubble setting.
 * Every one of them was a shade of dark that the conversation on top of it could not tell apart,
 * so the picker was five ways to choose the same screen; these two differ by having a cast at all.
 */
enum class WallpaperTint { SLATE, INK }

/** How a group's details screen lists its members. */
enum class MemberView { GRID, LIST }

/**
 * What replying looks like.
 * - QUOTE — Quick quote: the message you're replying to sits in a card on the send bar.
 * - FOCUS — Focus thread: it lifts out of the conversation, the rest blurs away, and your reply
 *   lands directly beneath it.
 */
enum class ReplyMode { QUOTE, FOCUS }

/**
 * How several photos or videos sent together are drawn in the conversation.
 * - GRID — one bubble holding a 2x2 grid of the first four, the last carrying a count of the rest.
 * - STACK — a stack of cards; flicking the top one sends it to the bottom.
 */
enum class MultiMediaStyle { GRID, STACK }

/**
 * What the gallery does on the "+" card: a resizable widget among the tiles, nothing at all, or the
 * card given over to it — the tiles in two scrolling rows with the gallery running on below them.
 */
enum class GalleryMode { WIDGET, NONE, FULL }

/** What a double tap on a message does. */
enum class DoubleTapAction { REACTIONS, QUICK, NONE }

/** How a pushed screen arrives. */
enum class PageTransition { DEFAULT, SLIDE, FADE, SHEET, NONE }

/**
 * Which app draws and runs the GamePigeon games.
 *
 * PIGEON is this project's own games app: fewer games, but ours to change.
 * OPENPIGEON is the finished one, spoken to over BlueBubbles' Madrid extension
 * protocol — it owns its picker, its boards and its bubble previews, so every game
 * it ships works here without any of that living in this app.
 *
 * A whole-app setting rather than a per-invite choice on purpose: a game is a
 * conversation that runs for days, and nothing on the wire records which app
 * opened it, so the alternative is a thread whose bubbles no longer open.
 */
enum class GameApp(val label: String) {
    PIGEON("Pigeon"),
    OPENPIGEON("OpenPigeon"),
}

/** Who a piece of your profile is shown to. */
enum class Visibility(val label: String) {
    EVERYONE("Everyone"),
    CONTACTS("My contacts"),
    NOBODY("Nobody"),
}

/** How long a new message survives before it disappears on its own. */
enum class MessageTimer(val label: String) {
    OFF("Off"),
    DAY("24 hours"),
    WEEK("7 days"),
    QUARTER("90 days"),
}

/** When an incoming attachment is fetched without being asked for. */
enum class MediaAutoDownload(val label: String) {
    WIFI("Wi-Fi only"),
    ALWAYS("Wi-Fi and mobile data"),
    NEVER("Never"),
}

/** What the reaction bar offers before anybody has changed it. */
val REACTION_EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

/**
 * An emptied slot in the bar.
 *
 * The bar is stored as a fixed-length list with holes rather than as a shorter list, so that
 * emptying the third slot and then filling it again puts the emoji back *where it was* instead of
 * on the end. The holes are the user's arrangement; only the rendering drops them.
 */
const val EMPTY_REACTION_SLOT = ""

/** The bar as it is actually drawn: the holes closed up, in the order they were left in. */
fun Settings.reactionEmoji(): List<String> = reactionBar.filter { it.isNotBlank() }

/**
 * What a double tap drops on a message.
 *
 * The first thing in the bar, always. It used to be a setting of its own, which meant two lists of
 * emoji to keep in agreement and a quick reaction that could be a glyph the bar did not even offer.
 * The bar is the arrangement; the first slot in it is the one nearest your thumb, which is what a
 * quick reaction is.
 */
fun Settings.quickReactionEmoji(): String = reactionEmoji().firstOrNull() ?: REACTION_EMOJI.first()

/**
 * How far a pinch on the conversation can push the message text either side of its default size.
 * There is no setting for this any more: the gesture *is* the control, so the range is a
 * continuous one rather than the handful of named steps the old picker offered.
 */
const val MIN_TEXT_SCALE = 0.8f
const val MAX_TEXT_SCALE = 1.6f

@Serializable
data class Settings(
    /**
     * Your own display name and photo.
     *
     * Held in settings rather than as constants: the app drew every "you" from a hardcoded
     * `USER_NAME`, so the avatar in the chat list was two fixed letters and nothing could tell
     * whether a group message mentioned you. Blank falls back to [USER_NAME].
     */
    val profileName: String = "",
    val profilePhotoUri: String? = null,
    val mode: ThemeMode = ThemeMode.DARK,
    val materialYou: Boolean = false,
    val bubbleType: BubbleStyle = BubbleStyle.GOOGLE,
    val notifications: Boolean = true,
    /**
     * Whether the banner offering to fix background delivery has been waved away.
     *
     * Stored rather than held for the session: the whole point of the banner is that it is asking
     * about something the user may not want to deal with right now, and a banner that comes back
     * every launch is one that gets dismissed reflexively rather than read. The row in Notification
     * settings is the permanent way back, so nothing is lost by taking no for an answer.
     *
     * Cleared again if the exemption is granted and later revoked, since that is a new situation
     * rather than the one that was dismissed — see the chat list.
     */
    val batteryBannerDismissed: Boolean = false,
    /**
     * Whether the other end is told when you have read their message.
     *
     * The global default for every conversation. A conversation only departs from it once its own
     * switch has been touched — see [Chat.readReceipts], which is null until then.
     */
    val readReceipts: Boolean = true,
    /** Whether the other end sees you typing; the global default, overridable per conversation like [readReceipts]. */
    val typingIndicators: Boolean = true,
    /** Dragging the thread left slides the bubbles over to reveal each message's time. */
    val swipeTimestamps: Boolean = true,
    /** The return key sends instead of inserting a newline. */
    val enterToSend: Boolean = false,
    /** Haptic feedback on swipe-to-reply and on send. */
    val haptics: Boolean = true,
    /**
     * Every photo and video in a conversation is saved to the phone as it arrives — the ones sent to
     * you once they have downloaded, and your own once they have gone out.
     */
    val autoSaveMedia: Boolean = false,
    /**
     * A link you send carries a card — the page's title and picture — fetched by this phone.
     *
     * On by default because it is what iMessage does. Off means this phone never contacts a site
     * because a link was typed into a message; received cards are drawn either way, since those were
     * fetched by whoever sent them and cost this phone nothing.
     */
    val linkPreviews: Boolean = true,
    /** The app sits behind the phone's own lock — see `AppLock`. */
    val appLock: Boolean = false,
    /** How long the app may be away before the lock applies again; 0 locks every time it is left. */
    val appLockTimeoutSeconds: Int = 0,
    /**
     * Notifications say who wrote and not what they wrote, and the widget shows no previews.
     *
     * For the lock screen and the shade, which anyone holding the phone can read.
     */
    val hideNotificationContent: Boolean = false,
    /**
     * Multiplier on every bubble's text, set by pinching the conversation. Defaults to 1 — the "M"
     * the old four-step picker called its middle notch — and an older stored value under the
     * previous `textSize` key is simply ignored, which lands on that same default.
     */
    val messageTextScale: Float = 1f,
    /** Draws the doodle pattern over the chat backdrop. */
    val wallpaperPattern: Boolean = true,
    val wallpaperTint: WallpaperTint = WallpaperTint.SLATE,
    /**
     * The app's own colour: the accent everywhere, and the scheme of every conversation that has
     * not been given one of its own. `DEFAULT` is the stock blue.
     */
    val appTheme: ChatThemeKey = ChatThemeKey.DEFAULT,
    val appThemeHue: Float = ChatThemeKey.DEFAULT_HUE,
    val memberView: MemberView = MemberView.GRID,
    val replyMode: ReplyMode = ReplyMode.QUOTE,
    val doubleTapAction: DoubleTapAction = DoubleTapAction.REACTIONS,
    /**
     * The six slots of the reaction bar, in order, with [EMPTY_REACTION_SLOT] for an emptied one.
     *
     * Fixed length by construction — see [EMPTY_REACTION_SLOT] — so a bar with a hole in it is a
     * bar you can put something back into. What gets drawn is [reactionEmoji].
     */
    val reactionBar: List<String> = REACTION_EMOJI,
    /**
     * Long press a bubble for a small icon menu — reply, edit, copy, select text, "more" for a
     * multi-message selection — instead of dropping straight into selection mode.
     */
    val longPressMenu: Boolean = true,
    val pageTransition: PageTransition = PageTransition.DEFAULT,
    /** How a batch of photos sent together is drawn — see [MultiMediaStyle]. */
    val multiMediaStyle: MultiMediaStyle = MultiMediaStyle.GRID,
    /** Who sees your last-seen, your photo, and your about line. */
    val lastSeenVisibility: Visibility = Visibility.EVERYONE,
    val profilePhotoVisibility: Visibility = Visibility.EVERYONE,
    val aboutVisibility: Visibility = Visibility.EVERYONE,
    val messageTimer: MessageTimer = MessageTimer.OFF,
    val mediaAutoDownload: MediaAutoDownload = MediaAutoDownload.WIFI,
    val securityNotifications: Boolean = true,
    val twoStepVerification: Boolean = false,
    /**
     * Which app runs the games — see [GameApp].
     *
     * No longer offered in Settings: OpenPigeon is the only one wired up, and nothing reads this
     * any more. Kept so the stored settings and the Pigeon port stay as they were.
     */
    val gameApp: GameApp = GameApp.OPENPIGEON,
    /**
     * Which speech model transcribes voice messages — see [SpeechModel].
     *
     * Only a preference: the model it names is not necessarily downloaded, and nothing transcribes
     * until it is. Changing this while another model is installed leaves that one on disk rather
     * than deleting it behind your back.
     */
    val speechModel: SpeechModel = SpeechModel.DEFAULT,
    /**
     * Hold model downloads until the phone is on an unmetered connection.
     *
     * On by default because the smallest of them is 31MB and the largest is 190MB, which is not an
     * amount to spend from somebody's data plan without being asked.
     */
    val speechModelWifiOnly: Boolean = true,
    /**
     * The order the "+" card's tiles are arranged in, by id — see `AttachmentPanel`.
     *
     * Empty means "as shipped", which is not the same as a stored order that happens to match:
     * an empty list lets the panel's own default change under an arrangement nobody has touched,
     * and only a list somebody actually dragged into place pins the order against that.
     *
     * Ids the panel no longer knows are dropped on read and tiles it has gained are appended, so a
     * saved arrangement survives the set of tiles changing rather than freezing it.
     */
    val attachmentTiles: List<String> = emptyList(),
    /** How many grid cells the "+" card's recent-photos widget spans, across and down. */
    val photosWidgetColumns: Int = 2,
    val photosWidgetRows: Int = 1,
    /** How many pictures the recent-photos widget shows, picked from its corner badge. */
    val photosWidgetCount: Int = 3,
    /** Whether the "+" card carries the gallery as a widget, not at all, or as its whole lower half. */
    val galleryMode: GalleryMode = GalleryMode.FULL,
)

/** The bubble text multiplier for the current setting, held inside the pinch's range. */
fun Settings.textScale(): Float = messageTextScale.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)

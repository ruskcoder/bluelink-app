package com.ruskcoder.messages.imessage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.PorterDuffXfermode
import android.graphics.PorterDuff
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.net.toUri
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.LocusIdCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.ruskcoder.messages.MainActivity
import com.ruskcoder.messages.R
import com.ruskcoder.messages.data.model.AVATAR_INK
import com.ruskcoder.messages.data.model.colorFor
import com.ruskcoder.messages.data.model.initialsFor

/**
 * Posts a notification per conversation as messages arrive.
 *
 * Uses `MessagingStyle` so the system renders it the way it renders every other chat app — grouped
 * by conversation, with each message attributed to its sender. Each chat also publishes a dynamic
 * shortcut and tags its notification with the matching `LocusId`, which is what Android requires
 * before it will treat these as real conversations: the conversation section at the top of the
 * shade, priority and bubble settings, and a long-press that offers to pin the thread.
 */
class MessageNotifications(private val context: Context) {

    /** Whether notifications say who wrote but not what — see [Settings.hideNotificationContent]. */
    var hideContent: () -> Boolean = { false }

    /** The emoji the notification's one-tap reaction places — the same one double-tap places. */
    var quickReaction: () -> String = { "❤️" }

    /** Recent lines per chat, so a second message extends the conversation instead of replacing it. */
    private val history = mutableMapOf<String, MutableList<Line>>()

    /** The conversation's name and face, kept so a re-post (e.g. a photo arriving) can rebuild it. */
    private data class ChatMeta(val chatName: String, val chatPhotoUri: String?)
    private val meta = mutableMapOf<String, ChatMeta>()

    /** One rendered message inside a conversation's notification. */
    private data class Line(
        val sender: String,
        val text: String,
        val at: Long,
        val fromMe: Boolean,
        /** The sender's real photo, when the address book has one. Falls back to their monogram. */
        val photoUri: String? = null,
        /** A local image file for this line, shown inline in the expanded notification. */
        val picturePath: String? = null,
        /** The message this line is, where it is one that can be reacted to from the shade. */
        val messageId: String? = null,
    )

    fun notify(
        chatId: String,
        chatName: String,
        senderName: String,
        text: String,
        /** The sender's contact photo, and the conversation's, when either is known. */
        senderPhotoUri: String? = null,
        chatPhotoUri: String? = null,
        /** A local image file when the message is a picture whose bytes are already here. */
        picturePath: String? = null,
        /** The message, so the notification can offer a one-tap reaction to it. */
        messageId: String? = null,
    ) {
        post(chatId, chatName, senderName, text, fromMe = false, senderPhotoUri, chatPhotoUri, picturePath, messageId)
    }

    /**
     * Announce a reaction someone placed on a message.
     *
     * Routed through the same conversation notification as a message so it lands in the thread's
     * own bundle rather than as a stray alert — "Alex reacted 😂 to …".
     */
    fun notifyReaction(
        chatId: String,
        chatName: String,
        senderName: String,
        emoji: String,
        targetPreview: String,
        senderPhotoUri: String? = null,
        chatPhotoUri: String? = null,
    ) {
        val trimmed = targetPreview.trim().let { if (it.length > 60) it.take(59) + "…" else it }
        val text = if (trimmed.isBlank()) "Reacted $emoji" else "Reacted $emoji to “$trimmed”"
        post(chatId, chatName, senderName, text, fromMe = false, senderPhotoUri, chatPhotoUri)
    }

    /**
     * Attach a picture to the most recent inbound line and re-post, once its bytes have downloaded.
     *
     * The notification for a photo goes up immediately with just its caption; the file arrives a
     * moment later on a background fetch, and this folds it in without buzzing the phone again.
     */
    fun attachPicture(chatId: String, picturePath: String) {
        if (!canPost()) return
        val lines = history[chatId] ?: return
        val idx = lines.indexOfLast { !it.fromMe && it.picturePath == null }
            .takeIf { it >= 0 } ?: return
        lines[idx] = lines[idx].copy(picturePath = picturePath)
        render(chatId, alertOnce = true)
    }

    /**
     * Append our own reply to the conversation's notification.
     *
     * Posting again rather than cancelling keeps the thread on screen after an inline reply, which
     * is what the system's own messaging apps do — the reply appears in place, attributed to us.
     */
    fun appendOwnReply(chatId: String, chatName: String, text: String) {
        if (history[chatId] == null) return
        post(chatId, chatName, senderName = "", text = text, fromMe = true)
    }

    private fun post(
        chatId: String,
        chatName: String,
        senderName: String,
        text: String,
        fromMe: Boolean,
        senderPhotoUri: String? = null,
        chatPhotoUri: String? = null,
        picturePath: String? = null,
        messageId: String? = null,
    ) {
        if (!canPost()) return

        val lines = history.getOrPut(chatId) { mutableListOf() }
        lines += Line(senderName, text, System.currentTimeMillis(), fromMe, senderPhotoUri, picturePath, messageId)
        // A notification is a summary, not an archive; the thread itself is where history lives.
        while (lines.size > MAX_LINES) lines.removeAt(0)
        meta[chatId] = ChatMeta(chatName, chatPhotoUri ?: meta[chatId]?.chatPhotoUri)

        render(chatId, alertOnce = fromMe)
    }

    /** (Re)build and post a conversation's notification from its stored lines. */
    private fun render(chatId: String, alertOnce: Boolean) {
        if (!canPost()) return
        ensureChannel()

        val lines = history[chatId] ?: return
        val chatMeta = meta[chatId] ?: return
        val chatName = chatMeta.chatName
        val chatPhotoUri = chatMeta.chatPhotoUri
        val senderName = lines.lastOrNull { !it.fromMe }?.sender.orEmpty()

        val group = chatName.isNotBlank() && chatName != senderName
        // The conversation's own face where there is one, otherwise the face of whoever last
        // spoke — in a one-to-one those are the same person, and in a group the shortcut stands for
        // the thread rather than for any one member.
        val conversationPhoto = chatPhotoUri ?: lines.lastOrNull { !it.fromMe }?.photoUri
        val shortcutId = pushShortcut(chatId, chatName.ifBlank { senderName }, conversationPhoto)

        val self = Person.Builder().setName("You").setKey("self").build()
        val style = NotificationCompat.MessagingStyle(self)
            .setConversationTitle(chatName.takeIf { group })
            .setGroupConversation(group)

        // With message text hidden, the shade says who wrote and not what: the lock screen and the
        // shade are readable by anyone holding the phone.
        val hidden = hideContent()
        lines.forEach { line ->
            val who = if (line.fromMe) null else personFor(line.sender, line.photoUri)
            val message = NotificationCompat.MessagingStyle.Message(if (hidden) HIDDEN_TEXT else line.text, line.at, who)
            // The picture rides on the message itself, so the system draws it inline when the
            // notification is expanded and shows a thumbnail while it is collapsed.
            if (!hidden) {
                line.picturePath?.let { path ->
                    pictureUri(path)?.let { uri -> message.setData(mimeFor(path), uri) }
                }
            }
            style.addMessage(message)
        }

        // A photo just in gets pulled up as the collapsed notification's own large image, the way
        // every other messaging app previews one; otherwise the conversation's face stays there.
        val newestPicture = if (hidden) null else lines.lastOrNull()?.picturePath?.let { decodePicture(it) }
        // The newest thing someone else said, which is what a one-tap reaction answers.
        val reactable = lines.lastOrNull { !it.fromMe && it.messageId != null }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(openChat(chatId))
            .setDeleteIntent(NotificationActions.dismissIntent(context, chatId))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setShortcutId(shortcutId)
            // The face the shade shows beside a collapsed notification. Without it every
            // conversation was announced by the app's launcher icon, which is the one picture that
            // says nothing about which conversation it is.
            .setLargeIcon(newestPicture ?: avatarBitmap(chatName.ifBlank { senderName }, conversationPhoto))
            .setLocusId(LocusIdCompat(shortcutId))
            // Only alert for the message that just arrived; re-posting after an inline reply — or to
            // fold in a photo that finished downloading — must not buzz the phone again.
            .setOnlyAlertOnce(alertOnce)
            .addAction(replyAction(chatId, chatName))
        // Between reply and mark-as-read: the three actions a shade has room for, in the order a
        // message is usually answered — words, a reaction, or simply having seen it.
        reactable?.let { line -> builder.addAction(reactAction(chatId, line.messageId!!, line.text)) }
        builder.addAction(markReadAction(chatId))
            // The conversation can float in a bubble over other apps, where the platform and the
            // person allow it. Suppressed on a re-post, so folding in a photo does not pop it open.
            .setBubbleMetadata(bubbleMetadata(chatId, conversationPhoto, chatName.ifBlank { senderName }))
        if (hidden) {
            builder
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(
                    NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_notification)
                        .setContentTitle(HIDDEN_TEXT)
                        .build(),
                )
        }

        NotificationManagerCompat.from(context).notify(chatId.hashCode(), builder.build())
    }

    /** Clears a conversation's notification once its messages have been seen. */
    fun clear(chatId: String) {
        history.remove(chatId)
        meta.remove(chatId)
        NotificationManagerCompat.from(context).cancel(chatId.hashCode())
    }

    // -----------------------------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------------------------

    private fun replyAction(chatId: String, chatName: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(NotificationActions.KEY_REPLY)
            .setLabel("Reply")
            .build()

        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, android.R.drawable.ic_menu_send),
            "Reply",
            NotificationActions.replyIntent(context, chatId, chatName),
        )
            .addRemoteInput(remoteInput)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            // Replying must not drag the app to the foreground; the whole point is to answer
            // without leaving what you were doing.
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(true)
            .build()
    }

    /** Places the quick reaction on the newest message, without opening anything. */
    private fun reactAction(chatId: String, messageId: String, text: String): NotificationCompat.Action {
        val emoji = quickReaction()
        return NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, android.R.drawable.star_on),
            emoji,
            NotificationActions.reactIntent(context, chatId, messageId, text, emoji),
        )
            .setShowsUserInterface(false)
            .build()
    }

    /**
     * What lets the conversation float as a bubble.
     *
     * Bound to the conversation's shortcut as well as to its own activity, which is what Android 11+
     * requires before it offers a bubble at all.
     */
    private fun bubbleMetadata(chatId: String, photoUri: String?, name: String): NotificationCompat.BubbleMetadata? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val intent = PendingIntent.getActivity(
            context,
            chatId.hashCode() + BUBBLE_REQUEST_OFFSET,
            com.ruskcoder.messages.BubbleActivity.intent(context, chatId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.BubbleMetadata.Builder(intent, avatarIcon(name, photoUri))
            .setDesiredHeight(BUBBLE_HEIGHT_DP)
            .setSuppressNotification(false)
            .build()
    }

    private fun markReadAction(chatId: String): NotificationCompat.Action =
        NotificationCompat.Action.Builder(
            IconCompat.createWithResource(context, android.R.drawable.ic_menu_view),
            "Mark as read",
            NotificationActions.markReadIntent(context, chatId),
        )
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

    private fun openChat(chatId: String): PendingIntent {
        val open = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
            // Only from here. A conversation shortcut carries the same chat id and lands in the
            // same place, but it means "talk to this person" rather than "answer this message" —
            // and it is pressed from the launcher, where the keyboard arriving unasked over the
            // conversation is a surprise. Tapping a notification is already an answer in progress.
            putExtra(EXTRA_FOCUS_COMPOSER, true)
        }
        return PendingIntent.getActivity(
            context,
            chatId.hashCode(),
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // -----------------------------------------------------------------------------------------
    // Conversation shortcuts
    // -----------------------------------------------------------------------------------------

    /**
     * Publish the conversation as a dynamic shortcut and return its id.
     *
     * Android only promotes a notification into the conversation section if it points at a
     * long-lived shortcut, so this is a requirement rather than a nicety.
     */
    private fun pushShortcut(chatId: String, label: String, photoUri: String? = null): String {
        val id = SHORTCUT_PREFIX + chatId
        val name = label.ifBlank { "Conversation" }
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_CHAT_ID, chatId)
        }

        val shortcut = ShortcutInfoCompat.Builder(context, id)
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(avatarIcon(name, photoUri))
            .setIntent(intent)
            .setLongLived(true)
            .setLocusId(LocusIdCompat(id))
            .setPerson(personFor(name))
            .setCategories(setOf(SHORTCUT_CATEGORY))
            .build()

        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
        return id
    }

    private fun personFor(name: String, photoUri: String? = null): Person =
        Person.Builder()
            .setName(name.ifBlank { "Unknown" })
            .setKey(name)
            .setIcon(avatarIcon(name, photoUri))
            .build()

    /**
     * A round initial-on-colour avatar, matching how the app draws one in-app.
     *
     * Drawn rather than loaded from the address book because a notification needs an icon
     * synchronously on the push path, and the contact lookup is neither guaranteed to have a photo
     * nor cheap enough to block on.
     */
    private fun avatarIcon(name: String, photoUri: String? = null): IconCompat =
        IconCompat.createWithAdaptiveBitmap(avatarBitmap(name, photoUri))

    /**
     * The contact's own photo, circle-cropped — or the monogram where there isn't one.
     *
     * Decoded here on the push path rather than fetched asynchronously. It is a local content uri
     * the address book has already resolved, read down to icon size, and a notification has to be
     * built in one go: a face that arrives after the notification has posted is a face nobody sees.
     * Anything that goes wrong with the decode falls through to the monogram, which is what every
     * contact got before.
     */
    private fun avatarBitmap(name: String, photoUri: String? = null): Bitmap {
        photoUri?.takeIf { it.isNotBlank() }?.let { uri ->
            runCatching { circleCrop(decodePhoto(uri)) }.getOrNull()?.let { return it }
        }
        val initial = initialsFor(name).ifBlank { "?" }
        val size = AVATAR_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Seeded by the same string the app hashes — the display name — so the face the shade
        // draws is the face the chat list draws. It used to be seeded by the chat id here and by
        // the contact's row id in the list, which are different strings for the same person, so the
        // two surfaces reliably disagreed about a contact's colour.
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = runCatching { android.graphics.Color.parseColor(colorFor(name)) }
                .getOrDefault(android.graphics.Color.DKGRAY)
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, background)

        // Same ink and the same proportion the in-app avatar uses, so a monogram in the shade is
        // the one the conversation wears rather than a second, smaller rendering of it.
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = runCatching { android.graphics.Color.parseColor(AVATAR_INK) }
                .getOrDefault(android.graphics.Color.BLACK)
            textSize = size * LABEL_RATIO
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT
        }
        val baseline = size / 2f - (glyph.descent() + glyph.ascent()) / 2f
        canvas.drawText(initial, size / 2f, baseline, glyph)

        return bitmap
    }

    /**
     * A `content://` uri for a downloaded picture, readable by the system UI that draws the shade.
     *
     * The attachments directory is already published through the app's `FileProvider`; a bare
     * `file://` path would throw `FileUriExposedException` the moment the notification crossed into
     * the system process.
     */
    private fun pictureUri(path: String): Uri? = runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        context.grantUriPermission("com.android.systemui", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uri
    }.getOrNull()

    private fun mimeFor(path: String): String = when (path.substringAfterLast('.').lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "heic", "heif" -> "image/heic"
        else -> "image/jpeg"
    }

    /** The picture, decoded down to something a notification can carry without tripping its size cap. */
    private fun decodePicture(path: String): Bitmap? = runCatching {
        val file = File(path)
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        if (largest <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, Integer.highestOneBit(largest / PICTURE_PX))
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrNull()

    /** The contact's photo, decoded no larger than the icon it is going to become. */
    private fun decodePhoto(uri: String): Bitmap? {
        val parsed = uri.toUri()
        // Bounds first, so a full-resolution camera photo is never decoded only to throw most of
        // it away.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val smallest = minOf(bounds.outWidth, bounds.outHeight)
        if (smallest <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = maxOf(1, Integer.highestOneBit(smallest / AVATAR_PX))
        }
        return context.contentResolver.openInputStream(parsed)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /** Centre-cropped square, masked to a circle — the way the app draws an avatar. */
    private fun circleCrop(source: Bitmap?): Bitmap? {
        if (source == null) return null
        val size = AVATAR_PX
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        // The largest centred square of the source, blown up to fill the circle.
        val side = minOf(source.width, source.height)
        val left = (source.width - side) / 2
        val top = (source.height - side) / 2
        canvas.drawBitmap(source, Rect(left, top, left + side, top + side), Rect(0, 0, size, size), paint)
        return output
    }

    // -----------------------------------------------------------------------------------------
    // Plumbing
    // -----------------------------------------------------------------------------------------

    /**
     * Posting without the runtime permission throws on API 33+, and the permission is the user's to
     * grant — so a refusal quietly means no notifications rather than a crashed push reader.
     */
    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "New iMessages"
                setShowBadge(true)
            }
        )
    }

    companion object {
        const val EXTRA_CHAT_ID = "com.ruskcoder.messages.CHAT_ID"

        /** Set on a notification's own tap intent: open the conversation ready to be typed into. */
        const val EXTRA_FOCUS_COMPOSER = "com.ruskcoder.messages.FOCUS_COMPOSER"

        private const val CHANNEL_ID = "imessage_messages"
        private const val MAX_LINES = 6

        /** What a conversation's shortcut id starts with, ahead of its chat id — read back by direct share. */
        const val SHORTCUT_PREFIX = "chat:"

        /** Said in place of a message's words when notifications are set to hide them. */
        private const val HIDDEN_TEXT = "New message"

        private const val BUBBLE_REQUEST_OFFSET = 7
        private const val BUBBLE_HEIGHT_DP = 600
        private const val AVATAR_PX = 128

        /** The longest edge a picture is decoded to before it rides along on a notification. */
        private const val PICTURE_PX = 1024

        /** How much of the circle the letter fills. Mirrors the in-app avatar's own proportion. */
        private const val LABEL_RATIO = 0.56f

        /**
         * The category a conversation shortcut has to declare for the system share sheet and the
         * conversation surfaces to pick it up.
         */
        private const val SHORTCUT_CATEGORY = "android.shortcut.conversation"
    }
}

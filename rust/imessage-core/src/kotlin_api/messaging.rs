//! Sending, and the single stream every inbound push arrives on.

use std::sync::Arc;

use log::{info, warn};
use rustpush::{
    APSMessage, Attachment, Balloon, BalloonLayout, ChangeParticipantMessage, ConversationData,
    EditMessage, ExtensionApp, IndexedMessagePart, MMCSFile, Message, MessageInst, MessagePart,
    MessageParts, MessageType, NSDictionaryClass, NormalMessage, PushError, ReactMessage,
    PartExtension, ReactMessageType, Reaction, RenameMessage, TextEffect, TextFlags, TextFormat, UnsendMessage,
    statuskit::StatusKitMessage,
};
use std::collections::HashMap;
use std::io::Seek;
use std::time::{Duration, Instant, SystemTime};
use tokio::sync::{broadcast, mpsc};

use super::{ImError, Result, Session};
use crate::api::api;

// ---------------------------------------------------------------------------
// Addressing
// ---------------------------------------------------------------------------

/// Who a message is going to, and who it is from.
///
/// iMessage has no server-side chat id: a conversation *is* its participant set, plus a group
/// identifier once it has more than two members. `participants` and `sender` are handles in Apple's
/// URI form (`mailto:someone@example.com`, `tel:+15551234567`).
#[derive(uniffi::Record, Clone)]
pub struct ChatTarget {
    pub participants: Vec<String>,
    pub sender: String,
    /// The group's display name, when it has one.
    pub group_name: Option<String>,
    /// Stable identifier for a group, carried as the conversation's `sender_guid`.
    pub group_id: Option<String>,
}

impl From<ChatTarget> for ConversationData {
    fn from(value: ChatTarget) -> Self {
        ConversationData {
            participants: value.participants,
            cv_name: value.group_name,
            sender_guid: value.group_id,
            after_guid: None,
        }
    }
}

fn to_chat_target(
    conversation: &ConversationData,
    remote_sender: Option<&String>,
    local_handles: &[String],
) -> ChatTarget {
    // `MessageInst.sender` is the remote originator on receive.  ChatTarget.sender, however, is
    // the identity this device must use for replies.  Prefer the local handle already present in
    // the conversation and only fall back to another registered handle for malformed payloads.
    let sender = conversation
        .participants
        .iter()
        .find(|participant| {
            local_handles.contains(participant) && remote_sender != Some(*participant)
        })
        .or_else(|| local_handles.first())
        .cloned()
        .unwrap_or_default();
    ChatTarget {
        participants: conversation.participants.clone(),
        sender,
        group_name: conversation.cv_name.clone(),
        group_id: conversation.sender_guid.clone(),
    }
}

// ---------------------------------------------------------------------------
// Outbound payloads
// ---------------------------------------------------------------------------

/// A file to upload and attach.
///
/// `uti` is Apple's own type identifier (`public.jpeg`, `public.mpeg-4`, …). It travels alongside
/// the MIME type rather than being derived from it, because Apple clients key their rendering off
/// the UTI — get it wrong and a photo arrives as an unopenable blob.
#[derive(uniffi::Record)]
pub struct OutgoingAttachment {
    /// A real filesystem path. Content URIs have to be resolved to one before getting here.
    pub path: String,
    pub name: String,
    pub mime: String,
    pub uti: String,
    /// What to label this upload's progress with, or `None` to report none.
    ///
    /// Chosen by the caller rather than handed back by us, because the row it belongs to exists
    /// before the send does — the bubble is drawn the moment the file is picked, and it needs to
    /// recognise its own progress from the very first byte.
    pub transfer_id: Option<String>,
}

#[derive(uniffi::Record)]
pub struct OutgoingText {
    pub chat: ChatTarget,
    pub text: String,
    pub subject: Option<String>,
    /// Uploaded to Apple's MMCS before the message is sent, and carried as extra message parts.
    pub attachments: Vec<OutgoingAttachment>,
    /// GUID of the message being replied to.
    pub reply_to_guid: Option<String>,
    /// Which part of that message. iMessage addresses replies at part granularity, not message
    /// granularity, because one message can carry several parts.
    pub reply_to_part: Option<String>,
    /// A send effect, e.g. `invisibleink` or `CKConfettiEffect`.
    pub effect: Option<String>,
    /// Marks the message as a recorded audio message rather than an attached sound file.
    ///
    /// This flag, not the attachment's type, is what makes the receiving client render a playable
    /// bubble with a waveform; without it the recording arrives as a file to download.
    pub voice: bool,
    /// The GUID to send the message under, or `None` for a fresh one.
    ///
    /// Chosen by the app so that sending the same row again is the same message: a send whose
    /// acknowledgement was lost did reach the other end, and a retry under a new GUID arrived there
    /// as a second copy. Under the original one the receiving device recognises it as a message it
    /// already has.
    pub guid: Option<String>,
    /// The message's text broken into formatted stretches, or empty for plain text.
    ///
    /// Concatenated these must equal [`text`], which is still sent as the message's plain body and
    /// is what a client that ignores formatting will show. Empty is the ordinary case and costs
    /// nothing: one unformatted part, exactly as before.
    pub runs: Vec<TextRunSpec>,
    /// A rich link card for the link in [`text`], fetched by the app before sending.
    ///
    /// Sent the way Apple's own clients send one: as a `URLBalloonProvider` balloon whose payload is
    /// the page's `LPLinkMetadata`, with the card's picture riding along as a balloon attachment.
    /// The receiving phone draws the card from that and never contacts the linked site itself.
    #[uniffi(default = None)]
    pub link_preview: Option<LinkPreviewSpec>,
}

/// A link card to send — see [`OutgoingText::link_preview`].
#[derive(uniffi::Record)]
pub struct LinkPreviewSpec {
    pub url: String,
    pub title: Option<String>,
    pub summary: Option<String>,
    /// The card's picture, already downscaled by the app, or `None` for a card without one.
    pub image: Option<Vec<u8>>,
    pub image_mime: Option<String>,
    pub image_width: u32,
    pub image_height: u32,
}

/// A link card that arrived with a message, with its picture's bytes where it carried one.
#[derive(uniffi::Record, Clone)]
pub struct IncomingLinkPreview {
    pub url: String,
    pub title: Option<String>,
    pub summary: Option<String>,
    pub image: Option<Vec<u8>>,
    pub image_mime: Option<String>,
}

/// An iMessage app / balloon-plugin message — a GamePigeon-style invite, a poll, and so on.
///
/// This is what an iMessage extension ("Madrid extension") produces: a bubble drawn by the
/// receiving client's balloon plugin rather than by the text renderer. The wire message carries a
/// [`NormalMessage`] with its `app` field set, plus one message part per the notes on the fields
/// below; a client without the matching plugin falls back to showing [`ld_text`].
///
/// [`url`], [`session`] and the layout captions are produced by the extension and must travel
/// **verbatim** — the payload behind `url` is position-sensitive and any re-encoding corrupts it.
#[derive(uniffi::Record)]
pub struct OutgoingAppMessage {
    pub chat: ChatTarget,
    /// The balloon-plugin bundle id, e.g.
    /// `com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext`.
    pub bundle_id: String,
    /// The extension's display name, e.g. `GamePigeon`.
    pub app_name: String,
    /// Apple's numeric App Store id for the extension, when known.
    pub app_id: Option<u64>,
    /// Human-readable summary, e.g. `Word Hunt`. Sent as an object part so it is what a client with
    /// no matching plugin shows, and echoed into the balloon.
    pub ld_text: String,
    /// The extension's payload. Opaque; sent exactly as given.
    pub url: String,
    /// Game/thread identifier, preserved unchanged for the life of the exchange.
    pub session: Option<String>,
    /// `MSMessageTemplateLayout` strings. Empty where the extension left them unset.
    pub caption: Option<String>,
    pub subcaption: Option<String>,
    pub image_title: Option<String>,
    pub image_subtitle: Option<String>,
    pub secondary_subcaption: Option<String>,
    pub tertiary_subcaption: Option<String>,
    /// Whether the bubble updates in place as the exchange continues.
    pub is_live: bool,
    /// The extension's icon (PNG/JPEG bytes), drawn on the bubble by clients that have no plugin.
    pub icon: Option<Vec<u8>>,
    /// A preview image to upload and attach alongside the balloon, already written to a real path.
    pub preview: Option<OutgoingAttachment>,
    pub reply_to_guid: Option<String>,
    pub reply_to_part: Option<String>,
}

/// A further turn on a balloon message already in the thread — see [`Session::send_app_update`].
///
/// Apple does not send a second bubble for a second move. A continuation rides as an association
/// against the message that opened the exchange (`amt=2`), carrying the whole new payload; the
/// receiving client swaps it into the bubble already on screen. [`target_guid`] is that opening
/// message's guid and must not change for the life of the game.
#[derive(uniffi::Record)]
pub struct OutgoingAppUpdate {
    pub chat: ChatTarget,
    /// Guid of the message that opened the exchange — the bubble this turn updates.
    pub target_guid: String,
    /// The balloon-plugin bundle id, unchanged from the opening message.
    pub bundle_id: String,
    pub app_name: String,
    pub app_id: Option<u64>,
    /// Human-readable summary, e.g. `Word Hunt`.
    pub ld_text: String,
    /// The extension's payload for this turn. Opaque; sent exactly as given.
    pub url: String,
    /// Game/thread identifier — the same value the opening message carried.
    pub session: Option<String>,
    pub caption: Option<String>,
    pub subcaption: Option<String>,
    pub image_title: Option<String>,
    pub image_subtitle: Option<String>,
    pub secondary_subcaption: Option<String>,
    pub tertiary_subcaption: Option<String>,
    pub is_live: bool,
    pub icon: Option<Vec<u8>>,
    /// A fresh preview image for the updated bubble, when the extension rendered one.
    pub preview: Option<OutgoingAttachment>,
    /// Text for the notification the update raises. Empty for a silent turn.
    pub notification_text: String,
}

/// One stretch of a message's text carrying a single format.
///
/// Flags and effects are mutually exclusive, which is Apple's own model rather than a
/// simplification: `TextFormat` is either a set of style flags or one animation, and a run that
/// tried to be both would have to be written to the wire as two nested tags that iMessage does not
/// read. When an effect is set, the flags on the same run are ignored.
#[derive(uniffi::Record, Clone)]
pub struct TextRunSpec {
    pub text: String,
    pub bold: bool,
    pub italic: bool,
    pub underline: bool,
    pub strikethrough: bool,
    /// Apple's own effect id — 5 big, 11 small, 9 shake, 8 nod, 12 explode, 4 ripple, 6 bloom,
    /// 10 jitter. An id this build does not know is dropped to plain rather than refused.
    pub effect: Option<u32>,
}

impl TextRunSpec {
    fn format(&self) -> TextFormat {
        if let Some(effect) = self.effect.and_then(|id| TextEffect::try_from(id).ok()) {
            return TextFormat::Effect(effect);
        }
        TextFormat::Flags(TextFlags {
            bold: self.bold,
            italic: self.italic,
            underline: self.underline,
            strikethrough: self.strikethrough,
        })
    }
}

/// The text of a message as parts: one per formatted run, or a single plain one.
///
/// An empty stretch is dropped rather than sent — an empty part renders as a stray blank bubble —
/// and a message with no text at all contributes no parts, which is what an attachment sent
/// without a caption needs.
fn text_parts(text: &str, runs: &[TextRunSpec]) -> Vec<IndexedMessagePart> {
    if runs.is_empty() {
        if text.is_empty() {
            return vec![];
        }
        return vec![IndexedMessagePart {
            part: MessagePart::Text(text.to_string(), Default::default()),
            idx: None,
            ext: None,
        }];
    }
    runs.iter()
        .filter(|run| !run.text.is_empty())
        .map(|run| IndexedMessagePart {
            part: MessagePart::Text(run.text.clone(), run.format()),
            idx: None,
            ext: None,
        })
        .collect()
}

/// The tapbacks iMessage supports. `Sticker` is receive-only upstream, so it is not offered here.
#[derive(uniffi::Enum, Clone, Debug)]
pub enum TapbackKind {
    Heart,
    Like,
    Dislike,
    Laugh,
    Emphasize,
    Question,
    Emoji { emoji: String },
}

impl TapbackKind {
    fn to_reaction(&self) -> Reaction {
        match self {
            Self::Heart => Reaction::Heart,
            Self::Like => Reaction::Like,
            Self::Dislike => Reaction::Dislike,
            Self::Laugh => Reaction::Laugh,
            Self::Emphasize => Reaction::Emphasize,
            Self::Question => Reaction::Question,
            Self::Emoji { emoji } => Reaction::Emoji(emoji.clone()),
        }
    }

    fn from_reaction(reaction: &Reaction) -> Option<Self> {
        Some(match reaction {
            Reaction::Heart => Self::Heart,
            Reaction::Like => Self::Like,
            Reaction::Dislike => Self::Dislike,
            Reaction::Laugh => Self::Laugh,
            Reaction::Emphasize => Self::Emphasize,
            Reaction::Question => Self::Question,
            Reaction::Emoji(emoji) => Self::Emoji {
                emoji: emoji.clone(),
            },
            // Stickers carry an extension payload this app has nothing to render.
            Reaction::Sticker { .. } => return None,
        })
    }
}

#[derive(uniffi::Record)]
pub struct OutgoingTapback {
    pub chat: ChatTarget,
    pub target_guid: String,
    pub target_part: u64,
    /// The text being reacted to, which Apple echoes in the fallback body ("Loved ...").
    pub target_text: String,
    pub kind: TapbackKind,
    /// `false` removes a previously sent tapback.
    pub enable: bool,
}

/// Where a sticker sits on the bubble it was put on — Apple's `PartExtension::Sticker`, in the
/// terms the app works in.
///
/// `x` and `y` are the sticker's centre as a fraction of the bubble's width and height, `scale` is
/// its size against its natural one, and `rotation` is in radians, clockwise, within ±π.
/// `bubble_width` is how wide the bubble was, in points, on the device that placed it.
#[derive(uniffi::Record, Clone, Debug)]
pub struct StickerPlacement {
    pub x: f64,
    pub y: f64,
    pub scale: f64,
    pub rotation: f64,
    pub bubble_width: f64,
    pub effect_type: i64,
}

/// A sticker to put on a message: its picture, and where on the bubble it goes.
#[derive(uniffi::Record)]
pub struct OutgoingSticker {
    pub chat: ChatTarget,
    pub target_guid: String,
    pub target_part: u64,
    /// The text being stuck on, which Apple echoes in the fallback body.
    pub target_text: String,
    /// The picture, a PNG with transparency around the cut-out.
    pub image: OutgoingAttachment,
    pub placement: StickerPlacement,
    /// Identifies the picture: a stable hash of its bytes, 16 hex digits.
    pub hash: String,
    /// The emoji the sticker was made from, which an iPhone names it by.
    pub emoji: String,
    /// What VoiceOver reads for it — the emoji's name.
    pub label: String,
    /// The GUID to send under, or `None` for a fresh one — see [`OutgoingText::guid`].
    pub guid: Option<String>,
}

/// The app a sticker made from an emoji is attributed to.
const STICKER_APP_BUNDLE: &str =
    "com.apple.messages.MSMessageExtensionBalloonPlugin:0000000000:com.apple.Stickers.UserGenerated.MessagesExtension";

fn sticker_placement(ext: &PartExtension) -> StickerPlacement {
    let PartExtension::Sticker { msg_width, rotation, scale, normalized_x, normalized_y, effect_type, .. } = ext;
    StickerPlacement {
        x: *normalized_x,
        y: *normalized_y,
        scale: *scale,
        rotation: *rotation,
        bubble_width: *msg_width,
        effect_type: *effect_type,
    }
}

// ---------------------------------------------------------------------------
// Inbound events
// ---------------------------------------------------------------------------

#[derive(uniffi::Record, Clone)]
pub struct AttachmentInfo {
    pub name: String,
    pub mime: String,
    pub uti_type: String,
    pub size: u64,
    /// Which part of the message this attachment occupies.
    pub part: u64,
    /// Opaque handle to pass back to [`download_attachment`]; the bytes are not fetched with the
    /// message, only their location and decryption key.
    pub token: String,
    /// Set when this is a Genmoji — one of Apple's adaptive image glyphs — rather than a picture
    /// somebody sent: the sender's short description of it, empty when none came with it. It is a
    /// piece of the sentence and is drawn like an emoji, not filed as an attachment. See rustpush's
    /// `genmoji_of` for what marks one on the wire.
    pub genmoji: Option<String>,
}

/// Everything that can arrive from Apple, flattened for the syncer to write straight into Room.
#[derive(uniffi::Enum)]
pub enum IncomingEvent {
    Text {
        guid: String,
        chat: ChatTarget,
        sender: String,
        text: String,
        subject: Option<String>,
        reply_to_guid: Option<String>,
        reply_to_part: Option<String>,
        effect: Option<String>,
        attachments: Vec<AttachmentInfo>,
        /// The Apple part containing the text/caption.
        text_part: u64,
        /// The text broken into formatted stretches, or empty where none of it is styled.
        ///
        /// Concatenated these equal `text`, which is still what the app stores and searches. This
        /// is only how it is dressed, and a message with nothing on it carries none of these at
        /// all rather than one plain run per part.
        runs: Vec<TextRunSpec>,
        /// The rich link card the sender attached for the link in `text`, if any.
        link_preview: Option<IncomingLinkPreview>,
        /// True when RustPush could not authenticate the sender's signing identity.
        verification_failed: bool,
        /// Milliseconds since the Unix epoch.
        timestamp: u64,
    },
    /// A third-party balloon message — a GamePigeon-style game invite or turn.
    ///
    /// Drawn by the matching installed iMessage extension; a client without it shows [`ld_text`].
    /// [`url`] and [`session`] are opaque and must be stored and echoed back verbatim.
    AppMessage {
        guid: String,
        /// For a turn that updates a bubble already in the thread, the guid of the message that
        /// opened it — Apple's `amk` association target. `None` for the first bubble of an
        /// exchange, which is a message in its own right.
        ///
        /// A client that ignores this shows every turn as a fresh invite instead of updating the
        /// one bubble in place, which is not how the exchange reads on Apple's own clients.
        updates_guid: Option<String>,
        chat: ChatTarget,
        sender: String,
        /// Balloon-plugin bundle id, e.g. `com.apple.messages.MSMessageExtensionBalloonPlugin:EWFNLB79LQ:com.gamerdelights.gamepigeon.ext`.
        bundle_id: String,
        /// Extension display name, or empty when the payload did not name one.
        app_name: String,
        /// Human-readable summary shown when no plugin can draw the bubble.
        ld_text: String,
        /// Opaque payload — pass back verbatim.
        url: String,
        session: Option<String>,
        caption: Option<String>,
        subcaption: Option<String>,
        image_title: Option<String>,
        image_subtitle: Option<String>,
        secondary_subcaption: Option<String>,
        tertiary_subcaption: Option<String>,
        /// Whether the bubble updates in place as the exchange continues.
        is_live: bool,
        reply_to_guid: Option<String>,
        reply_to_part: Option<String>,
        /// True when RustPush could not authenticate the sender's signing identity.
        verification_failed: bool,
        /// Milliseconds since the Unix epoch.
        timestamp: u64,
        /// Preview image attachment sent alongside the balloon.
        attachments: Vec<AttachmentInfo>,
    },
    Tapback {
        guid: String,
        chat: ChatTarget,
        sender: String,
        target_guid: String,
        target_part: u64,
        kind: TapbackKind,
        enable: bool,
        timestamp: u64,
    },
    /// A sticker placed on a message — Apple's sticker tapback (`amt` 2007, removed with 3007).
    ///
    /// Its own event rather than a [`TapbackKind`]: a sticker is a picture, delivered as attachment
    /// parts that have to be downloaded, not a glyph. Removal carries no attachments.
    Sticker {
        guid: String,
        chat: ChatTarget,
        sender: String,
        target_guid: String,
        target_part: u64,
        attachments: Vec<AttachmentInfo>,
        /// Where on the bubble it sits; `None` when the sender gave no position.
        placement: Option<StickerPlacement>,
        enable: bool,
        timestamp: u64,
    },
    Edit {
        sender: String,
        target_guid: String,
        target_part: u64,
        new_text: String,
        timestamp: u64,
    },
    Unsend {
        sender: String,
        target_guid: String,
        target_part: u64,
        timestamp: u64,
    },
    Typing {
        chat: ChatTarget,
        sender: String,
        active: bool,
    },
    Delivered {
        guid: String,
        sender: String,
        timestamp: u64,
    },
    Read {
        guid: String,
        sender: String,
        timestamp: u64,
    },
    /// An audio message of ours was listened to. `guid` is the message; `timestamp` is when.
    Played {
        guid: String,
        sender: String,
        timestamp: u64,
    },
    /// Reports the outcome of one of our own sends, once Apple has answered for every recipient.
    SendConfirm { guid: String, error: Option<String> },
    /// How far one attachment's upload has got, labelled with its `transfer_id`.
    ///
    /// On the same stream as everything else so there is one path into the app rather than a second
    /// callback interface for the sake of a number. Dropped rather than queued when the channel is
    /// full: this fires per chunk, and a stale byte count is worth nothing.
    UploadProgress { transfer_id: String, sent: u64, total: u64 },
    GroupRenamed {
        guid: String,
        chat: ChatTarget,
        sender: String,
        name: String,
        timestamp: u64,
    },
    GroupParticipantsChanged {
        guid: String,
        chat: ChatTarget,
        sender: String,
        participants: Vec<String>,
        timestamp: u64,
    },
    /// A delivery failure Apple reported against a specific message.
    Failed {
        guid: String,
        code: u64,
        message: String,
    },
    /// Somebody we are watching has turned a Focus on or off.
    ///
    /// Not a message and not attached to one: it arrives on its own encrypted presence channel,
    /// which Apple only pushes to devices that have asked for it — see
    /// [`Session::watch_focus_status`]. It says nothing about whether messages will *arrive*, only
    /// whether they will make a sound when they do, which is what "Delivered Quietly" reports.
    FocusStatus {
        /// The handle whose Focus changed, in Apple's URI form.
        handle: String,
        /// True while a Focus is on that this account is not on the list of exceptions for.
        silenced: bool,
        /// Apple's identifier for the Focus itself, e.g. `com.apple.donotdisturb.mode.default`.
        mode: Option<String>,
    },
}

/// The receive side of the session, created once in `connect`.
pub(crate) struct Inbox {
    pub(crate) aps: broadcast::Receiver<APSMessage>,
    pub(crate) local: mpsc::Receiver<IncomingEvent>,
}

// ---------------------------------------------------------------------------
// Conversion
// ---------------------------------------------------------------------------

/// Flatten a message's parts into the plain text the app stores.
///
/// Mentions contribute their display text rather than their URI, so a mention reads naturally in a
/// chat-list preview; the URI is only needed to render the highlight, which this app does not do.
fn parts_text(parts: &MessageParts) -> String {
    parts
        .0
        .iter()
        .filter_map(|p| match &p.part {
            MessagePart::Text(text, _) => Some(text.as_str()),
            MessagePart::Mention(_, text) => Some(text.as_str()),
            MessagePart::Object(_) | MessagePart::Attachment(_) => None,
        })
        .collect()
}

/// The same text, as the formatted stretches it arrived in.
///
/// Empty when every part is plain, which is the overwhelmingly common case and is what lets the
/// app store nothing at all for an ordinary message rather than a run per part. A mention is
/// carried as a plain stretch: its display text is already in `text` and the highlight is not
/// something this app draws.
fn parts_runs(parts: &MessageParts) -> Vec<TextRunSpec> {
    let mut runs: Vec<TextRunSpec> = Vec::new();
    let mut styled = false;
    for part in parts.0.iter() {
        let (text, format) = match &part.part {
            MessagePart::Text(text, format) => (text.as_str(), Some(*format)),
            MessagePart::Mention(_, text) => (text.as_str(), None),
            MessagePart::Object(_) | MessagePart::Attachment(_) => continue,
        };
        if text.is_empty() {
            continue;
        }
        let mut run = TextRunSpec {
            text: text.to_string(),
            bold: false,
            italic: false,
            underline: false,
            strikethrough: false,
            effect: None,
        };
        match format {
            Some(TextFormat::Flags(flags)) => {
                run.bold = flags.bold;
                run.italic = flags.italic;
                run.underline = flags.underline;
                run.strikethrough = flags.strikethrough;
            }
            Some(TextFormat::Effect(effect)) => run.effect = Some(effect as u32),
            None => {}
        }
        if run.bold || run.italic || run.underline || run.strikethrough || run.effect.is_some() {
            styled = true;
        }
        runs.push(run);
    }
    if styled { runs } else { Vec::new() }
}

fn parts_attachments(parts: &MessageParts) -> Vec<AttachmentInfo> {
    parts
        .0
        .iter()
        .filter_map(|p| match &p.part {
            MessagePart::Attachment(attachment) => Some(to_attachment_info(attachment)),
            _ => None,
        })
        .collect()
}

/// An `NSURL` the way `KeyedArchive` spells an absolute one: no base, the whole address relative.
fn ns_url(url: &str) -> rustpush::NSURL {
    rustpush::NSURL { base: "$null".to_string(), relative: url.to_string() }
}

/// The wire form of a link card: the page's metadata, and its picture as balloon attachment 0.
fn link_meta_for(preview: LinkPreviewSpec) -> rustpush::LinkMeta {
    let image_bytes = preview.image.filter(|bytes| !bytes.is_empty());
    let has_image = image_bytes.is_some();
    rustpush::LinkMeta {
        data: rustpush::LPLinkMetadata {
            image_metadata: has_image.then(|| rustpush::LPImageMetadata {
                size: format!("{{{}, {}}}", preview.image_width, preview.image_height),
                url: ns_url(&preview.url),
                version: 1,
            }),
            version: 1,
            icon_metadata: None,
            original_url: Some(ns_url(&preview.url)),
            url: Some(ns_url(&preview.url)),
            title: preview.title,
            summary: preview.summary,
            image: has_image.then(|| rustpush::RichLinkImageAttachmentSubstitute {
                mime_type: preview.image_mime.unwrap_or_else(|| "image/jpeg".to_string()),
                rich_link_image_attachment_substitute_index: 0,
            }),
            icon: None,
            images: None,
            icons: None,
            is_incomplete: None,
            uses_activity_pub: None,
            is_encoded_for_local_use: None,
            collaboration_type: None,
            specialization2: None,
        },
        attachments: image_bytes.into_iter().collect(),
    }
}

/// A received link card, with its picture resolved out of the balloon's attachments.
///
/// The picture is the page's image where it has one and its icon otherwise, so a site that only
/// offers a favicon still draws a card with something on it.
fn incoming_link_preview(meta: &rustpush::LinkMeta) -> IncomingLinkPreview {
    let data = &meta.data;
    let url: String = data
        .url
        .clone()
        .or_else(|| data.original_url.clone())
        .map(Into::into)
        .unwrap_or_default();
    let substitute = data.image.as_ref().or(data.icon.as_ref());
    let image = substitute
        .and_then(|sub| meta.attachments.get(sub.rich_link_image_attachment_substitute_index as usize))
        .cloned();
    IncomingLinkPreview {
        url,
        title: data.title.clone(),
        summary: data.summary.clone(),
        image_mime: image.as_ref().and(substitute).map(|sub| sub.mime_type.clone()),
        image,
    }
}

fn to_attachment_info(attachment: &Attachment) -> AttachmentInfo {
    AttachmentInfo {
        name: attachment.name.clone(),
        mime: attachment.mime.clone(),
        uti_type: attachment.uti_type.clone(),
        size: attachment.get_size() as u64,
        part: attachment.part,
        token: api::save_attachment(attachment),
        genmoji: attachment.genmoji.clone(),
    }
}

/// Translate a decrypted message into an app-level event.
///
/// Returns `None` for the many control messages that are meaningful to the protocol but have
/// nothing to show: peer cache invalidations, SMS activation handshakes, profile sharing, poster
/// updates and so on. Dropping them here keeps the syncer from having to know they exist.
fn text_part(parts: &MessageParts) -> u64 {
    parts
        .0
        .iter()
        .enumerate()
        .find(|(_, part)| matches!(part.part, MessagePart::Text(..) | MessagePart::Mention(..)))
        .map(|(position, part)| part.idx.unwrap_or(position) as u64)
        .unwrap_or(0)
}

/// The six `MSMessageTemplateLayout` strings, each mapped to `None` when the extension left it blank.
///
/// Order: `(caption, subcaption, image_title, image_subtitle, secondary_subcaption, tertiary_subcaption)`.
type LayoutStrings = (
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
);

fn balloon_layout_strings(layout: &Option<BalloonLayout>) -> LayoutStrings {
    match layout {
        Some(BalloonLayout::TemplateLayout {
            caption,
            subcaption,
            image_title,
            image_subtitle,
            secondary_subcaption,
            tertiary_subcaption,
            ..
        }) => (
            Some(caption.clone()).filter(|s| !s.is_empty()),
            Some(subcaption.clone()).filter(|s| !s.is_empty()),
            Some(image_title.clone()).filter(|s| !s.is_empty()),
            Some(image_subtitle.clone()).filter(|s| !s.is_empty()),
            Some(secondary_subcaption.clone()).filter(|s| !s.is_empty()),
            Some(tertiary_subcaption.clone()).filter(|s| !s.is_empty()),
        ),
        _ => (None, None, None, None, None, None),
    }
}

fn to_event(inst: MessageInst, local_handles: &[String]) -> Option<IncomingEvent> {
    // Unverified control payloads must never mutate a conversation. Text is retained so the user
    // can see the content together with its explicit verification warning; edits, unsends,
    // tapbacks and group administration have no safe degraded representation.
    if inst.verification_failed && !matches!(&inst.message, Message::Message(_)) {
        return None;
    }
    let guid = inst.id.clone();
    let timestamp = inst.sent_timestamp;
    let sender = inst.sender.clone().unwrap_or_default();
    let chat = inst
        .conversation
        .as_ref()
        .map(|c| to_chat_target(c, inst.sender.as_ref(), local_handles));
    let verification_failed = inst.verification_failed;

    match inst.message {
        Message::Message(message) => {
            // A balloon-plugin message (GamePigeon and the like) carries its bubble in `app`
            // rather than in text. Surface it as its own event so the app can store the payload
            // and hand it to the matching extension, instead of the object-replacement text that
            // would otherwise land in the thread.
            if let Some(app) = message.app.as_ref().filter(|a| a.balloon.is_some()) {
                let balloon = app.balloon.as_ref().unwrap();
                let (caption, subcaption, image_title, image_subtitle, secondary_subcaption, tertiary_subcaption) =
                    balloon_layout_strings(&balloon.layout);
                return Some(IncomingEvent::AppMessage {
                    guid,
                    updates_guid: None,
                    chat: chat?,
                    sender,
                    bundle_id: app.bundle_id.clone(),
                    app_name: app.name.clone(),
                    ld_text: balloon.ld_text.clone().unwrap_or_default(),
                    url: balloon.url.clone(),
                    session: balloon.session.clone(),
                    caption,
                    subcaption,
                    image_title,
                    image_subtitle,
                    secondary_subcaption,
                    tertiary_subcaption,
                    is_live: balloon.is_live,
                    reply_to_guid: message.reply_guid.clone(),
                    reply_to_part: message.reply_part.clone(),
                    verification_failed,
                    timestamp,
                    attachments: parts_attachments(&message.parts),
                });
            }
            let link_preview = message.link_meta.as_ref().map(incoming_link_preview);
            Some(IncomingEvent::Text {
                guid,
                chat: chat?,
                sender,
                text: parts_text(&message.parts),
                subject: message.subject,
                reply_to_guid: message.reply_guid,
                reply_to_part: message.reply_part,
                effect: message.effect,
                attachments: parts_attachments(&message.parts),
                text_part: text_part(&message.parts),
                runs: parts_runs(&message.parts),
                link_preview,
                verification_failed,
                timestamp,
            })
        }

        Message::React(react) => {
            // GamePigeon and other iMessage app messages arrive as an `amt=2` association that
            // carries an `ExtensionApp`, *not* as a normal message — so a game turn from an iPhone
            // lands here, not in the `Message::Message` arm above. Without this it was dropped, and
            // the conversation showed nothing when the other player moved.
            // Only a balloon is an app message. A sticker placed on a bubble is an extension with no
            // balloon, and handled below — letting it in here dropped it on the `balloon?` and it
            // never showed up.
            if let ReactMessageType::Extension { spec: spec @ ExtensionApp { balloon: Some(_), .. }, body, is_meta } = &react.reaction {
                // A meta update is a silent state sync for a bubble that already exists, not a new
                // turn to show.
                if *is_meta {
                    return None;
                }
                let balloon = spec.balloon.as_ref()?;
                let (caption, subcaption, image_title, image_subtitle, secondary_subcaption, tertiary_subcaption) =
                    balloon_layout_strings(&balloon.layout);
                return Some(IncomingEvent::AppMessage {
                    // The turn is its own message with its own guid; `to_uuid` names the bubble it
                    // updates. Using the target's guid here made every turn collide with the row
                    // that opened the game, so an opponent's move landed nowhere.
                    guid,
                    updates_guid: Some(react.to_uuid.clone()),
                    chat: chat?,
                    sender,
                    bundle_id: spec.bundle_id.clone(),
                    app_name: spec.name.clone(),
                    ld_text: balloon.ld_text.clone().unwrap_or_default(),
                    url: balloon.url.clone(),
                    session: balloon.session.clone(),
                    caption,
                    subcaption,
                    image_title,
                    image_subtitle,
                    secondary_subcaption,
                    tertiary_subcaption,
                    is_live: balloon.is_live,
                    reply_to_guid: None,
                    reply_to_part: None,
                    verification_failed,
                    timestamp,
                    // A turn that re-renders the board ships a fresh preview image with it.
                    attachments: parts_attachments(body),
                });
            }
            // A sticker is a picture laid on a message, sent as a reaction whose body is attachment
            // parts. It used to fall through to `from_reaction`, which has no glyph for one, and was
            // dropped — the sticker simply never appeared on this side.
            if let ReactMessageType::React { reaction: Reaction::Sticker { body, spec }, enable } = &react.reaction {
                let placement = body.0.iter().find_map(|p| p.ext.as_ref()).map(sticker_placement);
                // What an Apple sticker says about itself, so what we send can be checked against it.
                log::info!(
                    "sticker from {sender}: app={:?} placement={placement:?} raw={:?}",
                    spec.as_ref().map(|s| &s.bundle_id),
                    body.0.iter().find_map(|p| p.ext.as_ref()).map(|e| {
                        let PartExtension::Sticker { sai, sli, version, safi, sticker_id, hash, .. } = e;
                        format!("sai={sai} sli={sli} spv={version} safi={safi} sid={sticker_id} shash={hash}")
                    }),
                );
                return Some(IncomingEvent::Sticker {
                    guid,
                    chat: chat?,
                    sender,
                    target_guid: react.to_uuid.clone(),
                    target_part: react.to_part.unwrap_or(0),
                    attachments: parts_attachments(body),
                    placement,
                    enable: *enable,
                    timestamp,
                });
            }
            // A sticker placed on a bubble: `amt` 1000, a non-balloon extension message whose body
            // is the picture with its placement. Dropped before, so none ever showed up here.
            if let ReactMessageType::Extension { spec: ExtensionApp { balloon: None, bundle_id, name, .. }, body, .. } = &react.reaction {
                if body.has_attachments() {
                    let placement = body.0.iter().find_map(|p| p.ext.as_ref()).map(sticker_placement);
                    log::info!("placed sticker from {sender}: app={bundle_id:?} name={name:?} placement={placement:?}");
                    return Some(IncomingEvent::Sticker {
                        guid,
                        chat: chat?,
                        sender,
                        target_guid: react.to_uuid.clone(),
                        target_part: react.to_part.unwrap_or(0),
                        attachments: parts_attachments(body),
                        placement,
                        enable: true,
                        timestamp,
                    });
                }
            }
            let (kind, enable) = match &react.reaction {
                ReactMessageType::React { reaction, enable } => {
                    (TapbackKind::from_reaction(reaction)?, *enable)
                }
                // Anything else without a balloon is handled above as a placed sticker.
                ReactMessageType::Extension { .. } => return None,
            };
            Some(IncomingEvent::Tapback {
                guid,
                chat: chat?,
                sender,
                target_guid: react.to_uuid,
                target_part: react.to_part.unwrap_or(0),
                kind,
                enable,
                timestamp,
            })
        }

        Message::Edit(edit) => Some(IncomingEvent::Edit {
            sender,
            target_guid: edit.tuuid,
            target_part: edit.edit_part,
            new_text: parts_text(&edit.new_parts),
            timestamp,
        }),

        Message::Unsend(unsend) => Some(IncomingEvent::Unsend {
            sender,
            target_guid: unsend.tuuid,
            target_part: unsend.edit_part,
            timestamp,
        }),

        Message::Typing(active, _app) => Some(IncomingEvent::Typing {
            chat: chat?,
            sender,
            active,
        }),

        Message::Delivered => Some(IncomingEvent::Delivered {
            guid,
            sender,
            timestamp,
        }),
        Message::Read => Some(IncomingEvent::Read {
            guid,
            sender,
            timestamp,
        }),
        Message::Played => Some(IncomingEvent::Played {
            guid,
            sender,
            timestamp,
        }),

        Message::RenameMessage(rename) => Some(IncomingEvent::GroupRenamed {
            guid,
            chat: chat?,
            sender,
            name: rename.new_name,
            timestamp,
        }),

        Message::ChangeParticipants(change) => Some(IncomingEvent::GroupParticipantsChanged {
            guid,
            chat: chat?,
            sender,
            participants: change.new_participants,
            timestamp,
        }),

        Message::Error(error) => Some(IncomingEvent::Failed {
            guid: error.for_uuid,
            code: error.status,
            message: error.status_str,
        }),

        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Session methods
// ---------------------------------------------------------------------------

impl Session {
    /// Acknowledge a successfully decrypted inbound message before exposing it to Kotlin.
    async fn acknowledge(&self, client: &rustpush::IMClient, inst: &MessageInst) -> Result<()> {
        if let Some(context) = &inst.certified_context {
            client
                .identity
                .certify_delivery("com.apple.madrid", context, inst.send_delivered)
                .await?;
            return Ok(());
        }
        if !inst.send_delivered {
            return Ok(());
        }

        let Some(remote) = inst.sender.as_ref() else {
            return Ok(());
        };
        let Some(source) = inst.conversation.as_ref() else {
            return Ok(());
        };
        let handles = client.identity.get_handles().await;
        let Some(local) = source
            .participants
            .iter()
            .find(|participant| handles.contains(participant) && *participant != remote)
            .or_else(|| handles.first())
        else {
            return Err(ImError::msg(
                "No local handle is available for a delivered receipt",
            ));
        };

        // Conventional receipts are addressed only to the originating device. Preserve the
        // inbound target token, as OpenBubbles does, rather than broadcasting to every device.
        let mut receipt = MessageInst::new(
            ConversationData {
                participants: vec![remote.clone()],
                cv_name: source.cv_name.clone(),
                sender_guid: source.sender_guid.clone(),
                after_guid: None,
            },
            local,
            Message::Delivered,
        );
        receipt.id = inst.id.clone();
        receipt.target = inst.target.clone();
        client.send(&mut receipt).await?;
        Ok(())
    }

    /// Offer a push to the presence client, and translate whatever it makes of it.
    ///
    /// Most pushes are not presence at all and this returns `None` for them in a topic comparison.
    /// The ones that are come in two shapes: a status change on somebody's channel, which is the
    /// event, and the key sharing that lets us read that channel at all, which is bookkeeping the
    /// client does to its own stored state and has nothing to report about.
    ///
    /// Errors are swallowed rather than surfaced. A status nobody can decrypt — a channel we hold
    /// stale keys for, a ratchet we have fallen behind on — must not stop the messages queued
    /// behind it, and there is nothing the app could usefully do about it if told.
    async fn focus_change(&self, message: APSMessage) -> Option<IncomingEvent> {
        let presence = self.presence().await?;
        let shared_before = presence.shared_handles().await;
        let handled = presence.handle(message).await;
        // A key share is the moment somebody's Focus becomes readable at all, and the subscription
        // taken at connect could not have named a channel that did not exist yet. Without this the
        // first status anyone ever shares with us is only heard after the next reconnect.
        if matches!(handled, Ok(None)) {
            let shared_after = presence.shared_handles().await;
            let fresh = shared_after
                .into_iter()
                .filter(|h| !shared_before.contains(h))
                .collect::<Vec<_>>();
            if !fresh.is_empty() {
                info!("focus: {} new channel(s) shared with us, resubscribing", fresh.len());
                let mut handles = self.focus_handles.lock().await.clone();
                for handle in fresh {
                    if !handles.iter().any(|h| same_handle(h, &handle)) {
                        handles.push(handle);
                    }
                }
                if let Err(err) = self.watch_focus_status(handles).await {
                    warn!("failed to resubscribe to focus channels: {err}");
                }
            }
        }
        match handled {
            Ok(Some(StatusKitMessage::StatusChanged { user, mode, allowed })) => {
                Some(IncomingEvent::FocusStatus {
                    handle: user,
                    // Apple's flag names the courtesy, not the restriction: `allowed` is true when
                    // this account is on the exception list for whatever Focus is running, or when
                    // no Focus is running at all.
                    silenced: !allowed,
                    mode,
                })
            }
            Ok(None) => None,
            Err(err) => {
                warn!("failed to read a presence push: {err}");
                None
            }
        }
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl Session {
    /// Suspend until the next event arrives; `None` means the connection is finished.
    ///
    /// Kotlin drives this from a `flow { }` loop rather than us calling into a foreign callback
    /// trait. That keeps backpressure natural — nothing is decrypted until the previous event has
    /// been consumed — and means the whole receive side is an ordinary cancellable `Flow`.
    pub async fn next_event(&self) -> Option<IncomingEvent> {
        let client = self.require_client().await.ok()?;
        let mut inbox = self.inbox.lock().await;
        // Split the guard so the two receivers can be polled together; `select!` needs a distinct
        // mutable borrow of each, which it cannot get through the guard itself.
        let Inbox { aps, local } = &mut *inbox;

        loop {
            tokio::select! {
                local = local.recv() => return local,
                aps = aps.recv() => {
                    let message = match aps {
                        Ok(message) => message,
                        // A slow consumer only ever loses control traffic worth re-deriving, so
                        // keep going rather than tearing the connection down.
                        Err(broadcast::error::RecvError::Lagged(skipped)) => {
                            warn!("push consumer lagged, dropped {skipped} messages");
                            continue;
                        }
                        Err(broadcast::error::RecvError::Closed) => return None,
                    };

                    // Presence first, on a clone: a Focus change rides its own encrypted channel
                    // and the key-sharing that sets that channel up rides IDS topics the message
                    // client has never heard of. Handed to `client.handle` instead, both would be
                    // logged as undecryptable and thrown away.
                    if let Some(status) = self.focus_change(message.clone()).await {
                        return Some(status);
                    }

                    match client.handle(message).await {
                        Ok(Some(inst)) => {
                            // Receipt failures must not hide a message that was already decrypted,
                            // but they are logged so reconnection/Apple redelivery remains visible.
                            if let Err(err) = self.acknowledge(&client, &inst).await {
                                warn!("failed to acknowledge {}: {err}", inst.id);
                            }
                            let handles = client.identity.get_handles().await;
                            if let Some(event) = to_event(inst, &handles) {
                                return Some(event);
                            }
                        }
                        Ok(None) => {}
                        // A message we cannot decrypt or parse must not stop the ones behind it.
                        Err(err) => warn!("failed to handle push: {err}"),
                    }
                }
            }
        }
    }

    pub async fn send_text(&self, message: OutgoingText) -> Result<String> {
        let mut normal = NormalMessage::new(message.text.clone(), MessageType::IMessage);
        normal.subject = message.subject;
        normal.reply_guid = message.reply_to_guid;
        normal.reply_part = message.reply_to_part;
        normal.effect = message.effect;
        normal.voice = message.voice;
        if let Some(preview) = message.link_preview {
            normal.link_meta = Some(link_meta_for(preview));
        }

        // Rebuilt from the runs whenever there are any: `NormalMessage::new` above lays the text
        // down as a single unformatted part, which is right for the ordinary case and is exactly
        // what has to be replaced when the text is styled.
        let text = text_parts(&message.text, &message.runs);
        if !message.attachments.is_empty() {
            let mut parts = Vec::with_capacity(message.attachments.len() + text.len());

            // Attachments lead and any caption follows, which is the order iMessage renders them in.
            for attachment in message.attachments {
                parts.push(IndexedMessagePart {
                    part: MessagePart::Attachment(self.upload(attachment, message.guid.as_deref()).await?),
                    idx: None,
                    ext: None,
                });
            }

            parts.extend(text);
            normal.parts = MessageParts(parts);
        } else if !message.runs.is_empty() {
            normal.parts = MessageParts(text);
        }

        self.dispatch_as(message.chat, Message::Message(normal), message.guid).await
    }

    /// Send an iMessage app / balloon message — see [`OutgoingAppMessage`].
    ///
    /// The parts mirror what Apple's own clients put on the wire for an extension message: the
    /// `ld_text` as an object part so a plugin-less client has something to show, then the preview
    /// image (if any) as an ordinary attachment. The rich bubble itself rides in `NormalMessage.app`.
    pub async fn send_app_message(&self, message: OutgoingAppMessage) -> Result<String> {
        let mut normal = NormalMessage::new(String::new(), MessageType::IMessage);
        normal.reply_guid = message.reply_to_guid;
        normal.reply_part = message.reply_to_part;

        let layout = BalloonLayout::TemplateLayout {
            image_subtitle: message.image_subtitle.unwrap_or_default(),
            image_title: message.image_title.unwrap_or_default(),
            caption: message.caption.unwrap_or_default(),
            secondary_subcaption: message.secondary_subcaption.unwrap_or_default(),
            tertiary_subcaption: message.tertiary_subcaption.unwrap_or_default(),
            subcaption: message.subcaption.unwrap_or_default(),
            class: NSDictionaryClass::NSDictionary,
        };

        normal.app = Some(ExtensionApp {
            name: message.app_name,
            app_id: message.app_id,
            bundle_id: message.bundle_id,
            balloon: Some(Balloon {
                url: message.url,
                session: message.session,
                layout: Some(layout),
                ld_text: Some(message.ld_text.clone()),
                is_live: message.is_live,
                icon: message.icon,
            }),
        });

        // `NormalMessage::new` seeds one empty text part; replace it with the object part (and the
        // preview attachment, when there is one) that an extension message actually carries.
        let mut parts = vec![IndexedMessagePart {
            part: MessagePart::Object(message.ld_text),
            idx: None,
            ext: None,
        }];
        if let Some(preview) = message.preview {
            parts.push(IndexedMessagePart {
                part: MessagePart::Attachment(self.upload(preview, None).await?),
                idx: None,
                ext: None,
            });
        }
        normal.parts = MessageParts(parts);

        self.dispatch(message.chat, Message::Message(normal)).await
    }

    /// Send a further turn on an existing balloon — see [`OutgoingAppUpdate`].
    ///
    /// Unlike [`send_app_message`](Self::send_app_message) this is an *association*, not a new
    /// message: Apple carries a continuation as an `amt=2` react against the bubble that opened the
    /// exchange, so the receiving client updates that bubble rather than adding another one. The
    /// body it carries is the same object-part-then-preview pair a first turn uses.
    pub async fn send_app_update(&self, update: OutgoingAppUpdate) -> Result<String> {
        let layout = BalloonLayout::TemplateLayout {
            image_subtitle: update.image_subtitle.unwrap_or_default(),
            image_title: update.image_title.unwrap_or_default(),
            caption: update.caption.unwrap_or_default(),
            secondary_subcaption: update.secondary_subcaption.unwrap_or_default(),
            tertiary_subcaption: update.tertiary_subcaption.unwrap_or_default(),
            subcaption: update.subcaption.unwrap_or_default(),
            class: NSDictionaryClass::NSDictionary,
        };

        let spec = ExtensionApp {
            name: update.app_name,
            app_id: update.app_id,
            bundle_id: update.bundle_id,
            balloon: Some(Balloon {
                url: update.url,
                session: update.session,
                layout: Some(layout),
                ld_text: Some(update.ld_text.clone()),
                is_live: update.is_live,
                icon: update.icon,
            }),
        };

        let mut parts = vec![IndexedMessagePart {
            part: MessagePart::Object(update.ld_text),
            idx: None,
            ext: None,
        }];
        if let Some(preview) = update.preview {
            parts.push(IndexedMessagePart {
                part: MessagePart::Attachment(self.upload(preview, None).await?),
                idx: None,
                ext: None,
            });
        }

        let react = ReactMessage {
            to_uuid: update.target_guid,
            // A balloon update addresses the whole message, not one part of it — a part index here
            // would turn `amk` into `p:<n>/<guid>`, which is not what the association wants.
            to_part: None,
            to_text: update.notification_text,
            reaction: ReactMessageType::Extension {
                spec,
                body: MessageParts(parts),
                is_meta: false,
            },
            embedded_profile: None,
        };

        self.dispatch(update.chat, Message::React(react)).await
    }

    pub async fn send_tapback(&self, tapback: OutgoingTapback) -> Result<String> {
        let react = ReactMessage {
            to_uuid: tapback.target_guid,
            to_part: Some(tapback.target_part),
            reaction: ReactMessageType::React {
                reaction: tapback.kind.to_reaction(),
                enable: tapback.enable,
            },
            to_text: tapback.target_text,
            embedded_profile: None,
        };

        self.dispatch(tapback.chat, Message::React(react)).await
    }

    /// Put a sticker on a message — Apple's placed sticker, `amt` 1000.
    ///
    /// The picture goes up to MMCS like any attachment and rides in the reaction's body as its one
    /// FILE part, with the placement as that part's sticker attributes.
    pub async fn send_sticker(&self, sticker: OutgoingSticker) -> Result<String> {
        let placement = sticker.placement;
        // Everything below is what an iPhone sends for an emoji sticker; one missing any of it is
        // delivered and then silently not shown.
        let sid = format!("{}-sticker.png", sticker.hash);
        {
            let mut extras = rustpush::STICKER_EXTRAS.lock().unwrap();
            extras.file_attrs.insert(sid.clone(), vec![
                ("pid".to_string(), STICKER_APP_BUNDLE.to_string()),
                ("suri".to_string(), format!("sticker:///emoji/identifier/{}", sticker.emoji)),
            ]);
            let mut size = plist::Dictionary::new();
            size.insert("mpw".into(), plist::Value::String("731.000000".into()));
            size.insert("gm".into(), plist::Value::Boolean(false));
            size.insert("mth".into(), plist::Value::String("150.000000".into()));
            size.insert("mtw".into(), plist::Value::String("150.000000".into()));
            size.insert("iaig".into(), plist::Value::Boolean(false));
            size.insert("st".into(), plist::Value::Boolean(false));
            size.insert("s".into(), plist::Value::String("3.000000".into()));
            size.insert("$class".into(), plist::Value::String("NSMutableDictionary".into()));
            let mut ati = plist::Dictionary::new();
            ati.insert("accessl".into(), plist::Value::String(sticker.label.clone()));
            ati.insert("pgensw".into(), plist::Value::Real(320.0));
            ati.insert("pgensh".into(), plist::Value::Real(320.0));
            ati.insert("pgenszc".into(), plist::Value::Dictionary(size));
            extras.ati.insert(STICKER_APP_BUNDLE.to_string(), ati);
        }
        let ext = PartExtension::Sticker {
            msg_width: placement.bubble_width,
            rotation: placement.rotation,
            sai: 0,
            scale: placement.scale,
            update: None,
            sli: 0,
            normalized_x: placement.x,
            normalized_y: placement.y,
            version: 0,
            hash: sticker.hash,
            safi: 0,
            effect_type: -1,
            sticker_id: sid,
        };
        let body = MessageParts(vec![IndexedMessagePart {
            part: MessagePart::Attachment(self.upload(sticker.image, sticker.guid.as_deref()).await?),
            idx: None,
            ext: Some(ext),
        }]);
        // A sticker slapped onto a bubble is not a tapback (that is `amt` 2007, a sticker shown in
        // the tapback pill): it is a non-balloon extension message, `amt` 1000, naming the sticker
        // app that made it. Sent as a 2007 it was delivered and silently ignored.
        let react = ReactMessage {
            to_uuid: sticker.target_guid,
            to_part: Some(sticker.target_part),
            reaction: ReactMessageType::Extension {
                spec: ExtensionApp {
                    name: "Stickers".to_string(),
                    app_id: None,
                    bundle_id: STICKER_APP_BUNDLE.to_string(),
                    balloon: None,
                },
                body,
                is_meta: false,
            },
            to_text: sticker.target_text,
            embedded_profile: None,
        };
        self.dispatch_as(sticker.chat, Message::React(react), sticker.guid).await
    }

    /// Replace the text of a message already sent. Only our own messages can be edited.
    pub async fn edit_message(
        &self,
        chat: ChatTarget,
        target_guid: String,
        target_part: u64,
        new_text: String,
    ) -> Result<String> {
        let edit = EditMessage {
            tuuid: target_guid,
            edit_part: target_part,
            new_parts: MessageParts(vec![IndexedMessagePart {
                part: MessagePart::Text(new_text, Default::default()),
                idx: None,
                ext: None,
            }]),
        };

        self.dispatch(chat, Message::Edit(edit)).await
    }

    /// Retract a message for everyone.
    pub async fn unsend_message(
        &self,
        chat: ChatTarget,
        target_guid: String,
        target_part: u64,
    ) -> Result<String> {
        let unsend = UnsendMessage {
            tuuid: target_guid,
            edit_part: target_part,
        };
        self.dispatch(chat, Message::Unsend(unsend)).await
    }

    pub async fn set_typing(&self, chat: ChatTarget, active: bool) -> Result<String> {
        self.dispatch(chat, Message::Typing(active, None)).await
    }

    /// Ask Apple to push us Focus changes for `handles`, and stop pushing them for anyone else.
    ///
    /// The whole set every time rather than one at a time: interest is held by a token whose drop
    /// unsubscribes, so replacing it with a token for exactly the people the app cares about now is
    /// both the subscribe and the unsubscribe. Handles we hold no shared key for are simply not in
    /// the resulting subscription — a Focus status is only visible to people it has been shared
    /// with, and this is where that comes out as silence rather than as an error.
    ///
    /// Does nothing at all before registration, or on an install with no stored credential.
    pub async fn watch_focus_status(&self, handles: Vec<String>) -> Result<()> {
        let Some(presence) = self.presence().await else {
            return Ok(());
        };
        *self.focus_handles.lock().await = handles.clone();
        // Channels are keyed by the handle exactly as the sharer's phone spelled it — nearly always
        // `tel:+E164` — while a conversation can have stored the same person as `tel:5551234567`.
        // Matched on the person instead, then asked for in Apple's spelling.
        let shared = presence.shared_handles().await;
        let mut wanted = shared
            .iter()
            .filter(|from| handles.iter().any(|h| same_handle(h, from)))
            .cloned()
            .collect::<Vec<_>>();
        info!(
            "focus: watching {} of {} handles ({} channels shared with us)",
            wanted.len(),
            handles.len(),
            shared.len()
        );
        wanted.extend(handles);
        wanted.sort();
        wanted.dedup();
        let token = api::request_handles(&presence, wanted).await?;
        // Assigned rather than dropped-then-assigned: the old token's unsubscribe goes out as it
        // falls out of this slot, after the new interest has already been registered.
        *self.focus_interest.lock().await = token;
        Ok(())
    }

    /// Break through the recipient's Focus for one message they have already been sent quietly.
    ///
    /// Carries the id of that message rather than one of its own, the way a receipt does: it is not
    /// a new thing in the conversation, it is a second opinion about something already in it.
    pub async fn notify_anyway(&self, chat: ChatTarget, target_guid: String) -> Result<()> {
        let client = self.require_client().await?;
        let chat = self.resolve_sender(&client, chat).await;
        let sender = chat.sender.clone();
        let conversation: ConversationData = chat.into();

        let mut inst = MessageInst::new(conversation, &sender, Message::NotifyAnyways);
        inst.id = target_guid;
        client.send(&mut inst).await?;
        Ok(())
    }

    /// Tell the sender their message was read.
    pub async fn mark_read(&self, chat: ChatTarget, target_guid: String) -> Result<()> {
        let client = self.require_client().await?;
        let chat = self.resolve_sender(&client, chat).await;
        // Taken before the conversion, which consumes `chat`. A receipt has to be attributed to our
        // own handle; the first participant is whoever Apple happened to list first, which is
        // usually the other person.
        let sender = chat.sender.clone();
        let conversation: ConversationData = chat.into();

        let mut inst = MessageInst::new(conversation, &sender, Message::Read);
        // A read receipt names the message it acknowledges rather than being a message in its own
        // right, so it carries the target's id instead of a fresh one.
        inst.id = target_guid;
        client.send(&mut inst).await?;
        Ok(())
    }

    /// Tell the sender their audio message was listened to.
    ///
    /// Command 105, shaped exactly like a read receipt: no body, and the envelope names the message
    /// that was played rather than carrying a fresh id of its own.
    pub async fn mark_played(&self, chat: ChatTarget, target_guid: String) -> Result<()> {
        let client = self.require_client().await?;
        let chat = self.resolve_sender(&client, chat).await;
        let sender = chat.sender.clone();
        let conversation: ConversationData = chat.into();

        let mut inst = MessageInst::new(conversation, &sender, Message::Played);
        inst.id = target_guid;
        client.send(&mut inst).await?;
        Ok(())
    }

    pub async fn rename_group(&self, chat: ChatTarget, name: String) -> Result<String> {
        self.dispatch(
            chat,
            Message::RenameMessage(RenameMessage { new_name: name }),
        )
        .await
    }

    /// Replace a group's participant list, which is how members are both added and removed.
    ///
    /// `group_version` must increase with each change; Apple uses it to order concurrent edits.
    pub async fn set_participants(
        &self,
        chat: ChatTarget,
        participants: Vec<String>,
        group_version: u64,
    ) -> Result<String> {
        self.dispatch(
            chat,
            Message::ChangeParticipants(ChangeParticipantMessage {
                new_participants: participants,
                group_version,
            }),
        )
        .await
    }
}

/// What a finished upload is remembered by — see [`Session::upload`].
#[derive(PartialEq, Eq, Hash)]
pub(crate) struct UploadKey {
    guid: String,
    path: String,
    len: u64,
    modified: Option<SystemTime>,
}

pub(crate) type Uploads = HashMap<UploadKey, (Instant, Attachment)>;

/// How long a finished upload is kept for the message it belongs to. Long enough to outlast every
/// retry of one send; nowhere near as long as Apple keeps the file.
const UPLOAD_REUSE: Duration = Duration::from_secs(60 * 60);

impl Session {
    /// Upload a file to Apple's MMCS and describe it as a message part.
    ///
    /// Two passes over the file: the first hashes and chunks it to build the manifest Apple wants
    /// up front, the second streams the bytes. Hence the rewind between them — `prepare_put` leaves
    /// the handle at the end.
    ///
    /// `guid` is the message the file is going out in, where the caller chose one. An upload that
    /// finished is remembered under it, so the same message tried again — which is what a failed
    /// send becomes — sends the file it already uploaded rather than uploading it a second time.
    async fn upload(&self, attachment: OutgoingAttachment, guid: Option<&str>) -> Result<Attachment> {
        let mut file = std::fs::File::open(&attachment.path)?;

        // The file's size and age are part of the key, so a path that has been written to since is
        // a different file as far as this is concerned.
        let meta = file.metadata()?;
        let key = guid.filter(|g| !g.is_empty()).map(|g| UploadKey {
            guid: g.to_string(),
            path: attachment.path.clone(),
            len: meta.len(),
            modified: meta.modified().ok(),
        });
        if let Some(key) = &key {
            let mut uploads = self.uploads.lock().unwrap();
            uploads.retain(|_, (at, _)| at.elapsed() < UPLOAD_REUSE);
            if let Some((_, done)) = uploads.get(key) {
                info!("reusing the finished upload of {}", attachment.name);
                if let Some(id) = attachment.transfer_id.clone() {
                    let total = done.get_size() as u64;
                    let _ = self.local_tx.try_send(IncomingEvent::UploadProgress { transfer_id: id, sent: total, total });
                }
                return Ok(done.clone());
            }
        }

        let prepared = MMCSFile::prepare_put(&mut file).await?;
        file.rewind()?;

        // `try_send` rather than an await: the callback is synchronous and runs inside the upload's
        // own loop, so blocking it to report a number would be throttling the transfer to the speed
        // of the UI reading about it. A full channel means the app is already behind on progress it
        // is about to be told again anyway, so the drop costs nothing.
        let transfer_id = attachment.transfer_id.clone();
        let local = self.local_tx.clone();
        let progress = move |sent: usize, total: usize| {
            if let Some(id) = transfer_id.as_ref() {
                let _ = local.try_send(IncomingEvent::UploadProgress {
                    transfer_id: id.clone(),
                    sent: sent as u64,
                    total: total as u64,
                });
            }
        };

        let uploaded = Attachment::new_mmcs(
            &self.conn,
            &prepared,
            file,
            &attachment.mime,
            &attachment.uti,
            &attachment.name,
            progress,
        )
        .await?;
        if let Some(key) = key {
            self.uploads.lock().unwrap().insert(key, (Instant::now(), uploaded.clone()));
        }
        Ok(uploaded)
    }

    /// Build, send, and arrange for the eventual delivery outcome to surface as an event.
    ///
    /// `IMClient::send` returns once the message is on its way; whether every recipient accepted it
    /// is only known later. Rather than block on that, the join handle is watched on a task that
    /// posts a [`IncomingEvent::SendConfirm`] into the same stream the UI is already consuming, so
    /// an optimistic bubble can settle into sent or failed without a second code path.
    async fn dispatch(&self, chat: ChatTarget, message: Message) -> Result<String> {
        self.dispatch_as(chat, message, None).await
    }

    /// [`dispatch`](Self::dispatch), under a GUID the caller chose — see [`OutgoingText::guid`].
    async fn dispatch_as(&self, chat: ChatTarget, message: Message, guid: Option<String>) -> Result<String> {
        let client = self.require_client().await?;
        let chat = self.resolve_sender(&client, chat).await;
        let sender = chat.sender.clone();
        if sender.is_empty() {
            return Err(ImError::msg("No sending handle was chosen"));
        }

        let conversation: ConversationData = chat.into();
        let mut inst = MessageInst::new(conversation, &sender, message);
        if let Some(chosen) = guid.filter(|g| !g.is_empty()) {
            inst.id = chosen;
        }
        let guid = inst.id.clone();

        let job = match client.send(&mut inst).await {
            Ok(job) => job,
            // A conversation whose recipients have no delivery targets. Almost always a poisoned
            // key cache rather than a person who has stopped using iMessage: an IDS query that
            // comes back empty — a flap, a rate limit, a device whose keys were rotated — is
            // *cached* as an empty identity list, and from then on every send to that conversation
            // fails here without a single packet leaving the phone, until the entry ages out. That
            // is what makes this present as "some chats instantly error and never recover".
            //
            // Nothing has been transmitted at this point, so re-asking IDS and trying once more
            // cannot duplicate a message. `prepare_send` is idempotent and the instance keeps its
            // own guid, so the retry is the same message rather than a second one — which matters,
            // because the app has already drawn a bubble waiting for exactly this guid.
            Err(PushError::NoValidTargets) => {
                warn!("no delivery targets for {guid}; refreshing IDS keys and retrying once");
                client.identity.invalidate_id_cache().await;
                client.send(&mut inst).await?
            }
            Err(err) => return Err(err.into()),
        };

        if let Some(handle) = job.handle {
            let local = self.local_tx.clone();
            let guid = guid.clone();
            tokio::spawn(async move {
                let error = match handle.await {
                    Ok(Ok(())) => None,
                    Ok(Err(err)) => Some(format!("{err}")),
                    Err(err) => Some(format!("{err}")),
                };
                let _ = local.send(IncomingEvent::SendConfirm { guid, error }).await;
            });
        }

        Ok(guid)
    }
}

/// Fetch an attachment's bytes to `path`, using the handle from [`AttachmentInfo::token`].
#[uniffi::export(async_runtime = "tokio")]
pub async fn download_attachment(session: Arc<Session>, token: String, path: String) -> Result<()> {
    let attachment = api::restore_attachment(token);
    let path = std::path::Path::new(&path);
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }

    let mut file = std::fs::File::create(path)?;
    attachment
        .get_attachment(&session.conn, &mut file, |_progress, _total| {})
        .await?;
    Ok(())
}

/// Whether two spellings of a handle name the same person.
///
/// Addresses compare case-blind; numbers compare on their last ten digits, which is where a
/// national spelling and an E.164 one agree. Deliberately loose — it only decides which already
/// shared channels to listen on, so a false match costs one idle subscription.
fn same_handle(a: &str, b: &str) -> bool {
    fn bare(h: &str) -> String {
        let h = h.trim();
        let h = h
            .strip_prefix("tel:")
            .or_else(|| h.strip_prefix("mailto:"))
            .unwrap_or(h);
        h.to_lowercase()
    }
    let (a, b) = (bare(a), bare(b));
    if a.contains('@') || b.contains('@') {
        return a == b;
    }
    let digits = |s: &str| s.chars().filter(|c| c.is_ascii_digit()).collect::<String>();
    let (a, b) = (digits(&a), digits(&b));
    if a.len() < 7 || b.len() < 7 {
        return a == b && !a.is_empty();
    }
    let tail = |s: &str| s[s.len().saturating_sub(10)..].to_string();
    tail(&a) == tail(&b)
}

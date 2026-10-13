package com.ruskcoder.messages.data.model

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Apple's Polls, on the wire.
 *
 * A poll is not text and it is not a reply. It is an iMessage **app balloon** — the same kind of
 * message a GamePigeon game rides in — carrying a JSON document in its `url`, and every vote is an
 * *association* against that balloon rather than a message of its own. That is what keeps a poll
 * one bubble that changes rather than a bubble followed by a column of "I vote Tacos".
 *
 * The shapes below are Apple's, field for field, because they have to be: this is read by, and read
 * back from, the Polls extension on a real iPhone. Names that look redundant here ([text] beside
 * [attributedText], [canBeEdited] which nothing in this app sets) are part of the document Apple
 * writes, and dropping them would produce a poll that this app could read and nothing else could.
 *
 * The payload rides in the balloon url as `data:,<base64 of the JSON>?src=p&c=<option count>`.
 *
 * The three things that happen to a poll, and how each travels:
 *
 *  - **asking one** — a new balloon message, the whole document in it;
 *  - **voting** — an update against the balloon carrying only *your own* votes, so two people
 *    voting at once cannot overwrite each other. Everyone's standing answer is the newest update
 *    each of them sent, which is what [PollState] works out;
 *  - **adding a choice** — an update carrying the whole document again, with the new option on the
 *    end and the count in the url bumped.
 */
@Serializable
data class PollPayload(
    val item: PollItem? = null,
    val version: Int = 1,
)

@Serializable
data class PollItem(
    val orderedPollOptions: List<PollOption> = emptyList(),
    val votes: List<PollVote> = emptyList(),
    val creatorHandle: String? = null,
    val title: String? = null,
)

@Serializable
data class PollOption(
    /** Apple writes the option's text into both of these. */
    val attributedText: String? = null,
    val canBeEdited: Boolean = false,
    val creatorHandle: String? = null,
    /**
     * An upper-case UUID, and the only thing a vote actually names.
     *
     * Votes point at identifiers rather than at text or at a position, which is what lets a poll
     * gain a choice without invalidating the answers already given.
     */
    val optionIdentifier: String,
    val text: String,
)

@Serializable
data class PollVote(
    val voteOptionIdentifier: String,
    val participantHandle: String,
)

/** The balloon plugin that draws a poll. */
object Polls {
    const val BUNDLE_ID =
        "com.apple.messages.MSMessageExtensionBalloonPlugin:0000000000:com.apple.messages.Polls"
    const val APP_NAME = "Polls"

    /** What a poll sent with nothing typed alongside it is called. */
    const val DEFAULT_TITLE = "Poll"

    /** How many choices a poll may hold. Past this the composer stops offering an empty row. */
    const val MAX_OPTIONS = 12

    /** How many empty choices a fresh poll starts with. */
    const val STARTING_OPTIONS = 3

    /**
     * What a client with no Polls plugin shows in place of the bubble, and what the notification
     * says. Apple's own is the options run together with commas, which is the only summary of a
     * poll that is shorter than the poll.
     */
    fun ldText(options: List<PollOption>): String = options.joinToString(", ") { it.text }

    fun newOption(text: String, creatorHandle: String?): PollOption = PollOption(
        attributedText = text,
        canBeEdited = false,
        creatorHandle = creatorHandle,
        optionIdentifier = UUID.randomUUID().toString().uppercase(),
        text = text,
    )
}

private val pollJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The balloon url a poll payload rides in.
 *
 * `?src=p&c=<n>` is Apple's, and the count is not decorative: the extension reads it to know how
 * many options the document should have, and an add-choice update that leaves it alone is ignored.
 */
fun encodePollUrl(payload: PollPayload): String {
    val json = pollJson.encodeToString(PollPayload.serializer(), payload)
    val encoded = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val count = payload.item?.orderedPollOptions?.size ?: 0
    return "data:,$encoded?src=p&c=$count"
}

/**
 * Read a poll payload back out of a balloon url, or null if it is not one.
 *
 * Deliberately forgiving about the envelope and strict about nothing: this parses payloads written
 * by Apple's own extension, and a poll that fails to decode should disappear into "some balloon we
 * cannot draw" rather than take the conversation down with it.
 */
fun decodePollUrl(url: String?): PollPayload? {
    if (url.isNullOrBlank()) return null
    return runCatching {
        val body = url.substringAfter("data:,", "").substringBefore('?')
        if (body.isEmpty()) return null
        val json = String(Base64.decode(body, Base64.DEFAULT), Charsets.UTF_8)
        pollJson.decodeFromString(PollPayload.serializer(), json)
    }.getOrNull()
}

/**
 * A poll as the conversation currently stands: its choices, and who has answered what.
 *
 * Assembled rather than stored. The choices come from the newest update that carried any (the
 * opening message, or the last "add choice"), and the answers are the newest update each person
 * sent — merged per handle exactly the way Apple's own client does it, so somebody changing their
 * mind replaces their earlier answer instead of adding to it.
 */
data class PollState(
    val title: String,
    val options: List<PollOption>,
    /** Voter handle to the options they picked. Apple's poll allows more than one. */
    val votes: Map<String, Set<String>>,
    /** Which handles are ours, so "did I vote for this" is answerable. */
    val myHandles: Set<String>,
) {
    /** Everyone who picked [option], in a stable order so the avatar stack does not reshuffle. */
    fun voters(option: PollOption): List<String> =
        votes.entries
            .filter { option.optionIdentifier in it.value }
            .map { it.key }
            .sorted()

    fun count(option: PollOption): Int = voters(option).size

    fun picked(option: PollOption): Boolean =
        myHandles.any { option.optionIdentifier in (votes[it] ?: emptySet()) }

    val total: Int get() = votes.count { it.value.isNotEmpty() }

    /** The most any one option has drawn — what the bars are measured against. */
    val leader: Int get() = options.maxOfOrNull { count(it) } ?: 0

    /** What we have picked, as the identifiers a fresh vote update should carry. */
    fun myVotes(): Set<String> =
        myHandles.flatMap { votes[it] ?: emptySet() }.toSet()

    /** That set with [option] turned on if it was off, and off if it was on. */
    fun toggled(option: PollOption): Set<String> {
        val mine = myVotes()
        return if (option.optionIdentifier in mine) {
            mine - option.optionIdentifier
        } else {
            mine + option.optionIdentifier
        }
    }
}

/**
 * Fold a poll's opening payload and every update sent against it into one standing.
 *
 * [updates] are oldest first, each paired with the handle that sent it. Options are taken from the
 * last payload that carried any — an update that is purely a vote carries none, so voting cannot
 * silently drop a choice somebody added. Votes are taken per sender from that sender's newest
 * update, which is the whole of the merge rule.
 */
fun pollStateOf(
    opening: PollPayload?,
    updates: List<Pair<String, PollPayload>>,
    myHandles: Set<String>,
): PollState {
    var options = opening?.item?.orderedPollOptions.orEmpty()
    val title = opening?.item?.title?.takeIf { it.isNotBlank() } ?: Polls.DEFAULT_TITLE
    val byVoter = linkedMapOf<String, Set<String>>()
    // The opening payload can carry votes of its own — a poll sent by somebody who answered it as
    // they wrote it — and its creator is who they belong to.
    opening?.item?.votes?.groupBy { it.participantHandle }?.forEach { (handle, votes) ->
        byVoter[handle] = votes.map { it.voteOptionIdentifier }.toSet()
    }
    updates.forEach { (sender, payload) ->
        val item = payload.item ?: return@forEach
        if (item.orderedPollOptions.isNotEmpty()) options = item.orderedPollOptions
        // An update that carries no votes at all is an add-choice, not somebody clearing their
        // answer — clearing one arrives as an empty vote list from a sender who has voted before,
        // which is why this asks whether the update was *about* votes rather than whether it has
        // any. Apple's own add-choice update carries the poll's whole vote history, so an empty
        // list from it would wipe the board.
        if (item.orderedPollOptions.isEmpty()) {
            // Filed under the handle the payload names, not under the one we worked out for the
            // sender: the two are the same person but not always the same string — an address can
            // arrive with or without its country code, and matching on it would silently drop the
            // vote. The sender is only needed for the one update that names nobody, which is
            // somebody taking their last answer back.
            val named = item.votes.groupBy { it.participantHandle.ifBlank { sender } }
            if (named.isEmpty()) {
                byVoter[sender] = emptySet()
            } else {
                named.forEach { (handle, votes) ->
                    byVoter[handle] = votes.map { it.voteOptionIdentifier }.toSet()
                }
            }
        } else if (item.votes.isNotEmpty()) {
            item.votes.groupBy { it.participantHandle }.forEach { (handle, votes) ->
                byVoter[handle] = votes.map { it.voteOptionIdentifier }.toSet()
            }
        }
    }
    return PollState(
        title = title,
        options = options,
        votes = byVoter.filterValues { it.isNotEmpty() },
        myHandles = myHandles,
    )
}

/** The poll a message carries, if it carries one. */
fun Message.pollAttachment(): Attachment.Poll? =
    ((this as? Message.AttachmentMessage)?.attachment) as? Attachment.Poll

/** True for a vote or an added choice — a real message, but not one the conversation draws. */
fun Message.isPollUpdate(): Boolean = pollAttachment()?.updateOf != null

/**
 * The conversation with every vote folded into the poll it answers.
 *
 * The updates are real messages and they really were sent — that is what makes the tally work
 * across devices and across an iPhone. But nobody *said* anything, and a poll five people answered
 * would otherwise be a poll followed by five empty bubbles. The poll above them already shows all
 * of it.
 */
fun withoutPollUpdates(messages: List<Message>): List<Message> =
    if (messages.none { it.isPollUpdate() }) messages else messages.filterNot { it.isPollUpdate() }

/**
 * Every poll's standing, keyed by the id of the message that asked it.
 *
 * One walk over the conversation rather than a search per poll. Grouped by the balloon's root guid
 * rather than by message id, because that is what an update names and what is stable across the
 * moment a locally-composed poll learns the guid Apple gave it.
 *
 * [senderHandle] answers who sent a message, which the domain model does not carry — only the
 * screen knows how to work it out, and it is needed for the one update whose payload cannot say:
 * somebody clearing their last answer, which carries no votes and therefore no handle.
 */
fun pollStatesIn(
    messages: List<Message>,
    myHandles: Set<String>,
    senderHandle: (Message) -> String,
): Map<String, PollState> {
    val byRoot = linkedMapOf<String, MutableList<Message>>()
    messages.forEach { message ->
        val poll = message.pollAttachment() ?: return@forEach
        val key = poll.rootGuid ?: poll.sessionId.ifBlank { message.id }
        byRoot.getOrPut(key) { mutableListOf() } += message
    }
    if (byRoot.isEmpty()) return emptyMap()

    val states = mutableMapOf<String, PollState>()
    byRoot.values.forEach { rows ->
        val opening = rows.firstOrNull { it.pollAttachment()?.updateOf == null } ?: return@forEach
        val updates = rows
            .filter { it.id != opening.id }
            .sortedBy { it.createdAt }
            .mapNotNull { row ->
                decodePollUrl(row.pollAttachment()?.url)?.let { senderHandle(row) to it }
            }
        states[opening.id] = pollStateOf(
            opening = decodePollUrl(opening.pollAttachment()?.url),
            updates = updates,
            myHandles = myHandles,
        )
    }
    return states
}

/** The payload a vote update carries: our answers, and nothing else. */
fun votePayload(choices: Set<String>, myHandle: String): PollPayload = PollPayload(
    item = PollItem(
        votes = choices.map { PollVote(voteOptionIdentifier = it, participantHandle = myHandle) },
    ),
    version = 1,
)

/** The payload an add-choice update carries: the whole poll again, with the new choices on it. */
fun optionsPayload(state: PollState, options: List<PollOption>, myHandle: String?): PollPayload =
    PollPayload(
        item = PollItem(
            orderedPollOptions = options,
            votes = state.votes.flatMap { (handle, picks) ->
                picks.map { PollVote(voteOptionIdentifier = it, participantHandle = handle) }
            },
            creatorHandle = myHandle,
            title = state.title,
        ),
        version = 1,
    )

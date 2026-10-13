package com.ruskcoder.messages.ui.screens.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.ruskcoder.messages.data.repository.ChatRepository
import com.ruskcoder.messages.ui.components.MessageThreadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Find-in-conversation: what is being looked for, the hits, and which of them is on screen.
 *
 * Split out of the conversation screen, where it was a handful of loose variables and effects mixed
 * in with everything else. Held together it is also cheaper: the query is read by the effects below
 * through a flow, so typing into the search bar recomposes the bar and not the whole screen.
 */
@Stable
class InChatSearchState(startOpen: Boolean) {
    var open by mutableStateOf(startOpen)
    var query by mutableStateOf("")
    var index by mutableIntStateOf(0)

    /** Ids of the matching messages, newest first — the order the chevrons walk. */
    var hits by mutableStateOf<List<String>>(emptyList())
        internal set

    /** The hit being looked at, lit up in the thread. */
    val currentHitId: String? get() = hits.getOrNull(index)

    fun step(delta: Int) {
        if (hits.isEmpty()) return
        index = (index + delta + hits.size) % hits.size
    }

    fun close() {
        open = false
        query = ""
        index = 0
    }
}

/**
 * The search state for one conversation, with the two effects that make it work: asking the index
 * as the query settles, and bringing each hit on screen — loading it first when it is older than
 * the window the thread holds.
 */
@Composable
fun rememberInChatSearch(
    chatId: String,
    startOpen: Boolean,
    chatRepository: ChatRepository,
    threadState: MessageThreadState,
): InChatSearchState {
    val state = remember(chatId) { InChatSearchState(startOpen) }

    // Asked of the full-text index rather than filtered out of the thread in memory: the thread only
    // holds a window of the newest messages, and find-in-chat has to reach the whole conversation.
    LaunchedEffect(state) {
        snapshotFlow { state.query.trim() }.collectLatest { needle ->
            if (needle.isBlank()) {
                state.hits = emptyList()
                state.index = 0
                return@collectLatest
            }
            // A query is typed a letter at a time; only the one the typing settles on is worth asking.
            delay(SearchDebounceMs)
            state.hits = chatRepository.searchInChat(chatId, needle).map { it.messageId }
            // Changing the query starts over at the newest hit.
            state.index = 0
        }
    }

    LaunchedEffect(state) {
        snapshotFlow { state.currentHitId }.collectLatest { id ->
            id ?: return@collectLatest
            // A hit older than the window has to be loaded before there is a row to scroll to — and
            // the widened thread then has to reach the list's own layout, a frame or two later.
            if (!chatRepository.ensureLoaded(chatId, id)) return@collectLatest
            var frames = 0
            while (!threadState.holds(id) && frames++ < ScrollWaitFrames) withFrameNanos { }
            threadState.scrollToMessage(id)
        }
    }
    return state
}

/** How long find-in-chat waits for the typing to settle before asking the index. */
private const val SearchDebounceMs = 150L

/** How many frames a jump waits for a freshly loaded message to reach the list's layout. */
private const val ScrollWaitFrames = 30

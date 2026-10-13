package com.ruskcoder.messages.ui.navigation

/** Route names, mirroring the RN app's Expo Router file layout under `src/app/`. */
object Routes {
    const val CHAT_LIST = "chatList"
    const val NEW_CHAT = "newChat"
    const val SEARCH = "search?chatId={chatId}"
    const val SETTINGS = "settings"
    /** iMessage sign-in, reachable from Account after skipping it on first run. */
    const val SETUP = "setup"
    const val SETTINGS_PAGE = "settings/{page}"
    const val CHAT = "chat/{id}?search={search}&message={message}"
    const val CONTACT = "contact/{id}"
    const val GROUP = "group/{id}"

    /** [search] opens the conversation with find-in-chat already up — where a details page sends you. */
    fun chat(id: String, search: Boolean = false) = "chat/$id?search=$search"

    /** Opens a conversation scrolled to one message and lit up — where a search result leads. */
    fun chatAtMessage(id: String, messageId: String) =
        "chat/$id?search=false&message=${android.net.Uri.encode(messageId)}"
    fun contact(id: String) = "contact/$id"
    fun group(id: String) = "group/$id"
    fun settingsPage(page: String) = "settings/$page"
    fun search(chatId: String? = null) = if (chatId != null) "search?chatId=$chatId" else "search"
}

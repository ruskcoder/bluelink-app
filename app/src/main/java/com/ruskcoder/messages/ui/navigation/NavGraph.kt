package com.ruskcoder.messages.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ruskcoder.messages.AppContainer
import com.ruskcoder.messages.data.model.PageTransition
import com.ruskcoder.messages.ui.components.ScreenSnapshot
import com.ruskcoder.messages.ui.theme.LocalSettings
import kotlinx.coroutines.launch
import com.ruskcoder.messages.ui.screens.chat.ChatScreen
import com.ruskcoder.messages.ui.screens.chatlist.ChatListScreen
import com.ruskcoder.messages.ui.screens.contact.ContactScreen
import com.ruskcoder.messages.ui.screens.group.GroupScreen
import com.ruskcoder.messages.ui.screens.newchat.NewChatScreen
import com.ruskcoder.messages.ui.screens.search.SearchScreen
import com.ruskcoder.messages.ui.screens.setup.SetupScreen
import com.ruskcoder.messages.ui.screens.settings.AccountScreen
import com.ruskcoder.messages.ui.screens.settings.AppearanceScreen
import com.ruskcoder.messages.ui.screens.settings.ChatsSettingsScreen
import com.ruskcoder.messages.ui.screens.settings.DeviceScreen
import com.ruskcoder.messages.ui.screens.settings.HelpScreen
import com.ruskcoder.messages.ui.screens.settings.ListsScreen
import com.ruskcoder.messages.ui.screens.settings.NotificationsScreen
import com.ruskcoder.messages.ui.screens.settings.PermissionsScreen
import com.ruskcoder.messages.ui.screens.settings.TranscriptionScreen
import com.ruskcoder.messages.ui.screens.settings.PrivacyScreen
import com.ruskcoder.messages.ui.screens.settings.SettingsHomeScreen
import com.ruskcoder.messages.ui.screens.settings.StorageScreen

/**
 * Material's own durations for a full-screen transition: the arriving page takes the long one, the
 * leaving page the short one, so the two never look like a single sheet of paper sliding across.
 */
private const val ENTER_MS = 400
private const val EXIT_MS = 300
/** How long the outgoing page takes to fade in a fade-through, which finishes before the new one starts. */
private const val FADE_THROUGH_OUT_MS = 90

/** Material's emphasized-decelerate and emphasized-accelerate curves, for arriving and leaving. */
private val EnterEasing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val ExitEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * The appearance setting's "Page transition" choice, as the four transitions a `NavHost` needs.
 * `pop` distinguishes going back — the same style, run the other way — from going forward.
 *
 * Each style is a distinct Material motion pattern rather than the same slide at different lengths,
 * so switching between them in Appearance is something you can actually see: shared axis for
 * Default and Slide, fade through for Fade, and a container that rises off the bottom edge for
 * Sheet.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.pageEnter(style: PageTransition, pop: Boolean): EnterTransition =
    when (style) {
        PageTransition.NONE -> EnterTransition.None
        // Fade through: the new page starts only once the old one is gone, so they never
        // cross-dissolve into each other.
        PageTransition.FADE -> fadeIn(tween(ENTER_MS, delayMillis = FADE_THROUGH_OUT_MS, easing = StandardEasing))
        // Up from the bottom edge on the way in; on the way back it's the sheet above that moves,
        // so the page underneath is simply revealed.
        PageTransition.SHEET ->
            if (pop) EnterTransition.None
            else slideInVertically(tween(ENTER_MS, easing = EnterEasing)) { it } + fadeIn(tween(ENTER_MS / 2))
        // The full-width slide of a plain push.
        PageTransition.SLIDE -> slideIntoContainer(
            if (pop) SlideDirection.Right else SlideDirection.Left,
            tween(ENTER_MS, easing = EnterEasing),
        )
        // Shared axis X: a third of the width, carried by the fade — the arriving page overtakes
        // the one it replaces rather than pushing it.
        PageTransition.DEFAULT -> fadeIn(tween(ENTER_MS, easing = StandardEasing)) +
            slideIntoContainer(
                if (pop) SlideDirection.Right else SlideDirection.Left,
                tween(ENTER_MS, easing = EnterEasing),
            ) { it / 3 }
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.pageExit(style: PageTransition, pop: Boolean): ExitTransition =
    when (style) {
        PageTransition.NONE -> ExitTransition.None
        PageTransition.FADE -> fadeOut(tween(FADE_THROUGH_OUT_MS, easing = StandardEasing))
        PageTransition.SHEET ->
            if (pop) slideOutVertically(tween(EXIT_MS, easing = ExitEasing)) { it } + fadeOut(tween(EXIT_MS))
            else ExitTransition.None
        PageTransition.SLIDE -> slideOutOfContainer(
            if (pop) SlideDirection.Right else SlideDirection.Left,
            tween(EXIT_MS, easing = ExitEasing),
        )
        PageTransition.DEFAULT -> fadeOut(tween(EXIT_MS, easing = StandardEasing)) +
            slideOutOfContainer(
                if (pop) SlideDirection.Right else SlideDirection.Left,
                tween(EXIT_MS, easing = ExitEasing),
            ) { it / 3 }
    }

/**
 * Drops the frozen copy of the screen once this destination has actually *arrived*.
 *
 * On composition would be the obvious moment and is the wrong one. A back gesture composes the
 * destination underneath before it knows whether the gesture will be completed — that is what makes
 * predictive back predictive — so clearing there took the glass out from under the page still on
 * screen the instant a finger touched the edge of it, and let go of, left that page sitting there
 * unfrosted for good. The same thing happened to the plain back button, where the page loses its
 * blur for the whole of its slide out.
 *
 * Resumption is the event that means "this destination is the one you are on now": the back stack
 * entry is held at STARTED for as long as a transition is in flight or a gesture is undecided, and
 * only moves on when it settles. A cancelled gesture never gets here, which is the whole point.
 */
@Composable
private fun ClearSnapshotOnArrival() {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ScreenSnapshot.clear() }
}

@Composable
fun MessagesNavGraph(container: AppContainer, navController: NavHostController = rememberNavController()) {
    val pageTransition = LocalSettings.current.pageTransition

    // The frosted pages — contact and group details, settings, search, the recipient picker — are
    // glass over the screen they were opened from. As real destinations they *replace* that screen
    // rather than sitting above it, so it is copied out first and they blur the copy; see
    // `ScreenSnapshot`. The navigation waits for the copy, which is a frame or so, otherwise the
    // copy catches the transition already underway.
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    fun openDetails(route: String) {
        scope.launch {
            ScreenSnapshot.capture(view)
            navController.navigate(route)
        }
    }

    /**
     * A frosted page opened from another frosted page — the search screen, reached from a details
     * page's shared-media card.
     *
     * No new copy is taken. The one already held is of the conversation both pages are *about*,
     * which is the right ground for either of them; re-copying would hand the second page a
     * blurred picture of the first, and hand the first a blurred picture of itself on the way back.
     * It also skips the frame the copy costs, so the page arrives straight away.
     */
    fun openOverDetails(route: String) {
        navController.navigate(route)
    }

    // A conversation named from outside the app — a notification tap, a conversation shortcut.
    // Consumed here because the intent lands on the activity but only the graph can navigate, and
    // cleared immediately so returning to the app later does not re-open it.
    val pendingChatId by container.pendingChatId.collectAsStateWithLifecycle()
    LaunchedEffect(pendingChatId) {
        val chatId = pendingChatId ?: return@LaunchedEffect
        container.pendingChatId.value = null
        navController.navigate(Routes.chat(chatId)) {
            // Land on the conversation with the list behind it, rather than on top of whatever
            // screen happened to be open when the notification arrived.
            popUpTo(Routes.CHAT_LIST)
            launchSingleTop = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = Routes.CHAT_LIST,
        // The host has to be the size of the window: `slideIntoContainer` measures its offsets off
        // the container, so a host that wrapped its content would slide the arriving page by
        // whatever that page happened to measure — nothing at all, on a frame where it is empty.
        modifier = Modifier.fillMaxSize(),
        enterTransition = { pageEnter(pageTransition, pop = false) },
        exitTransition = { pageExit(pageTransition, pop = false) },
        popEnterTransition = { pageEnter(pageTransition, pop = true) },
        popExitTransition = { pageExit(pageTransition, pop = true) },
    ) {
        composable(Routes.CHAT_LIST) {
            // Arriving anywhere that is not itself frosted is what ends a snapshot's usefulness:
            // it is then a stale picture of somewhere you have left and a bitmap nothing is using.
            //
            // Cleared on *arrival* here rather than on each frosted page's way out. A page leaves
            // composition when its exit transition finishes, which is a good few frames after the
            // page that replaced it is already drawn — so a details page disposing its snapshot
            // pulled the glass out from under the search page it had just opened, a moment after
            // it appeared.
            ClearSnapshotOnArrival()
            ChatListScreen(
                chatRepository = container.chatRepository,
                typingByChat = container.imessageSyncer.typing,
                onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                onOpenSettings = { openDetails(Routes.SETTINGS) },
                onOpenSearch = { openDetails(Routes.search()) },
                onNewChat = { openDetails(Routes.NEW_CHAT) },
                settingsRepository = container.settingsRepository,
                connection = container.connectionMonitor.health,
                onOpenConnection = { openDetails(Routes.settingsPage("connection")) },
            )
        }
        composable(
            Routes.CHAT,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("search") { type = NavType.BoolType; defaultValue = false },
                navArgument("message") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()

            // The other unfrosted destination; see the chat list above.
            ClearSnapshotOnArrival()

            // Announce which conversation is on screen, so a message arriving in it is not also
            // announced in the shade. Tied to the destination's lifetime rather than the screen's
            // internals, so leaving by any route — back, a deep link, process death — clears it.
            DisposableEffect(id) {
                container.activeChatId.value = id
                onDispose {
                    if (container.activeChatId.value == id) container.activeChatId.value = null
                }
            }

            ChatScreen(
                chatId = id,
                initialName = null,
                initialGroup = false,
                initialMembers = emptyList(),
                startInSearch = backStackEntry.arguments?.getBoolean("search") == true,
                initialMessageId = backStackEntry.arguments?.getString("message"),
                composeRequests = container.composeInChat,
                onComposerFocused = { container.composeInChat.value = null },
                chatRepository = container.chatRepository,
                imessageSender = container.imessageSender,
                pigeonClient = container.pigeonClient,
                openPigeonClient = container.openPigeonClient,
                typingByChat = container.imessageSyncer.typing,
                onChatRead = container.imessageNotifications::clear,
                contactsRepository = container.contactsRepository,
                settingsRepository = container.settingsRepository,
                voiceTranscriptions = container.voiceTranscriptions,
                speechModels = container.speechModels,
                onOpenTranscriptionSettings = { navController.navigate(Routes.settingsPage("transcription")) },
                onBack = navController::popBackStack,
                onOpenContact = { contactId -> openDetails(Routes.contact(contactId)) },
                onOpenChat = { handle -> navController.navigate(Routes.chat(handle)) },
                onOpenGroup = { groupId -> openDetails(Routes.group(groupId)) },
                onOpenSearch = { chatId -> openDetails(Routes.search(chatId)) },
                // Only where the push service is running: fetching goes through the iMessage session,
                // and an install that skipped sign-in must not load the Rust library to find out
                // there is nothing to fetch.
                onRetryDownloads = {
                    if (com.ruskcoder.messages.imessage.PushService.isRunning) {
                        container.imessageSyncer.resumePendingDownloads()
                    }
                },
            )
        }
        composable(
            Routes.CONTACT,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            ContactScreen(
                chatId = id,
                chatRepository = container.chatRepository,
                contactsRepository = container.contactsRepository,
                onBack = navController::popBackStack,
                // A details page sends you back to the conversation with find-in-chat open, rather
                // than out to the advanced page — that one is a tap away from the search bar.
                onOpenSearch = { chatId ->
                    navController.navigate(Routes.chat(chatId, search = true)) { popUpTo(Routes.CHAT) { inclusive = true } }
                },
                // The shared-media card goes the other way: to the search page itself, confined
                // to this conversation, which is where everything shared in it is actually listed.
                onBrowseChat = { chatId -> openOverDetails(Routes.search(chatId)) },
            )
        }
        composable(
            Routes.GROUP,
            arguments = listOf(navArgument("id") { type = NavType.StringType }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getString("id").orEmpty()
            GroupScreen(
                chatId = id,
                chatRepository = container.chatRepository,
                imessageSender = container.imessageSender,
                contactsRepository = container.contactsRepository,
                onBack = navController::popBackStack,
                onOpenChat = { chatId -> navController.navigate(Routes.chat(chatId)) },
                onShowContact = { handle -> openDetails(Routes.contact(handle)) },
                onOpenSearch = { chatId ->
                    navController.navigate(Routes.chat(chatId, search = true)) { popUpTo(Routes.CHAT) { inclusive = true } }
                },
                onBrowseChat = { chatId -> openOverDetails(Routes.search(chatId)) },
                onLeaveGroup = { navController.popBackStack(Routes.CHAT_LIST, inclusive = false) },
            )
        }
        composable(Routes.SETUP) {
            SetupScreen(
                service = container.imessageService,
                backupManager = container.backupManager,
                scope = rememberCoroutineScope(),
                onFinished = navController::popBackStack,
            )
        }
        composable(Routes.NEW_CHAT) {
            NewChatScreen(
                chatRepository = container.chatRepository,
                imessageSender = container.imessageSender,
                contactsRepository = container.contactsRepository,
                onBack = navController::popBackStack,
                onChatCreated = { id ->
                    navController.navigate(Routes.chat(id)) {
                        popUpTo(Routes.CHAT_LIST)
                    }
                },
            )
        }
        composable(
            Routes.SEARCH,
            arguments = listOf(navArgument("chatId") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { backStackEntry ->
            val scopedChatId = backStackEntry.arguments?.getString("chatId")
            SearchScreen(
                chatRepository = container.chatRepository,
                scopedChatId = scopedChatId,
                onBack = navController::popBackStack,
                onOpenChat = { id -> navController.navigate(Routes.chat(id)) },
                onOpenMessage = { id, messageId -> navController.navigate(Routes.chatAtMessage(id, messageId)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsHomeScreen(
                onBack = navController::popBackStack,
                imessageService = container.imessageService,
                onNavigate = { page -> navController.navigate(Routes.settingsPage(page)) },
            )
        }
        composable(
            Routes.SETTINGS_PAGE,
            arguments = listOf(navArgument("page") { type = NavType.StringType }),
        ) { backStackEntry ->
            when (backStackEntry.arguments?.getString("page")) {
                "appearance" -> AppearanceScreen(settingsRepository = container.settingsRepository, onBack = navController::popBackStack)
                "chats" -> ChatsSettingsScreen(
                    settingsRepository = container.settingsRepository,
                    chatRepository = container.chatRepository,
                    onOpenAppearance = { navController.navigate(Routes.settingsPage("appearance")) },
                    onBack = navController::popBackStack,
                )
                "notifications" -> NotificationsScreen(settingsRepository = container.settingsRepository, onBack = navController::popBackStack)
                "privacy" -> PrivacyScreen(
                    settingsRepository = container.settingsRepository,
                    onOpenPermissions = { navController.navigate(Routes.settingsPage("permissions")) },
                    onBack = navController::popBackStack,
                )
                "permissions" -> PermissionsScreen(onBack = navController::popBackStack)
                "transcription" -> TranscriptionScreen(
                    settingsRepository = container.settingsRepository,
                    models = container.speechModels,
                    onBack = navController::popBackStack,
                )
                "device" -> DeviceScreen(
                    imessageService = container.imessageService,
                    onBack = navController::popBackStack,
                )
                "account" -> AccountScreen(
                    settingsRepository = container.settingsRepository,
                    imessageService = container.imessageService,
                    onOpenSetup = { navController.navigate(Routes.SETUP) },
                    onBack = navController::popBackStack,
                )
                "lists" -> ListsScreen(
                    chatRepository = container.chatRepository,
                    contactsRepository = container.contactsRepository,
                    onOpenPermissions = { navController.navigate(Routes.settingsPage("permissions")) },
                    onBack = navController::popBackStack,
                )
                "storage" -> StorageScreen(
                    settingsRepository = container.settingsRepository,
                    chatRepository = container.chatRepository,
                    backupManager = container.backupManager,
                    onBack = navController::popBackStack,
                )
                "help" -> HelpScreen(onBack = navController::popBackStack)
                "connection" -> com.ruskcoder.messages.ui.screens.settings.ConnectionScreen(
                    monitor = container.connectionMonitor,
                    onReconnect = {
                        if (com.ruskcoder.messages.imessage.PushService.isRunning) container.imessageSyncer.reconnect()
                    },
                    onBack = navController::popBackStack,
                )
                "web" -> com.ruskcoder.messages.ui.screens.settings.WebDevicesScreen(
                    bridge = container.webBridge,
                    onBack = navController::popBackStack,
                )
                else -> Unit
            }
        }
    }
}

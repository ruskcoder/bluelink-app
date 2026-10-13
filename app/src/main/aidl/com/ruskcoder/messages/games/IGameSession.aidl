package com.ruskcoder.messages.games;

import com.ruskcoder.messages.games.IUpdateGameSessionCallback;
import com.ruskcoder.messages.games.IMessageUpdatedCallback;

// Implemented by pigeon (this app), called by the host messaging app.
// A "session" is one turn-based game attached to a message thread; its state
// is just a flat String->String map (mirrored as a Bundle over IPC).
interface IGameSession {
    // The opening state for a new game of `game`, as a String->String map:
    // the GamePigeon `game`/`game_name`/`version` identity plus whatever that
    // game needs to start (Knockout's board, Darts' mode and avatar). The host
    // merges this into the message envelope it builds. Empty for a game this
    // app does not have.
    Bundle newGameData(String game);

    // Host calls this before launching GodotGameActivity, seeding the state
    // the game should render (e.g. board position, whose turn it is).
    void startSession(String id, in Bundle initialMessage, String senderUUID);

    Bundle getCurrentMessage(String id);

    // Host calls this to push an opponent's incoming move into a session
    // that pigeon already has open (e.g. app was foregrounded on their turn).
    void updateSession(in Bundle updates, String mySession, IUpdateGameSessionCallback callback);

    // Host registers to be notified when the local player finishes a move,
    // so it can turn that into an outgoing iMessage.
    void registerCallback(String id, IMessageUpdatedCallback callback);

    String getSenderUUID(String id);

    void setSuppressNotifications(String id, boolean suppress);

    void lockMsgHandle(String id);
    void unlockMsgHandle(String id);
}

package org.jellyfin.mobile.bridge

import java.util.WeakHashMap

internal class ExternalPlaylistSessionStore {
    private val sessions = WeakHashMap<Any, ExternalPlaylistSession>()

    @Synchronized
    fun get(owner: Any, restore: () -> Boolean, registerSavedState: (() -> Boolean) -> Unit): ExternalPlaylistSession {
        return sessions.getOrPut(owner) {
            ExternalPlaylistSession(initiallyAwaitingResult = restore()).also { session ->
                registerSavedState(session::isAwaitingResult)
            }
        }
    }
}

package org.jellyfin.mobile.app

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.jellyfin.mobile.bridge.ExternalPlaylistSession
import org.jellyfin.mobile.settings.ExternalPlayerPackage
import org.jellyfin.mobile.utils.Constants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PlaybackContextGenerationTest {
    private val raw = mockk<SharedPreferences>(relaxed = true)
    private val listener = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
    private val preferences: AppPreferences

    init {
        val context = mockk<Context>()
        every { context.packageName } returns "org.jellyfin.test"
        every { context.getSharedPreferences("org.jellyfin.test_preferences", Context.MODE_PRIVATE) } returns raw
        every { raw.registerOnSharedPreferenceChangeListener(capture(listener)) } returns Unit
        preferences = AppPreferences(context)
    }

    @Test
    fun `all relevant preference notifications advance the context even when values later match`() {
        listOf(
            Constants.PREF_SERVER_ID, Constants.PREF_USER_ID,
            Constants.PREF_VIDEO_PLAYER_TYPE, Constants.PREF_EXTERNAL_PLAYER_APP,
        ).forEach { key ->
            val before = preferences.playbackContextGeneration
            listener.captured.onSharedPreferenceChanged(raw, key)
            assertTrue(preferences.playbackContextGeneration > before, key)
        }
    }

    @Test
    fun `unrelated preference changes do not cancel playback but clearing preferences does`() {
        val before = preferences.playbackContextGeneration
        listener.captured.onSharedPreferenceChanged(raw, "unrelated_display_option")
        assertEquals(before, preferences.playbackContextGeneration)
        listener.captured.onSharedPreferenceChanged(raw, null)
        assertTrue(preferences.playbackContextGeneration > before)
    }

    @Test
    fun `native setters invalidate immediately before a delayed preferences callback`() {
        var before = preferences.playbackContextGeneration
        preferences.currentServerId = 2
        assertTrue(preferences.playbackContextGeneration > before)
        before = preferences.playbackContextGeneration
        preferences.currentUserId = 3
        assertTrue(preferences.playbackContextGeneration > before)
        before = preferences.playbackContextGeneration
        preferences.externalPlayerApp = ExternalPlayerPackage.MX_PLAYER_PRO
        assertTrue(preferences.playbackContextGeneration > before)
    }

    @Test
    fun `explicit API authentication changes advance the context independently of preferences`() {
        val before = preferences.playbackContextGeneration
        preferences.invalidatePlaybackContext()
        preferences.invalidatePlaybackContext()
        assertTrue(preferences.playbackContextGeneration > before)
    }

    @Test
    fun `changing the player away and back while awaiting resolution still cancels the queue`() = runBlocking {
        val session = ExternalPlaylistSession()
        val capturedGeneration = preferences.playbackContextGeneration
        val gate = CompletableDeferred<Unit>()
        var player = ExternalPlayerPackage.MX_PLAYER_FREE
        var launches = 0
        val pending = async(start = CoroutineStart.UNDISPATCHED) {
            session.play(
                listOf(UUID(0, 1)),
                { player == ExternalPlayerPackage.MX_PLAYER_FREE && preferences.playbackContextGeneration == capturedGeneration },
                { gate.await(); it },
            ) { launches++ }
        }

        player = ExternalPlayerPackage.MX_PLAYER_PRO
        listener.captured.onSharedPreferenceChanged(raw, Constants.PREF_EXTERNAL_PLAYER_APP)
        player = ExternalPlayerPackage.MX_PLAYER_FREE
        listener.captured.onSharedPreferenceChanged(raw, Constants.PREF_EXTERNAL_PLAYER_APP)
        gate.complete(Unit)

        assertEquals(ExternalPlaylistSession.Outcome.CANCELED, pending.await())
        assertEquals(0, launches)
    }
}

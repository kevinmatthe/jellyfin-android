package org.jellyfin.mobile.bridge

import org.jellyfin.mobile.settings.ExternalPlayerPackage
import org.jellyfin.mobile.settings.VideoPlayerType
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MxPlaylistSupportTest {
    @Test
    fun `supports only installed explicitly selected MX free or pro in external player mode`() {
        listOf(ExternalPlayerPackage.MX_PLAYER_FREE, ExternalPlayerPackage.MX_PLAYER_PRO).forEach { player ->
            assertTrue(supportsMxPlaylists(VideoPlayerType.EXTERNAL_PLAYER, player) { it == player })
            assertFalse(supportsMxPlaylists(VideoPlayerType.EXTERNAL_PLAYER, player) { false })
            assertFalse(supportsMxPlaylists(VideoPlayerType.WEB_PLAYER, player) { true })
        }
        listOf(ExternalPlayerPackage.VLC_PLAYER, ExternalPlayerPackage.SYSTEM_DEFAULT, "unknown.player").forEach { player ->
            assertFalse(supportsMxPlaylists(VideoPlayerType.EXTERNAL_PLAYER, player) { true })
        }
    }
}

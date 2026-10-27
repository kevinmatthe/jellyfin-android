package org.jellyfin.mobile.bridge

import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.mobile.settings.ExternalPlayerPackage

internal fun supportsMxPlaylists(@VideoPlayerType mode: String, packageName: String, isInstalled: (String) -> Boolean): Boolean =
    mode == VideoPlayerType.EXTERNAL_PLAYER &&
        packageName in setOf(ExternalPlayerPackage.MX_PLAYER_FREE, ExternalPlayerPackage.MX_PLAYER_PRO) &&
        isInstalled(packageName)

package org.jellyfin.mobile.bridge

import android.content.ComponentName
import android.content.Intent
import android.net.Uri

internal class MxPlaylistVideo(val uri: Uri, val name: String)

internal fun Intent.withMxPlaylist(component: ComponentName, videos: List<MxPlaylistVideo>): Intent = apply {
    require(videos.isNotEmpty())
    this.component = component
    setDataAndType(videos.first().uri, "video/*")
    putExtra("title", videos.first().name)
    // Keep the runtime array type Uri[]: String[] is not MX Player's Parcelable playlist API.
    putExtra("video_list", videos.map(MxPlaylistVideo::uri).toTypedArray())
    putExtra("video_list.name", videos.map(MxPlaylistVideo::name).toTypedArray())
    putExtra("video_list_is_explicit", true)
    putExtra("return_result", true)
    putExtra("secure_uri", true)
}

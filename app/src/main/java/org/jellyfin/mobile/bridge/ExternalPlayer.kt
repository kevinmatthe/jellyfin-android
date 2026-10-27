package org.jellyfin.mobile.bridge

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.savedstate.SavedStateRegistryOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.player.PlayerException
import org.jellyfin.mobile.player.deviceprofile.DeviceProfileBuilder
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.source.ExternalSubtitleStream
import org.jellyfin.mobile.player.source.JellyfinMediaSource
import org.jellyfin.mobile.player.source.MediaSourceResolver
import org.jellyfin.mobile.settings.ExternalPlayerPackage
import org.jellyfin.mobile.settings.VideoPlayerType
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.isPackageInstalled
import org.jellyfin.mobile.utils.toast
import org.jellyfin.mobile.webapp.WebappFunctionChannel
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.videosApi
import org.jellyfin.sdk.api.operations.VideosApi
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import timber.log.Timber

class ExternalPlayer(
    private val context: Context,
    lifecycleOwner: LifecycleOwner,
    registry: ActivityResultRegistry,
) : KoinComponent {
    private val coroutinesScope = MainScope()

    private val appPreferences: AppPreferences by inject()
    private val webappFunctionChannel: WebappFunctionChannel by inject()
    private val mediaSourceResolver: MediaSourceResolver by inject()
    private val deviceProfileBuilder: DeviceProfileBuilder by inject()
    private val externalPlayerProfile: DeviceProfile = deviceProfileBuilder.getExternalPlayerProfile()
    private val apiClient: ApiClient = get()
    private val videosApi: VideosApi = apiClient.videosApi
    private val playlistScope = lifecycleOwner.lifecycleScope
    private val playlistLifecycle = lifecycleOwner.lifecycle
    private val savedStateRegistry = ((context as? SavedStateRegistryOwner) ?: (lifecycleOwner as? SavedStateRegistryOwner))
        ?.savedStateRegistry
    private val playlistSession = PLAYLIST_SESSIONS.get(
        owner = registry,
        restore = {
            savedStateRegistry?.consumeRestoredStateForKey(PLAYLIST_STATE_KEY)?.getBoolean(PLAYLIST_AWAITING_RESULT) ?: false
        },
        registerSavedState = { awaitingResult ->
            savedStateRegistry?.registerSavedStateProvider(PLAYLIST_STATE_KEY) {
                // Activity-scoped ownership survives replacement of the server's WebViewFragment.
                // Persist only this flag, never the queue, stream URLs, or credentials.
                bundleOf(PLAYLIST_AWAITING_RESULT to awaitingResult())
            }
        },
    )

    init {
        playlistLifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                playlistSession.invalidatePending()
            }
        })
    }

    private val playerContract = registry.register(
        "externalplayer",
        lifecycleOwner,
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // This path bypasses ExtPlayer's single-item playback manager. A queue result cannot
        // identify a watched item or position, including after Android recreates this fragment.
        if (playlistSession.consumeResult()) return@register
        val resultCode = result.resultCode
        val intent = result.data
        when (val action = intent?.action) {
            Constants.MPV_PLAYER_RESULT_ACTION -> handleMPVPlayer(resultCode, intent)
            Constants.MX_PLAYER_RESULT_ACTION -> handleMXPlayer(resultCode, intent)
            Constants.VLC_PLAYER_RESULT_ACTION -> handleVLCPlayer(resultCode, intent)
            Constants.MPVKT_PLAYER_RESULT_ACTION -> handleMPVKTPlayer(resultCode, intent)
            else -> {
                if (action != null && resultCode != Activity.RESULT_CANCELED) {
                    Timber.d("Unknown action $action [resultCode=$resultCode]")
                    notifyEvent(Constants.EVENT_CANCELED)
                    context.toast(R.string.external_player_not_supported_yet, Toast.LENGTH_LONG)
                } else {
                    Timber.d("Playback canceled: no player selected or player without action result")
                    notifyEvent(Constants.EVENT_CANCELED)
                    context.toast(R.string.external_player_invalid_player, Toast.LENGTH_LONG)
                }
            }
        }
    }

    @JavascriptInterface
    fun isEnabled() = appPreferences.videoPlayerType == VideoPlayerType.EXTERNAL_PLAYER

    @JavascriptInterface
    fun supportsPlaylists(): Boolean = supportsMxPlaylists(
        appPreferences.videoPlayerType,
        appPreferences.externalPlayerApp,
        context.packageManager::isPackageInstalled,
    )

    @JavascriptInterface
    fun initPlaylist(args: String) {
        val playbackContext = capturePlaylistContext()
        val request = ExternalPlaylistRequest.parse(args)
        playlistScope.launch {
            if (request == null) {
                context.toast(R.string.player_error_invalid_play_options)
                return@launch
            }
            if (!supportsPlaylists()) {
                context.toast(R.string.external_playlist_requires_mx, Toast.LENGTH_LONG)
                return@launch
            }
            if (playbackContext == null || !isCurrentPlaylistContext(playbackContext)) {
                context.toast(R.string.external_playlist_canceled)
                return@launch
            }
            if (playlistSession.isBusy()) {
                context.toast(R.string.external_playlist_busy)
                return@launch
            }
            context.toast(R.string.external_playlist_loading, Toast.LENGTH_LONG)
            val outcome = playlistSession.play(
                ids = request.ids,
                isCurrent = { isCurrentPlaylistContext(playbackContext) },
                resolve = { itemId ->
                    mediaSourceResolver.resolveMediaSource(
                        itemId = itemId,
                        deviceProfile = externalPlayerProfile,
                        maxStreamingBitrate = Int.MAX_VALUE,
                        autoOpenLiveStream = false,
                    ).getOrThrow()
                },
            ) { sources ->
                // No player opens until every requested source has resolved successfully.
                val videos = sources.map { source ->
                    MxPlaylistVideo(
                        videosApi.getVideoStreamUrl(
                            itemId = source.itemId,
                            static = true,
                            mediaSourceId = source.id,
                            playSessionId = source.playSessionId,
                        ).toUri(),
                        source.getName(context),
                    )
                }
                check(isCurrentPlaylistContext(playbackContext) && supportsPlaylists())
                val component = requireNotNull(getComponent(playbackContext.playerPackage))
                playerContract.launch(Intent(Intent.ACTION_VIEW).withMxPlaylist(component, videos))
            }
            when (outcome) {
                ExternalPlaylistSession.Outcome.FAILED -> context.toast(R.string.external_playlist_error, Toast.LENGTH_LONG)
                ExternalPlaylistSession.Outcome.CANCELED -> context.toast(R.string.external_playlist_canceled)
                ExternalPlaylistSession.Outcome.BUSY -> context.toast(R.string.external_playlist_busy)
                ExternalPlaylistSession.Outcome.STARTED -> Unit
            }
        }
    }

    @JavascriptInterface
    fun initPlayer(args: String) {
        val playOptions = PlayOptions.fromJson(args)
        val itemId = playOptions?.run {
            ids.firstOrNull() ?: mediaSourceId?.toUUIDOrNull() // fallback if ids is empty
        }
        if (playOptions == null || itemId == null) {
            context.toast(R.string.player_error_invalid_play_options)
            return
        }
        if (!playlistSession.beginSinglePlayback()) {
            context.toast(R.string.external_playlist_busy)
            return
        }

        coroutinesScope.launch {
            try {
                // Resolve media source to query info about external (subtitle) streams
                mediaSourceResolver.resolveMediaSource(
                    itemId = itemId,
                    mediaSourceId = playOptions.mediaSourceId,
                    deviceProfile = externalPlayerProfile,
                    startTime = playOptions.startPosition,
                    audioStreamIndex = playOptions.audioStreamIndex,
                    subtitleStreamIndex = playOptions.subtitleStreamIndex,
                    maxStreamingBitrate = Int.MAX_VALUE, // ensure we always direct play
                    autoOpenLiveStream = false,
                ).onSuccess { jellyfinMediaSource ->
                    playMediaSource(playOptions, jellyfinMediaSource)
                }.onFailure { error ->
                    when (error as? PlayerException) {
                        is PlayerException.InvalidPlayOptions -> context.toast(R.string.player_error_invalid_play_options)
                        is PlayerException.NetworkFailure -> context.toast(R.string.player_error_network_failure)
                        is PlayerException.UnsupportedContent -> context.toast(R.string.player_error_unsupported_content)
                        null -> throw error // Unknown error, rethrow from here
                    }
                }
            } finally {
                playlistSession.finishSingleResolution()
            }
        }
    }

    private fun playMediaSource(playOptions: PlayOptions, source: JellyfinMediaSource) {
        // Create direct play URL
        val url = videosApi.getVideoStreamUrl(
            itemId = source.itemId,
            static = true,
            mediaSourceId = source.id,
            playSessionId = source.playSessionId,
        )

        // Select correct subtitle
        val selectedSubtitleStream = playOptions.subtitleStreamIndex?.let { index ->
            source.mediaStreams.getOrNull(index)
        }
        source.selectSubtitleStream(selectedSubtitleStream)

        // Build playback intent
        val playerIntent = Intent(Intent.ACTION_VIEW).apply {
            if (context.packageManager.isPackageInstalled(appPreferences.externalPlayerApp)) {
                component = getComponent(appPreferences.externalPlayerApp)
            }
            setDataAndType(url.toUri(), "video/*")
            putExtra("title", source.getName(context))
            putExtra("position", source.startTime.inWholeMilliseconds.toInt())
            putExtra("return_result", true)
            putExtra("secure_uri", true)

            val externalSubs = source.externalSubtitleStreams
            val enabledSubUrl = when {
                source.selectedSubtitleStream != null -> {
                    externalSubs.find { stream -> stream.index == source.selectedSubtitleStream?.index }?.let { sub ->
                        apiClient.createUrl(sub.deliveryUrl)
                    }
                }
                else -> null
            }

            // MX Player API / MPV
            val subtitleUris = externalSubs.map { stream ->
                apiClient.createUrl(stream.deliveryUrl).toUri()
            }
            putExtra("subs", subtitleUris.toTypedArray())
            putExtra("subs.name", externalSubs.map(ExternalSubtitleStream::displayTitle).toTypedArray())
            putExtra("subs.filename", externalSubs.map(ExternalSubtitleStream::language).toTypedArray())
            putExtra("subs.enable", enabledSubUrl?.let { url -> arrayOf(url.toUri()) } ?: emptyArray())

            // VLC
            if (enabledSubUrl != null) putExtra("subtitles_location", enabledSubUrl)
        }
        playlistSession.onSinglePlayerLaunch()
        try {
            playerContract.launch(playerIntent)
        } catch (error: Exception) {
            playlistSession.onSinglePlayerLaunchFailure()
            throw error
        }
        Timber.d(
            "Starting playback [id=${source.itemId}, title=${source.getName(context)}, " +
                "playMethod=${source.playMethod}, startTime=${source.startTime}]",
        )
    }

    private fun notifyEvent(event: String, parameters: String = "") {
        if (event in ALLOWED_EVENTS && parameters.isDigitsOnly()) {
            webappFunctionChannel.call("window.ExtPlayer.notify$event($parameters)")
        }
    }

    // https://github.com/mpv-android/mpv-android/commit/f70298fe23c4872ea04fe4f2a8b378b986460d98
    private fun handleMPVPlayer(resultCode: Int, data: Intent) {
        val player = "MPV Player"
        when (resultCode) {
            Activity.RESULT_OK -> {
                val position = data.getIntExtra("position", -1)
                when {
                    position > 0 -> {
                        Timber.d("Playback stopped [player=$player, position=$position]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE, "$position")
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    position == 0 -> {
                        Timber.d("Playback canceled [player=$player]")
                        notifyEvent(Constants.EVENT_CANCELED)
                    }
                    else -> {
                        Timber.d("Playback completed [player=$player]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE)
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                }
            }
            Activity.RESULT_CANCELED -> {
                Timber.d("Playback stopped by unknown error [player=$player]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
            else -> {
                Timber.d("Invalid state [player=$player, resultCode=$resultCode]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
        }
    }

    // https://sites.google.com/site/mxvpen/api
    private fun handleMXPlayer(resultCode: Int, data: Intent) {
        val player = "MX Player"
        when (resultCode) {
            Activity.RESULT_OK -> {
                when (val endBy = data.getStringExtra("end_by")) {
                    "playback_completion" -> {
                        Timber.d("Playback completed [player=$player]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE)
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    "user" -> {
                        val position = data.getIntExtra("position", -1)
                        val duration = data.getIntExtra("duration", -1)
                        when {
                            position > 0 -> {
                                Timber.d("Playback stopped [player=$player, position=$position, duration=$duration]")
                                notifyEvent(Constants.EVENT_TIME_UPDATE, "$position")
                                notifyEvent(Constants.EVENT_ENDED)
                            }
                            position == 0 -> {
                                Timber.d("Playback canceled [player=$player, position=$position, duration=$duration]")
                                notifyEvent(Constants.EVENT_CANCELED)
                            }
                            else -> {
                                Timber.d("Invalid state [player=$player, position=$position, duration=$duration]")
                                notifyEvent(Constants.EVENT_CANCELED)
                                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
                            }
                        }
                    }
                    else -> {
                        Timber.d("Invalid state [player=$player, endBy=$endBy]")
                        notifyEvent(Constants.EVENT_CANCELED)
                        context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
                    }
                }
            }
            Activity.RESULT_CANCELED -> {
                Timber.d("Playback stopped by user [player=$player]")
                notifyEvent(Constants.EVENT_CANCELED)
            }
            Activity.RESULT_FIRST_USER -> {
                Timber.d("Playback stopped by unknown error [player=$player]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
            else -> {
                Timber.d("Invalid state [player=$player, resultCode=$resultCode]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
        }
    }

    // https://wiki.videolan.org/Android_Player_Intents/
    private fun handleVLCPlayer(resultCode: Int, data: Intent) {
        val player = "VLC Player"
        when (resultCode) {
            Activity.RESULT_OK -> {
                val extraPosition = data.getLongExtra("extra_position", -1L)
                val extraDuration = data.getLongExtra("extra_duration", -1L)
                when {
                    extraDuration == extraPosition -> {
                        Timber.d("Playback completed [player=$player]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE)
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    extraPosition > 0L -> {
                        Timber.d(
                            "Playback stopped [player=$player, extraPosition=$extraPosition, extraDuration=$extraDuration]",
                        )
                        notifyEvent(Constants.EVENT_TIME_UPDATE, "$extraPosition")
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    else -> {
                        Timber.d(
                            "Playback canceled [player=$player, extraPosition=$extraPosition, extraDuration=$extraDuration]",
                        )
                        notifyEvent(Constants.EVENT_CANCELED)
                        if (extraPosition == -1L) {
                            context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
                        }
                    }
                }
            }
            else -> {
                Timber.d("Playback failed [player=$player, resultCode=$resultCode]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
        }
    }

    private fun handleMPVKTPlayer(resultCode: Int, data: Intent) {
        val player = "mpvKt Player"
        when (resultCode) {
            Activity.RESULT_OK -> {
                val position = data.getIntExtra("position", -1)
                when {
                    position > 0 -> {
                        Timber.d("Playback stopped [player=$player, position=$position]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE, "$position")
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                    position == 0 -> {
                        Timber.d("Playback canceled [player=$player]")
                        notifyEvent(Constants.EVENT_CANCELED)
                    }
                    else -> {
                        Timber.d("Playback completed [player=$player]")
                        notifyEvent(Constants.EVENT_TIME_UPDATE)
                        notifyEvent(Constants.EVENT_ENDED)
                    }
                }
            }
            else -> {
                Timber.d("Invalid state [player=$player, resultCode=$resultCode]")
                notifyEvent(Constants.EVENT_CANCELED)
                context.toast(R.string.external_player_unknown_error, Toast.LENGTH_LONG)
            }
        }
    }

    /**
     * To ensure that the correct activity is called.
     */
    private fun getComponent(@ExternalPlayerPackage packageName: String): ComponentName? {
        return when (packageName) {
            ExternalPlayerPackage.MPV_PLAYER -> {
                ComponentName(packageName, "$packageName.MPVActivity")
            }
            ExternalPlayerPackage.MX_PLAYER_FREE, ExternalPlayerPackage.MX_PLAYER_PRO -> {
                ComponentName(packageName, "$packageName.ActivityScreen")
            }
            ExternalPlayerPackage.VLC_PLAYER -> {
                ComponentName(packageName, "$packageName.StartActivity")
            }
            ExternalPlayerPackage.MPVKT_PLAYER -> {
                ComponentName(packageName, "$packageName.ui.player.PlayerActivity")
            }
            else -> null
        }
    }

    private fun capturePlaylistContext(): PlaylistContext? {
        val generation = appPreferences.playbackContextGeneration
        val baseUrl = apiClient.baseUrl?.takeIf(String::isNotBlank) ?: return null
        val accessToken = apiClient.accessToken?.takeIf(String::isNotBlank) ?: return null
        val serverId = appPreferences.currentServerId ?: return null
        val userId = appPreferences.currentUserId ?: return null
        return PlaylistContext(
            generation,
            baseUrl,
            accessToken,
            apiClient.deviceInfo.id,
            serverId,
            userId,
            appPreferences.externalPlayerApp,
            appPreferences.videoPlayerType,
        )
    }

    private fun isCurrentPlaylistContext(snapshot: PlaylistContext): Boolean =
        snapshot.generation == appPreferences.playbackContextGeneration &&
            playlistLifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            snapshot.baseUrl == apiClient.baseUrl &&
            snapshot.accessToken == apiClient.accessToken &&
            snapshot.deviceId == apiClient.deviceInfo.id &&
            snapshot.serverId == appPreferences.currentServerId &&
            snapshot.userId == appPreferences.currentUserId &&
            snapshot.playerPackage == appPreferences.externalPlayerApp &&
            snapshot.playerMode == appPreferences.videoPlayerType

    // Deliberately not a data class: its generated toString would expose the access token.
    private class PlaylistContext(
        val generation: Long,
        val baseUrl: String,
        val accessToken: String,
        val deviceId: String,
        val serverId: Long,
        val userId: Long,
        val playerPackage: String,
        val playerMode: String,
    )

    companion object {
        private const val PLAYLIST_STATE_KEY = "externalplayer.mx_playlist"
        private const val PLAYLIST_AWAITING_RESULT = "awaiting_result"
        private val PLAYLIST_SESSIONS = ExternalPlaylistSessionStore()
        private val ALLOWED_EVENTS = arrayOf(
            Constants.EVENT_CANCELED,
            Constants.EVENT_ENDED,
            Constants.EVENT_TIME_UPDATE,
        )
    }
}

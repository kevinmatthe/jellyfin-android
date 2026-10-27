package org.jellyfin.mobile.bridge

import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

internal class ExternalPlaylistSession(initiallyAwaitingResult: Boolean = false) {
    enum class Outcome { STARTED, FAILED, CANCELED, BUSY }

    private var generation = 0L
    private var resolvingGeneration: Long? = null
    private var awaitingResult = initiallyAwaitingResult
    private var singleResolutions = 0
    private var singlePlayerLaunches = 0

    suspend fun <T> play(
        ids: List<UUID>,
        isCurrent: () -> Boolean,
        resolve: suspend (UUID) -> T,
        launch: (List<T>) -> Unit,
    ): Outcome {
        val token = begin() ?: return Outcome.BUSY
        return try {
            val videos = ids.map { id ->
                if (!isGenerationCurrent(token) || !isCurrent()) return Outcome.CANCELED
                resolve(id)
            }
            if (launchIfCurrent(token, isCurrent) { launch(videos) }) Outcome.STARTED else Outcome.CANCELED
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Resolution and launch failures are supervised, with no partial queue or URL-bearing logs.
            Outcome.FAILED
        } finally {
            finish(token)
        }
    }

    /** True means this result belongs to a native queue and must never update the web player's item. */
    @Synchronized
    fun consumeResult(): Boolean {
        if (awaitingResult) {
            awaitingResult = false
            return true
        }
        if (singlePlayerLaunches > 0) singlePlayerLaunches--
        return false
    }

    @Synchronized
    fun invalidatePending() {
        resolvingGeneration = null
    }

    @Synchronized
    fun beginSinglePlayback(): Boolean {
        if (awaitingResult) return false
        invalidatePending()
        singleResolutions++
        return true
    }

    @Synchronized
    fun finishSingleResolution() {
        if (singleResolutions > 0) singleResolutions--
    }

    @Synchronized
    fun onSinglePlayerLaunch() {
        singlePlayerLaunches++
    }

    @Synchronized
    fun onSinglePlayerLaunchFailure() {
        if (singlePlayerLaunches > 0) singlePlayerLaunches--
    }

    @Synchronized
    fun isAwaitingResult(): Boolean = awaitingResult

    @Synchronized
    fun isBusy(): Boolean = resolvingGeneration != null || awaitingResult || singleResolutions > 0 || singlePlayerLaunches > 0

    @Synchronized
    private fun begin(): Long? {
        if (isBusy()) return null
        generation++
        resolvingGeneration = generation
        return generation
    }

    @Synchronized
    private fun isGenerationCurrent(token: Long): Boolean = resolvingGeneration == token

    @Synchronized
    private fun launchIfCurrent(token: Long, isCurrent: () -> Boolean, launch: () -> Unit): Boolean {
        if (resolvingGeneration != token || !isCurrent()) return false
        resolvingGeneration = null
        awaitingResult = true
        try {
            launch()
        } catch (error: Exception) {
            awaitingResult = false
            throw error
        }
        return true
    }

    @Synchronized
    private fun finish(token: Long) {
        if (resolvingGeneration == token) resolvingGeneration = null
    }
}

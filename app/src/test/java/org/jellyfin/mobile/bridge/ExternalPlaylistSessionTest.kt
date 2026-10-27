package org.jellyfin.mobile.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.cancellation.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ExternalPlaylistSessionTest {
    private val ids = listOf(UUID(0, 2), UUID(0, 1))

    @Test
    fun `launches once only after resolving the complete ordered queue`() = runBlocking {
        val session = ExternalPlaylistSession()
        val events = mutableListOf<String>()
        val outcome = session.play(ids, { true }, { id ->
            events += "resolve:$id"
            "video:$id"
        }) { videos -> events += "launch:${videos.joinToString()}" }

        assertEquals(ExternalPlaylistSession.Outcome.STARTED, outcome)
        assertEquals(listOf("resolve:${ids[0]}", "resolve:${ids[1]}", "launch:video:${ids[0]}, video:${ids[1]}"), events)
    }

    @Test
    fun `a partial resolution failure never launches any video`() = runBlocking {
        val session = ExternalPlaylistSession()
        var launches = 0
        val outcome = session.play(ids, { true }, { id ->
            if (id == ids.last()) error("Resolution failed")
            id
        }) { launches++ }

        assertEquals(ExternalPlaylistSession.Outcome.FAILED, outcome)
        assertEquals(0, launches)
        assertFalse(session.consumeResult())
        assertEquals(ExternalPlaylistSession.Outcome.STARTED, session.play(ids, { true }, { it }) { launches++ })
        assertEquals(1, launches)
    }

    @Test
    fun `a changed server account or player cancels before resolving another item`() = runBlocking {
        val session = ExternalPlaylistSession()
        var current = true
        var resolutions = 0
        var launches = 0
        val outcome = session.play(ids, { current }, {
            resolutions++
            current = false
            it
        }) { launches++ }

        assertEquals(ExternalPlaylistSession.Outcome.CANCELED, outcome)
        assertEquals(1, resolutions)
        assertEquals(0, launches)
    }

    @Test
    fun `checks the context again after resolving the final item`() = runBlocking {
        val session = ExternalPlaylistSession()
        var current = true
        var launches = 0
        val outcome = session.play(ids, { current }, { id ->
            if (id == ids.last()) current = false
            id
        }) { launches++ }

        assertEquals(ExternalPlaylistSession.Outcome.CANCELED, outcome)
        assertEquals(0, launches)
    }

    @Test
    fun `ignores a double tap while the queue is resolving`() = runBlocking {
        val session = ExternalPlaylistSession()
        val gate = CompletableDeferred<Unit>()
        var launches = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            session.play(ids, { true }, { gate.await(); it }) { launches++ }
        }

        assertEquals(ExternalPlaylistSession.Outcome.BUSY, session.play(ids, { true }, { it }) { launches++ })
        gate.complete(Unit)
        assertEquals(ExternalPlaylistSession.Outcome.STARTED, first.await())
        assertEquals(1, launches)
    }

    @Test
    fun `consumes native queue results without exposing them to normal web progress callbacks`() = runBlocking {
        val session = ExternalPlaylistSession()
        assertFalse(session.isBusy())
        assertFalse(session.consumeResult())
        session.play(ids, { true }, { it }) {}

        assertTrue(session.isBusy())
        assertEquals(ExternalPlaylistSession.Outcome.BUSY, session.play(ids, { true }, { it }) {})
        assertTrue(session.consumeResult())
        assertFalse(session.consumeResult())
        assertEquals(ExternalPlaylistSession.Outcome.STARTED, session.play(ids, { true }, { it }) {})
    }

    @Test
    fun `a newer single playback request invalidates a pending queue`() = runBlocking {
        val session = ExternalPlaylistSession()
        var launches = 0
        val outcome = session.play(ids, { true }, {
            session.invalidatePending()
            it
        }) { launches++ }

        assertEquals(ExternalPlaylistSession.Outcome.CANCELED, outcome)
        assertEquals(0, launches)
        assertFalse(session.consumeResult())
    }

    @Test
    fun `a failed Android player launch releases the native result mode`() = runBlocking {
        val session = ExternalPlaylistSession()

        assertEquals(ExternalPlaylistSession.Outcome.FAILED, session.play(ids, { true }, { it }) { error("Player not installed") })
        assertFalse(session.consumeResult())
        assertEquals(ExternalPlaylistSession.Outcome.STARTED, session.play(ids, { true }, { it }) {})
    }

    @Test
    fun `lifecycle cancellation is propagated and leaves no pending launch`() = runBlocking {
        val session = ExternalPlaylistSession()
        var canceled = false
        try {
            session.play(ids, { true }, { throw CancellationException("Destroyed") }) {}
        } catch (error: CancellationException) {
            canceled = true
        }

        assertTrue(canceled)
        assertFalse(session.consumeResult())
        assertEquals(ExternalPlaylistSession.Outcome.STARTED, session.play(ids, { true }, { it }) {})
    }

    @Test
    fun `restored native playback still consumes its result after activity recreation`() {
        val session = ExternalPlaylistSession(initiallyAwaitingResult = true)

        assertTrue(session.isAwaitingResult())
        assertTrue(session.consumeResult())
        assertFalse(session.isAwaitingResult())
    }

    @Test
    fun `does not launch a native queue over an in-flight normal playback request`() = runBlocking {
        val session = ExternalPlaylistSession()
        assertTrue(session.beginSinglePlayback())

        assertEquals(ExternalPlaylistSession.Outcome.BUSY, session.play(ids, { true }, { it }) {})
        session.onSinglePlayerLaunch()
        session.finishSingleResolution()
        assertEquals(ExternalPlaylistSession.Outcome.BUSY, session.play(ids, { true }, { it }) {})
        assertFalse(session.consumeResult())
        assertEquals(ExternalPlaylistSession.Outcome.STARTED, session.play(ids, { true }, { it }) {})
        assertFalse(session.beginSinglePlayback())
    }

    @Test
    fun `a newer normal request cancels native resolution and retains normal callbacks`() = runBlocking {
        val session = ExternalPlaylistSession()
        var launches = 0
        val outcome = session.play(ids, { true }, {
            assertTrue(session.beginSinglePlayback())
            session.onSinglePlayerLaunch()
            session.finishSingleResolution()
            it
        }) { launches++ }

        assertEquals(ExternalPlaylistSession.Outcome.CANCELED, outcome)
        assertEquals(0, launches)
        assertFalse(session.consumeResult())
    }
}

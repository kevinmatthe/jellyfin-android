package org.jellyfin.mobile.bridge

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ExternalPlaylistSessionStoreTest {
    @Test
    fun `new fragments in the same activity share result ownership and one saved state provider`() = runBlocking {
        val store = ExternalPlaylistSessionStore()
        val activity = Any()
        val providers = mutableListOf<() -> Boolean>()
        val first = store.get(activity, { false }) { providers += it }
        first.play(listOf(UUID(0, 1)), { true }, { it }) {}

        val replacement = store.get(activity, { false }) { providers += it }

        assertSame(first, replacement)
        assertEquals(1, providers.size)
        assertTrue(providers.single()())
        assertTrue(replacement.consumeResult())
        assertFalse(providers.single()())
    }

    @Test
    fun `different activities have independent result ownership`() = runBlocking {
        val store = ExternalPlaylistSessionStore()
        val first = store.get(Any(), { false }) {}
        first.play(listOf(UUID(0, 1)), { true }, { it }) {}
        val other = store.get(Any(), { false }) {}

        assertFalse(other.consumeResult())
        assertTrue(first.consumeResult())
    }

    @Test
    fun `restores only the pending native result flag after process recreation`() {
        val store = ExternalPlaylistSessionStore()
        var provider: (() -> Boolean)? = null
        val restored = store.get(Any(), { true }) { provider = it }

        assertTrue(provider!!.invoke())
        assertTrue(restored.consumeResult())
        assertFalse(provider!!.invoke())
    }
}

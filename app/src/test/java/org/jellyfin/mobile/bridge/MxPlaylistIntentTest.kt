package org.jellyfin.mobile.bridge

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class MxPlaylistIntentTest {
    @Test
    fun `passes an explicit Parcelable Uri array with matching names in order`() {
        val first = mockk<Uri>()
        val second = mockk<Uri>()
        val component = mockk<ComponentName>()
        val intent = mockk<Intent>()
        var uris: Array<out Parcelable>? = null
        var names: Array<String>? = null
        every { intent.setComponent(component) } returns intent
        every { intent.setDataAndType(first, "video/*") } returns intent
        every { intent.putExtra(any<String>(), any<Boolean>()) } returns intent
        every { intent.putExtra("title", "First") } returns intent
        every { intent.putExtra("video_list", any<Array<out Parcelable>>()) } answers {
            uris = secondArg()
            self as Intent
        }
        every { intent.putExtra("video_list.name", any<Array<String>>()) } answers {
            names = secondArg()
            self as Intent
        }

        intent.withMxPlaylist(component, listOf(MxPlaylistVideo(first, "First"), MxPlaylistVideo(second, "Second")))

        assertEquals(Uri::class.java, uris?.javaClass?.componentType)
        assertSame(first, uris?.get(0))
        assertSame(second, uris?.get(1))
        assertArrayEquals(arrayOf("First", "Second"), names)
        verifyOrder {
            intent.setDataAndType(first, "video/*")
            intent.putExtra("video_list", any<Array<out Parcelable>>())
            intent.putExtra("video_list.name", any<Array<String>>())
            intent.putExtra("video_list_is_explicit", true)
            intent.putExtra("return_result", true)
            intent.putExtra("secure_uri", true)
        }
    }
}

package org.jellyfin.mobile.bridge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class ExternalPlaylistRequestTest {
    private val first = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val second = UUID.fromString("abcdefab-cdef-4abc-8def-abcdefabcdef")

    @Test
    fun `preserves requested order and accepts compact Jellyfin UUIDs`() {
        val request = ExternalPlaylistRequest.parse("""{"ids":["${second.toString().replace("-", "")}","$first"]}""")

        assertEquals(listOf(second, first), request?.ids)
    }

    @Test
    fun `rejects malformed missing empty or partially invalid ids`() {
        val invalid = listOf(
            "", "{", "[]", "{}", "null", """{"ids":[]}""", """{"ids":"$first"}""",
            """{"ids":["$first",null]}""", """{"ids":["$first",false]}""",
            """{"ids":["$first",123]}""", """{"ids":["$first",{}]}""",
            """{"ids":["$first","bad-id"]}""", """{"ids":["1-1-1-1-1"]}""",
            """{"ids":[" $first"]}""",
        )

        invalid.forEach { assertNull(ExternalPlaylistRequest.parse(it), it) }
    }

    @Test
    fun `rejects duplicate ids after UUID normalization`() {
        assertNull(ExternalPlaylistRequest.parse("""{"ids":["$second","${second.toString().uppercase().replace("-", "")}"]}"""))
    }

    @Test
    fun `rejects arbitrary URLs and other unexpected request parameters`() {
        assertNull(ExternalPlaylistRequest.parse("""{"ids":["$first"],"url":"https://example.invalid/video"}"""))
        assertNull(ExternalPlaylistRequest.parse("""{"ids":["$first"],"startIndex":1}"""))
    }

    @Test
    fun `accepts at most 200 unique ids`() {
        fun json(count: Int) = (1..count).joinToString(prefix = "{\"ids\":[", postfix = "]}") { "\"${UUID(0L, it.toLong())}\"" }

        assertEquals(200, ExternalPlaylistRequest.parse(json(200))?.ids?.size)
        assertNull(ExternalPlaylistRequest.parse(json(201)))
    }
}

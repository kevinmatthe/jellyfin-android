package org.jellyfin.mobile.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

internal data class ExternalPlaylistRequest(val ids: List<UUID>) {
    companion object {
        private const val MAX_ITEMS = 200
        private const val MAX_REQUEST_LENGTH = 16_384
        private val UUID_FORMAT = Regex(
            "(?:[0-9a-fA-F]{32}|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})",
        )

        fun parse(args: String): ExternalPlaylistRequest? {
            if (args.length > MAX_REQUEST_LENGTH) return null
            return try {
                val options = Json.parseToJsonElement(args) as? JsonObject ?: return null
                // The bridge accepts item IDs only. URLs and normal single-player options are not valid here.
                if (options.keys != setOf("ids")) return null
                val values = options["ids"] as? JsonArray ?: return null
                if (values.size !in 1..MAX_ITEMS) return null
                val ids = values.map { value ->
                    val primitive = value as? JsonPrimitive ?: return null
                    if (!primitive.isString || !UUID_FORMAT.matches(primitive.content)) return null
                    primitive.content.toUUIDOrNull() ?: return null
                }
                if (ids.distinct().size != ids.size) return null
                ExternalPlaylistRequest(ids)
            } catch (_: Exception) {
                // Never log bridge arguments: a rejected request may contain credentials or URLs.
                null
            }
        }
    }
}

package com.cordiact.core.attachment

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Versioned JSON codec for ordered message parts.
 *
 * The database stores one row per part; this codec is used for backup manifests, draft overlays
 * and for the migration of rows that only have `metadataJson`. Unknown versions and unknown part
 * kinds fail closed: silently dropping an attachment would be worse than refusing to load.
 */
object MessagePartsJson {
    const val SCHEMA_VERSION = 1

    fun encode(parts: List<MessagePart>): String {
        val root = JsonObject()
        root.addProperty("v", SCHEMA_VERSION)
        val array = JsonArray()
        for (part in parts) {
            val element = JsonObject()
            when (part) {
                is MessagePart.Text -> {
                    element.addProperty("kind", "text")
                    element.addProperty("text", part.text)
                }

                is MessagePart.Attachment -> {
                    element.addProperty("kind", "attachment")
                    element.addProperty("id", part.id.value)
                    element.addProperty("interpretation", part.interpretation.name)
                    part.renditionId?.let { element.addProperty("renditionId", it) }
                }
            }
            array.add(element)
        }
        root.add("parts", array)
        return root.toString()
    }

    fun decode(json: String): List<MessagePart> {
        val root = try {
            JsonParser.parseString(json)
        } catch (failure: RuntimeException) {
            throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "Malformed message parts JSON", failure)
        }
        if (!root.isJsonObject) {
            throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "Malformed message parts JSON")
        }
        val objectRoot = root.asJsonObject
        val version = objectRoot.get("v")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        if (version != SCHEMA_VERSION) {
            throw AttachmentException(
                AttachmentErrorCode.INVALID_MEDIA_TYPE,
                "Unsupported message parts schema version $version",
            )
        }
        val array = objectRoot.get("parts")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "Message parts are missing")
        return array.mapIndexed { index, element -> decodePart(element, index) }
    }

    private fun decodePart(element: JsonElement, index: Int): MessagePart {
        if (!element.isJsonObject) {
            throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "Part $index is not an object")
        }
        val part = element.asJsonObject
        return when (val kind = part.string("kind")) {
            "text" -> MessagePart.Text(part.string("text") ?: "")
            "attachment" -> {
                val id = part.string("id")
                    ?: throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "Part $index has no attachment id")
                val interpretationName = part.string("interpretation")
                    ?: throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "Part $index has no interpretation")
                val interpretation = try {
                    Interpretation.valueOf(interpretationName)
                } catch (_: IllegalArgumentException) {
                    throw AttachmentException(
                        AttachmentErrorCode.INVALID_MEDIA_TYPE,
                        "Part $index has unknown interpretation $interpretationName",
                    )
                }
                MessagePart.Attachment(
                    id = AttachmentId(id),
                    interpretation = interpretation,
                    renditionId = part.string("renditionId"),
                )
            }

            else -> throw AttachmentException(AttachmentErrorCode.INVALID_MEDIA_TYPE, "Part $index has unknown kind $kind")
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
}

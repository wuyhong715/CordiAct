package com.cordiact.model.providers.attachment

import com.cordiact.core.attachment.AttachmentErrorCode
import com.cordiact.core.attachment.AttachmentException
import com.cordiact.core.attachment.AttachmentFormats
import com.cordiact.core.attachment.AttachmentMediaKind
import com.cordiact.core.attachment.Interpretation
import com.cordiact.core.attachment.PlannedAttachment
import com.cordiact.core.attachment.TransmissionPlan
import com.cordiact.model.providers.ModelProtocol
import com.google.gson.JsonObject
import java.util.Base64

/** Reads the exact stored bytes for a planned attachment. Never a path; always a scoped handle. */
fun interface AttachmentPayloadSource {
    fun read(attachment: PlannedAttachment): ByteArray
}

/**
 * Encodes one planned attachment into the content block shape of each protocol.
 *
 * The four protocols do not share a content array: `image_url` is not `input_image`, and
 * `inlineData` is not `document`. Each branch is built here, from the same validated
 * [PlannedAttachment], so a protocol cannot accidentally receive another protocol's field names.
 *
 * This class only encodes; capability, size and budget decisions stay in [com.cordiact.core.attachment.AttachmentPlanner].
 */
class AttachmentWireEncoder(private val payloads: AttachmentPayloadSource) {

    fun encode(protocol: ModelProtocol, attachment: PlannedAttachment): JsonObject =
        when (attachment.interpretation) {
            Interpretation.TEXT_EXTRACT -> textBlock(protocol, readText(attachment))
            Interpretation.VISION -> imageBlock(protocol, attachment)
            Interpretation.NATIVE_DOCUMENT -> documentBlock(protocol, attachment)
            Interpretation.LOCAL_ONLY -> throw AttachmentException(
                AttachmentErrorCode.LOCAL_ONLY_PART,
                "A LOCAL_ONLY attachment has no wire representation",
            )
        }

    fun encodeAll(protocol: ModelProtocol, plan: TransmissionPlan): List<JsonObject> =
        plan.attachments.map { encode(protocol, it) }

    /**
     * Encodes the whole plan in message order, interleaving text and media.
     *
     * Order matters: "look at this then that" must not become "look at that then this". The plan
     * keeps the user's order, so the blocks are produced by walking [TransmissionPlan.parts] rather
     * than by appending all text and then all media.
     */
    fun encodeOrdered(protocol: ModelProtocol, plan: TransmissionPlan): List<JsonObject> {
        val byKey = plan.attachments.associateBy { it.attachmentId.value to it.renditionId }
        return plan.parts.map { part ->
            when (part) {
                is com.cordiact.core.attachment.MessagePart.Text -> textBlock(protocol, part.text)
                is com.cordiact.core.attachment.MessagePart.Attachment -> {
                    val planned = byKey[part.id.value to part.renditionId]
                        ?: throw AttachmentException(
                            AttachmentErrorCode.UNKNOWN_ATTACHMENT,
                            "The plan has no encoded entry for ${part.id.value}",
                        )
                    encode(protocol, planned)
                }
            }
        }
    }

    private fun textBlock(protocol: ModelProtocol, text: String): JsonObject = JsonObject().apply {
        when (protocol) {
            ModelProtocol.OPENAI_CHAT_COMPLETIONS -> {
                addProperty("type", "text")
                addProperty("text", text)
            }

            ModelProtocol.OPENAI_RESPONSES -> {
                addProperty("type", "input_text")
                addProperty("text", text)
            }

            ModelProtocol.ANTHROPIC_MESSAGES -> {
                addProperty("type", "text")
                addProperty("text", text)
            }

            ModelProtocol.GEMINI_GENERATE_CONTENT -> addProperty("text", text)
        }
    }

    private fun imageBlock(protocol: ModelProtocol, attachment: PlannedAttachment): JsonObject {
        requireKind(attachment, AttachmentMediaKind.IMAGE, "VISION")
        val bytes = payloads.read(attachment)
        val data = Base64.getEncoder().encodeToString(bytes)
        val dataUrl = "data:${attachment.mimeType};base64,$data"
        return JsonObject().apply {
            when (protocol) {
                ModelProtocol.OPENAI_CHAT_COMPLETIONS -> {
                    addProperty("type", "image_url")
                    add("image_url", JsonObject().apply { addProperty("url", dataUrl) })
                }

                ModelProtocol.OPENAI_RESPONSES -> {
                    addProperty("type", "input_image")
                    addProperty("image_url", dataUrl)
                }

                ModelProtocol.ANTHROPIC_MESSAGES -> {
                    addProperty("type", "image")
                    add("source", JsonObject().apply {
                        addProperty("type", "base64")
                        addProperty("media_type", attachment.mimeType)
                        addProperty("data", data)
                    })
                }

                ModelProtocol.GEMINI_GENERATE_CONTENT -> {
                    add("inlineData", JsonObject().apply {
                        addProperty("mimeType", attachment.mimeType)
                        addProperty("data", data)
                    })
                }
            }
        }
    }

    private fun documentBlock(protocol: ModelProtocol, attachment: PlannedAttachment): JsonObject {
        requireKind(attachment, AttachmentMediaKind.PDF, "NATIVE_DOCUMENT")
        val bytes = payloads.read(attachment)
        val data = Base64.getEncoder().encodeToString(bytes)
        val dataUrl = "data:${attachment.mimeType};base64,$data"
        return JsonObject().apply {
            when (protocol) {
                ModelProtocol.OPENAI_CHAT_COMPLETIONS -> {
                    addProperty("type", "file")
                    add("file", JsonObject().apply {
                        addProperty("filename", attachment.displayName)
                        addProperty("file_data", dataUrl)
                    })
                }

                ModelProtocol.OPENAI_RESPONSES -> {
                    addProperty("type", "input_file")
                    addProperty("filename", attachment.displayName)
                    addProperty("file_data", dataUrl)
                }

                ModelProtocol.ANTHROPIC_MESSAGES -> {
                    addProperty("type", "document")
                    add("source", JsonObject().apply {
                        addProperty("type", "base64")
                        addProperty("media_type", attachment.mimeType)
                        addProperty("data", data)
                    })
                }

                ModelProtocol.GEMINI_GENERATE_CONTENT -> {
                    add("inlineData", JsonObject().apply {
                        addProperty("mimeType", attachment.mimeType)
                        addProperty("data", data)
                    })
                }
            }
        }
    }

    private fun readText(attachment: PlannedAttachment): String {
        requireKind(attachment, AttachmentMediaKind.TEXT, "TEXT_EXTRACT")
        val bytes = payloads.read(attachment)
        return AttachmentFormats.decodeUtf8OrNull(bytes)
            ?: throw AttachmentException(
                AttachmentErrorCode.INVALID_MEDIA_TYPE,
                "${attachment.displayName} is not valid UTF-8 and cannot be sent as text",
            )
    }

    private fun requireKind(attachment: PlannedAttachment, expected: AttachmentMediaKind, mode: String) {
        val actual = AttachmentFormats.kindOf(attachment.mimeType)
        if (actual != expected) {
            throw AttachmentException(
                AttachmentErrorCode.UNSUPPORTED_INTERPRETATION,
                "$mode cannot encode ${attachment.mimeType}",
            )
        }
    }

}

package com.cordiact.model.providers.attachment

import com.cordiact.core.attachment.AttachmentId
import com.cordiact.core.attachment.Interpretation
import com.cordiact.core.attachment.PlannedAttachment
import com.cordiact.model.providers.ModelProtocol
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttachmentWireEncoderTest {
    private val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val pdfBytes = "%PDF-1.7".toByteArray()
    private val textBytes = "hello".toByteArray()
    private val base64Png = Base64.getEncoder().encodeToString(pngBytes)
    private val base64Pdf = Base64.getEncoder().encodeToString(pdfBytes)

    private val image = PlannedAttachment(
        attachmentId = AttachmentId("att-image"),
        renditionId = null,
        mimeType = "image/png",
        interpretation = Interpretation.VISION,
        byteSize = pngBytes.size.toLong(),
        sha256 = "a".repeat(64),
        displayName = "photo.png",
    )
    private val pdf = PlannedAttachment(
        attachmentId = AttachmentId("att-pdf"),
        renditionId = null,
        mimeType = "application/pdf",
        interpretation = Interpretation.NATIVE_DOCUMENT,
        byteSize = pdfBytes.size.toLong(),
        sha256 = "b".repeat(64),
        displayName = "report.pdf",
    )
    private val text = PlannedAttachment(
        attachmentId = AttachmentId("att-text"),
        renditionId = null,
        mimeType = "text/plain",
        interpretation = Interpretation.TEXT_EXTRACT,
        byteSize = textBytes.size.toLong(),
        sha256 = "c".repeat(64),
        displayName = "notes.txt",
    )

    private val encoder = AttachmentWireEncoder { attachment ->
        when (attachment.attachmentId.value) {
            "att-image" -> pngBytes
            "att-pdf" -> pdfBytes
            else -> textBytes
        }
    }

    @Test
    fun openAiChatUsesImageUrlAndFileBlocks() {
        val imageBlock = encoder.encode(ModelProtocol.OPENAI_CHAT_COMPLETIONS, image)
        assertEquals("image_url", imageBlock["type"].asString)
        assertEquals("data:image/png;base64,$base64Png", imageBlock["image_url"].asJsonObject["url"].asString)

        val fileBlock = encoder.encode(ModelProtocol.OPENAI_CHAT_COMPLETIONS, pdf)
        assertEquals("file", fileBlock["type"].asString)
        assertEquals("report.pdf", fileBlock["file"].asJsonObject["filename"].asString)
        assertEquals("data:application/pdf;base64,$base64Pdf", fileBlock["file"].asJsonObject["file_data"].asString)
    }

    @Test
    fun openAiResponsesUsesInputTypes() {
        val imageBlock = encoder.encode(ModelProtocol.OPENAI_RESPONSES, image)
        assertEquals("input_image", imageBlock["type"].asString)
        assertTrue(imageBlock["image_url"].asString.startsWith("data:image/png;base64,"))

        val fileBlock = encoder.encode(ModelProtocol.OPENAI_RESPONSES, pdf)
        assertEquals("input_file", fileBlock["type"].asString)
        assertTrue(fileBlock["file_data"].asString.startsWith("data:application/pdf;base64,"))
    }

    @Test
    fun anthropicUsesBase64Source() {
        val block = encoder.encode(ModelProtocol.ANTHROPIC_MESSAGES, image)
        assertEquals("image", block["type"].asString)
        assertEquals("base64", block["source"].asJsonObject["type"].asString)
        assertEquals("image/png", block["source"].asJsonObject["media_type"].asString)
        assertEquals(base64Png, block["source"].asJsonObject["data"].asString)

        val document = encoder.encode(ModelProtocol.ANTHROPIC_MESSAGES, pdf)
        assertEquals("document", document["type"].asString)
        assertEquals("application/pdf", document["source"].asJsonObject["media_type"].asString)
    }

    @Test
    fun geminiUsesInlineData() {
        val block = encoder.encode(ModelProtocol.GEMINI_GENERATE_CONTENT, image)
        assertEquals("image/png", block["inlineData"].asJsonObject["mimeType"].asString)
        assertEquals(base64Png, block["inlineData"].asJsonObject["data"].asString)
    }

    @Test
    fun textProjectionIsDecodedNotBase64Encoded() {
        val block = encoder.encode(ModelProtocol.OPENAI_CHAT_COMPLETIONS, text)
        assertEquals("text", block["type"].asString)
        assertEquals("hello", block["text"].asString)
    }

    @Test
    fun encodeAllPreservesPlanOrder() {
        val plan = com.cordiact.core.attachment.TransmissionPlan(
            providerId = "p", modelId = "m", protocol = "openai-chat",
            endpointRevision = "r1", policyRevision = "p1",
            workspaceId = "w", sessionId = "s",
            parts = emptyList(),
            attachments = listOf(image, pdf, text),
            textBytes = 0, attachmentBytes = 0, estimatedRequestBytes = 0,
        )
        val blocks = encoder.encodeAll(ModelProtocol.OPENAI_CHAT_COMPLETIONS, plan)
        assertEquals(listOf("image_url", "file", "text"), blocks.map { it["type"].asString })
    }
}

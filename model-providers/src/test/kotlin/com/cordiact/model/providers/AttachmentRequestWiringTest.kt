package com.cordiact.model.providers

import com.cordiact.core.attachment.AttachmentDescriptor
import com.cordiact.core.attachment.AttachmentErrorCode
import com.cordiact.core.attachment.AttachmentException
import com.cordiact.core.attachment.AttachmentId
import com.cordiact.core.attachment.AttachmentInputCapability
import com.cordiact.core.attachment.AttachmentReader
import com.cordiact.core.attachment.AttachmentRendition
import com.cordiact.core.attachment.AttachmentState
import com.cordiact.core.attachment.Interpretation
import com.cordiact.core.attachment.MessagePart
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ModelMessage
import com.google.gson.JsonArray
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies that attachment parts become protocol-specific content blocks and that every refusal
 * happens before a request body can be built.
 */
class AttachmentRequestWiringTest {
    private val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
    private val descriptor = AttachmentDescriptor(
        id = AttachmentId("att-image"),
        workspaceId = "ws-1",
        mimeType = "image/png",
        displayName = "photo.png",
        byteSize = imageBytes.size.toLong(),
        sha256 = "a".repeat(64),
        state = AttachmentState.READY,
        createdAtMillis = 1,
        verifiedAtMillis = 1,
        width = 1,
        height = 1,
    )

    private val reader = object : AttachmentReader {
        override fun find(workspaceId: String, id: AttachmentId): AttachmentDescriptor? =
            descriptor.takeIf { workspaceId == "ws-1" && id == descriptor.id }

        override fun rendition(workspaceId: String, attachmentId: AttachmentId, renditionId: String): AttachmentRendition? = null

        override fun open(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): InputStream =
            ByteArrayInputStream(imageBytes)

        override fun verify(workspaceId: String, attachmentId: AttachmentId, renditionId: String?): Boolean = true
    }

    private val capability = AttachmentInputCapability(
        interpretations = setOf(Interpretation.VISION),
        mimeTypes = setOf("image/png"),
        maxFileBytes = 1_000_000,
        maxRequestBytes = 1_000_000,
        maxCount = 4,
    )

    @Test
    fun openAiChatContentBecomesAnArrayOfBlocks() {
        val backend = backend(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
        val request = request()
        val body = backend.requestBody(request, backend.preflightAttachments(request))
        val content = body["messages"].asJsonArray[0].asJsonObject["content"].asJsonArray
        assertEquals(2, content.size())
        assertEquals("text", content[0].asJsonObject["type"].asString)
        assertEquals("look", content[0].asJsonObject["text"].asString)
        assertEquals("image_url", content[1].asJsonObject["type"].asString)
        assertTrue(content[1].asJsonObject["image_url"].asJsonObject["url"].asString.startsWith("data:image/png;base64,"))
    }

    @Test
    fun openAiResponsesUsesInputBlocks() {
        val backend = backend(ModelProtocol.OPENAI_RESPONSES)
        val request = request()
        val body = backend.requestBody(request, backend.preflightAttachments(request))
        val content = body["input"].asJsonArray[0].asJsonObject["content"].asJsonArray
        assertEquals("input_text", content[0].asJsonObject["type"].asString)
        assertEquals("input_image", content[1].asJsonObject["type"].asString)
    }

    @Test
    fun anthropicUsesBase64Source() {
        val backend = backend(ModelProtocol.ANTHROPIC_MESSAGES)
        val request = request()
        val body = backend.requestBody(request, backend.preflightAttachments(request))
        val content = body["messages"].asJsonArray[0].asJsonObject["content"].asJsonArray
        assertEquals("text", content[0].asJsonObject["type"].asString)
        assertEquals("image", content[1].asJsonObject["type"].asString)
        assertEquals("image/png", content[1].asJsonObject["source"].asJsonObject["media_type"].asString)
    }

    @Test
    fun geminiUsesInlineDataParts() {
        val backend = backend(ModelProtocol.GEMINI_GENERATE_CONTENT)
        val request = request()
        val body = backend.requestBody(request, backend.preflightAttachments(request))
        val parts = body["contents"].asJsonArray[0].asJsonObject["parts"].asJsonArray
        assertEquals("look", parts[0].asJsonObject["text"].asString)
        assertEquals("image/png", parts[1].asJsonObject["inlineData"].asJsonObject["mimeType"].asString)
    }

    @Test
    fun allowedSendIsAuditedPerAttachmentWithoutContent() {
        val events = mutableListOf<com.cordiact.core.attachment.AttachmentAuditEvent>()
        val backend = ConfigurableModelBackend(
            config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, capability),
            ApiKeyProvider { "key" },
            attachmentReader = reader,
            attachmentAudit = { events += it },
        )
        backend.preflightAttachments(request())
        val event = events.single()
        assertEquals(com.cordiact.core.attachment.AttachmentAuditAction.SEND, event.action)
        assertEquals(com.cordiact.core.attachment.AttachmentAuditDecision.ALLOWED, event.decision)
        assertEquals("att-image", event.attachmentId)
        assertEquals(descriptor.sha256, event.sha256)
        assertEquals(16, event.endpointFingerprint?.length)
        assertFalse(event.toString().contains("look"))
    }

    @Test
    fun refusalIsAuditedAsDenied() {
        val events = mutableListOf<com.cordiact.core.attachment.AttachmentAuditEvent>()
        val backend = ConfigurableModelBackend(
            config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, attachmentCapability = null),
            ApiKeyProvider { "key" },
            attachmentReader = reader,
            attachmentAudit = { events += it },
        )
        assertFailsWith<AttachmentException> { backend.preflightAttachments(request()) }
        val event = events.single()
        assertEquals(com.cordiact.core.attachment.AttachmentAuditDecision.DENIED, event.decision)
        assertEquals(AttachmentErrorCode.UNSUPPORTED_CAPABILITY.name, event.reasonCode)
    }

    @Test
    fun unavailableAuditLogAbortsTheSend() {
        val backend = ConfigurableModelBackend(
            config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, capability),
            ApiKeyProvider { "key" },
            attachmentReader = reader,
            attachmentAudit = { throw IllegalStateException("audit is locked") },
        )
        val failure = assertFailsWith<AttachmentException> { backend.preflightAttachments(request()) }
        assertEquals(AttachmentErrorCode.STORAGE_FAILURE, failure.code)
    }

    @Test
    fun generateEmitsAttachmentRejectedBeforeAnyNetworkWork() {
        val adapter = com.cordiact.core.conversation.ModelAdapterPlugin()
        adapter.onEnable()
        val backend = ConfigurableModelBackend(
            config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, attachmentCapability = null),
            ApiKeyProvider { "key" },
            attachmentReader = reader,
        )
        adapter.attachBackend("test-owner", backend)
        val events = mutableListOf<com.cordiact.core.conversation.ModelEvent>()
        try {
            adapter.generate(request()) { events += it }
            val failed = events.single() as com.cordiact.core.conversation.ModelEvent.Failed
            assertEquals("attachment_rejected", failed.code)
        } finally {
            backend.close()
            adapter.onDisable()
        }
    }

    @Test
    fun refusesBeforeBuildingWithoutDeclaredCapability() {
        val backend = ConfigurableModelBackend(
            config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, attachmentCapability = null),
            ApiKeyProvider { "key" },
            attachmentReader = reader,
        )
        val failure = assertFailsWith<AttachmentException> { backend.preflightAttachments(request()) }
        assertEquals(AttachmentErrorCode.UNSUPPORTED_CAPABILITY, failure.code)
    }

    @Test
    fun refusesWhenNoAttachmentStoreIsWired() {
        val backend = ConfigurableModelBackend(
            config(ModelProtocol.OPENAI_CHAT_COMPLETIONS, attachmentCapability = capability),
            ApiKeyProvider { "key" },
            attachmentReader = null,
        )
        val failure = assertFailsWith<AttachmentException> { backend.preflightAttachments(request()) }
        assertEquals(AttachmentErrorCode.STORAGE_FAILURE, failure.code)
    }

    @Test
    fun refusesWithoutWorkspaceContext() {
        val backend = backend(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
        val failure = assertFailsWith<AttachmentException> {
            backend.preflightAttachments(request(workspaceId = null))
        }
        assertEquals(AttachmentErrorCode.CROSS_WORKSPACE, failure.code)
    }

    @Test
    fun requestBodyRefusesAnUnplannedAttachmentMessage() {
        val backend = backend(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
        val failure = assertFailsWith<AttachmentException> { backend.requestBody(request()) }
        assertEquals(AttachmentErrorCode.UNSUPPORTED_CAPABILITY, failure.code)
    }

    @Test
    fun refusesWhenTextIsNotRepresentedInParts() {
        val backend = backend(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
        val request = request(content = "text that the parts do not contain")
        val failure = assertFailsWith<AttachmentException> { backend.preflightAttachments(request) }
        assertEquals(AttachmentErrorCode.EMPTY_MESSAGE, failure.code)
    }

    @Test
    fun keepsSelectionOrderAcrossTextAndImages() {
        val backend = backend(ModelProtocol.OPENAI_CHAT_COMPLETIONS)
        val parts = listOf(
            MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION),
            MessagePart.Text("middle"),
        )
        val request = ModelGenerationRequest(
            sessionId = "s-1",
            model = ModelId("test", "model"),
            messages = listOf(ModelMessage(MessageRole.USER, "middle", parts = parts)),
            workspaceId = "ws-1",
        )
        val body = backend.requestBody(request, backend.preflightAttachments(request))
        val content = body["messages"].asJsonArray[0].asJsonObject["content"].asJsonArray
        assertEquals("image_url", content[0].asJsonObject["type"].asString)
        assertEquals("text", content[1].asJsonObject["type"].asString)
    }

    private fun backend(protocol: ModelProtocol) = ConfigurableModelBackend(
        config(protocol, capability),
        ApiKeyProvider { "key" },
        attachmentReader = reader,
    )

    private fun config(
        protocol: ModelProtocol,
        attachmentCapability: AttachmentInputCapability?,
    ) = ModelProviderConfig.forLoopbackTest(
        providerId = "test",
        displayName = "Test",
        protocol = protocol,
        endpoint = if (protocol == ModelProtocol.GEMINI_GENERATE_CONTENT) {
            "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
        } else {
            "https://api.example.test/v1/request"
        },
        models = listOf("model"),
        attachmentCapabilities = attachmentCapability?.let { mapOf("model" to it) }.orEmpty(),
    )

    private fun request(workspaceId: String? = "ws-1", content: String = "look"): ModelGenerationRequest =
        ModelGenerationRequest(
            sessionId = "s-1",
            model = ModelId("test", "model"),
            messages = listOf(
                ModelMessage(
                    role = MessageRole.USER,
                    content = content,
                    parts = listOf(
                        MessagePart.Text("look"),
                        MessagePart.Attachment(AttachmentId("att-image"), Interpretation.VISION),
                    ),
                ),
            ),
            workspaceId = workspaceId,
        )
}

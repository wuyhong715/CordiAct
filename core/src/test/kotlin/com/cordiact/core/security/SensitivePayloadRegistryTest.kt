package com.cordiact.core.security

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class SensitivePayloadRegistryTest {
    @Test
    fun `plaintext is substituted once for the approved route only`() {
        val registry = SensitivePayloadRegistry()
        val original = listOf(
            ModelMessage(MessageRole.USER, "read it"),
            ModelMessage(
                role = MessageRole.TOOL,
                content = "credential delivered through the approved sensitive-result path",
                toolCallId = "call-1",
                toolName = "credential_read",
            ),
        )
        registry.register("call-1", SensitivePayload.of("sk-secret".toByteArray()), "provider-a", "model-a")

        val wire = registry.wireMessages(original, "provider-a", "model-a")
        assertEquals("sk-secret", wire[1].content)
        assertEquals(
            "credential delivered through the approved sensitive-result path",
            original[1].content,
        )

        val second = registry.wireMessages(original, "provider-a", "model-a")
        assertEquals(
            "credential delivered through the approved sensitive-result path",
            second[1].content,
        )
    }

    @Test
    fun `route change does not release the payload`() {
        val registry = SensitivePayloadRegistry()
        registry.register("call-1", SensitivePayload.of("sk-secret".toByteArray()), "provider-a", "model-a")
        val original = listOf(
            ModelMessage(
                role = MessageRole.TOOL,
                content = "placeholder",
                toolCallId = "call-1",
                toolName = "credential_read",
            ),
        )
        assertEquals("placeholder", registry.wireMessages(original, "provider-b", "model-b")[0].content)
    }
}

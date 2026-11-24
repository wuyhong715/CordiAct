package com.cordiact.interaction

import com.cordiact.core.conversation.ToolHistoryFormat
import com.cordiact.core.conversation.ToolHistoryProvenance
import com.cordiact.core.conversation.ToolHistorySource
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginInstanceState
import com.cordiact.core.plugin.PluginKernel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrustedToolResultContributionsTest {
    private val key = ToolHistoryProvenance("core.example", "example.list", ToolHistorySource.HOST_REGISTRATION, ToolHistoryFormat.BOUNDED_SUMMARY_V1)
    private val publisher = PluginInstanceId("core.cards")
    private val directory = TrustedToolResultContributions(mapOf(key to publisher.pluginId))
    private val kernel = PluginKernel(directory::refresh)
    private val card = ToolResultCard("Example", listOf("Host provided text"))

    @Test
    fun `history identity is exact and generic fallback remains after withdrawal`() {
        var withdrewBeforeCleanup = false
        kernel.register(publisher, object : ContextPlugin {
            override fun start(context: PluginContext) {
                directory.register(context, key, card)
                directory.refresh()
                assertNull(directory.resolve(key))
                assertTrue(directory.snapshot.value.isEmpty())
                context.effect {
                    withdrewBeforeCleanup = true
                    assertNull(directory.resolve(key))
                    assertTrue(directory.snapshot.value.isEmpty())
                }
            }
        })
        kernel.enable(publisher)
        assertSame(card, directory.resolve(key))
        assertNull(directory.resolve(key.copy(ownerPluginId = "other.implementation")))
        assertNull(directory.resolve(key.copy(toolId = "example.other")))
        assertNull(directory.resolve(null))
        kernel.disable(publisher)
        assertTrue(withdrewBeforeCleanup)
        assertNull(directory.resolve(key))
    }

    @Test
    fun `wrong publisher scopes and duplicate registrations do not leave partial cards`() {
        val others = listOf(PluginInstanceId("other.plugin"), publisher.copy(workspaceId = "a"), publisher)
        others.forEach { id ->
            kernel.register(id, object : ContextPlugin {
                override fun start(context: PluginContext) {
                    directory.register(context, key, card)
                    directory.register(context, key, card)
                }
            })
            kernel.enable(id)
            assertEquals(PluginInstanceState.FAILED, kernel.status(id).state)
            assertTrue(directory.snapshot.value.isEmpty())
            assertNull(directory.resolve(key))
        }
    }

    @Test
    fun `recipes copy their bounded literal text`() {
        val mutable = mutableListOf("Local text")
        val result = ToolResultCard("Title", mutable)
        mutable[0] = "Changed"
        assertEquals(listOf("Local text"), result.lines)
        assertFailsWith<IllegalArgumentException> { ToolResultCard("Title", List(5) { "line" }) }
        assertFailsWith<IllegalArgumentException> { ToolResultCard("Title", listOf("a\nb")) }
    }
}

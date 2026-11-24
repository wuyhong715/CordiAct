package com.cordiact.interaction

import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginInstanceState
import com.cordiact.core.plugin.PluginKernel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TrustedWorkbenchTabsTest {
    private val tabs = TrustedWorkbenchTabs<String>()
    private val kernel = PluginKernel(tabs::refresh)

    @Test
    fun `publication group commits after start and retires before any cleanup`() {
        val owner = PluginInstanceId("builtin")
        lateinit var first: TrustedWorkbenchTabs.Publication<String>
        var stopped = false
        kernel.register(owner, object : ContextPlugin {
            override fun start(context: PluginContext) {
                tabs.register(context, "trace", "Trace", order = 1)
                tabs.register(context, "artifacts", "Artifacts", order = 2)
                tabs.refresh()
                assertTrue(tabs.snapshot.value.visible("a", "s").isEmpty())
                // LIFO: this check runs even before the first tab unregisters.
                context.effect {
                    assertTrue(tabs.snapshot.value.visible("a", "s").isEmpty())
                    assertFalse(tabs.isCurrent(first, "a", "s"))
                    stopped = true
                }
            }
        })
        kernel.enable(owner)
        val visible = tabs.snapshot.value.visible("a", "s")
        assertEquals(listOf("trace", "artifacts"), visible.map { it.id })
        first = visible.first()
        kernel.disable(owner)
        assertTrue(stopped)
        kernel.enable(owner)
        assertFalse(tabs.isCurrent(first, "a", "s"))
        assertEquals("trace", tabs.snapshot.value.visible("a", "s").first().id)
    }

    @Test
    fun `scope resolution and conflicts never replace another owner`() {
        val a = PluginInstanceId("tabs", "a", "s")
        val b = PluginInstanceId("tabs", "b", "s")
        val conflicting = PluginInstanceId("another", "a")
        listOf(a, b, conflicting).forEach { id ->
            kernel.register(id, object : ContextPlugin {
                override fun start(context: PluginContext) {
                    if (context.id == conflicting) tabs.register(context, "partial", "never visible")
                    tabs.register(context, "details", requireNotNull(context.id.workspaceId))
                }
            })
            kernel.enable(id)
        }
        assertEquals(PluginInstanceState.FAILED, kernel.status(conflicting).state)
        val snapshot = tabs.snapshot.value
        val original = snapshot.visible("a", "s").single()
        val other = snapshot.visible("b", "s").single()
        assertEquals(a, original.owner)
        assertEquals(b, other.owner)
        assertTrue(snapshot.visible("a", "other").isEmpty())
        assertTrue(snapshot.visible(null, null).isEmpty())
        assertFalse(tabs.isCurrent(original, "b", "s"))
        kernel.disable(a)
        assertTrue(tabs.snapshot.value.visible("a", "s").isEmpty())
        assertSame(other, tabs.snapshot.value.visible("b", "s").single())
    }
}

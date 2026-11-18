package com.cordiact.interaction

import com.cordiact.core.plugin.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PluginCommandContributionsTest {
    private fun descriptor(id: String = "demo.command", name: String = "demo") = CommandDescriptor(
        id, name, "demo", aliases = setOf("alias"),
        executor = InteractionCommandExecutor { _, _, _, _ -> CommandExecutionResult(true) },
    )

    @Test fun `scope withdraws command and aliases without enabling owner`() {
        val registry = InteractionCommandRegistry()
        val adapter = PluginCommandContributions(registry)
        val kernel = PluginKernel()
        val id = PluginInstanceId("demo")
        kernel.register(id, object : ContextPlugin {
            override fun start(context: PluginContext) { adapter.register(context, descriptor()) }
        })
        kernel.enable(id)
        assertNull(registry.find("demo"))
        registry.setOwnerEnabled("demo", true)
        assertNotNull(registry.find("demo"))
        assertNotNull(registry.find("alias"))
        kernel.disable(id)
        assertNull(registry.find("demo"))
        assertNull(registry.find("alias"))
    }

    @Test fun `stale scope does not remove replacement command`() {
        val registry = InteractionCommandRegistry()
        val kernel = PluginKernel()
        val id = PluginInstanceId("demo")
        val first = descriptor()
        kernel.register(id, object : ContextPlugin {
            override fun start(context: PluginContext) {
                PluginCommandContributions(registry).register(context, first)
            }
        })
        kernel.enable(id)
        registry.unregister(first.id)
        val replacement = descriptor()
        registry.register(replacement)
        registry.setOwnerEnabled("demo", true)
        kernel.disable(id)
        assertSame(replacement.executor, registry.find("alias")?.executor)
    }

    @Test fun `duplicate ids rejected and input collections snapshotted`() {
        val registry = InteractionCommandRegistry()
        val aliases = mutableSetOf("old")
        registry.register(descriptor().copy(aliases = aliases))
        aliases.add("new")
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(descriptor(name = "different").copy(aliases = emptySet()))
        }
        registry.setOwnerEnabled("demo", true)
        assertEquals(setOf("old"), registry.find("demo")?.aliases)
        assertTrue(registry.unregister("demo.command"))
        assertNull(registry.find("old"))
    }
}

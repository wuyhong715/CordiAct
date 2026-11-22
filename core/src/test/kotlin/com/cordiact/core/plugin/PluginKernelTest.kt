package com.cordiact.core.plugin

import kotlin.test.*

class PluginKernelTest {
    private val text = ServiceKey("text", String::class.java)
    private fun plugin(required: Set<ServiceKey<*>> = emptySet(), start: (PluginContext) -> Unit) =
        object : ContextPlugin {
            override val requiredServices = required
            override fun start(context: PluginContext) = start(context)
        }

    @Test fun `dependencies wait stop consumers first and restart`() {
        val kernel = PluginKernel()
        val provider = PluginInstanceId("provider", "w")
        val consumer = PluginInstanceId("consumer", "w")
        val events = mutableListOf<String>()
        kernel.register(consumer, plugin(setOf(text)) {
            assertEquals("hello", it.service(text))
            events.add("consumer+")
            it.effect { events.add("consumer-") }
        })
        kernel.register(provider, plugin {
            it.provide(text, "hello")
            events.add("provider+")
            it.effect { events.add("provider-") }
        })
        kernel.enable(consumer)
        assertEquals(PluginInstanceState.WAITING, kernel.status(consumer).state)
        kernel.enable(provider)
        assertEquals(PluginInstanceState.ACTIVE, kernel.status(consumer).state)
        kernel.disable(provider)
        assertEquals(listOf("provider+", "consumer+", "consumer-", "provider-"), events)
        assertEquals(PluginInstanceState.WAITING, kernel.status(consumer).state)
        kernel.enable(provider)
        assertEquals(PluginInstanceState.ACTIVE, kernel.status(consumer).state)
    }

    @Test fun `restart only affects dependency closure and shutdown releases all`() {
        val kernel = PluginKernel()
        val p = PluginInstanceId("p")
        val c = PluginInstanceId("c")
        val other = PluginInstanceId("other")
        val events = mutableListOf<String>()
        kernel.register(p, plugin { it.provide(text, "v"); events.add("p+"); it.effect { events.add("p-") } })
        kernel.register(c, plugin(setOf(text)) { events.add("c+"); it.effect { events.add("c-") } })
        kernel.register(other, plugin { events.add("o+"); it.effect { events.add("o-") } })
        kernel.enable(p); kernel.enable(c); kernel.enable(other)
        kernel.restart(p)
        assertEquals(listOf("p+", "c+", "o+", "c-", "p-", "p+", "c+"), events)
        kernel.shutdown()
        assertTrue(kernel.statuses().all { it.state == PluginInstanceState.DISABLED })
        assertEquals(listOf("c-", "p-", "o-"), events.takeLast(3))
        kernel.shutdown()
    }

    @Test fun `child scopes release on parent close`() {
        val parent = PluginScope()
        val child = parent.child()
        var releases = 0
        child.effect { releases++ }
        parent.close(); child.close()
        assertEquals(1, releases)
        assertFalse(child.isOpen)
    }

    @Test fun `workspace and session do not leak services`() {
        val kernel = PluginKernel()
        val p = PluginInstanceId("p", "a")
        kernel.register(p, plugin { it.provide(text, "private") })
        kernel.enable(p)
        for (id in listOf(PluginInstanceId("c", "b"), PluginInstanceId("c", "a", "s"), PluginInstanceId("c"))) {
            kernel.register(id, plugin(setOf(text)) { error("must not start") })
            kernel.enable(id)
            assertEquals(setOf("text"), kernel.status(id).missingServices)
        }
    }

    @Test fun `partial start rolls back resources and services`() {
        val kernel = PluginKernel()
        val p = PluginInstanceId("p")
        var cleaned = false
        kernel.register(p, plugin { it.provide(text, "partial"); it.effect { cleaned = true }; error("broken") })
        kernel.enable(p)
        assertTrue(cleaned)
        assertEquals(PluginInstanceState.FAILED, kernel.status(p).state)
        val replacement = PluginInstanceId("replacement")
        kernel.register(replacement, plugin { it.provide(text, "replacement") })
        kernel.enable(replacement)
        assertEquals(PluginInstanceState.ACTIVE, kernel.status(replacement).state)
    }

    @Test fun `disposal retries failures and still releases other resources`() {
        val scope = PluginScope()
        val events = mutableListOf<Int>()
        var fail = true
        scope.effect { events.add(1) }
        scope.effect { if (fail) error("retry"); events.add(2) }
        scope.effect { events.add(3) }
        assertFailsWith<IllegalStateException> { scope.close() }
        assertEquals(listOf(3, 1), events)
        fail = false
        scope.close()
        scope.close()
        assertEquals(listOf(3, 1, 2), events)
        var lateCleaned = false
        assertFailsWith<IllegalStateException> { scope.effect { lateCleaned = true } }
        assertTrue(lateCleaned)
    }

    @Test fun `duplicate service does not destroy original owner`() {
        val kernel = PluginKernel()
        val a = PluginInstanceId("a")
        val b = PluginInstanceId("b")
        kernel.register(a, plugin { it.provide(text, "a") })
        kernel.register(b, plugin { it.provide(text, "b") })
        kernel.enable(a); kernel.enable(b)
        assertEquals(PluginInstanceState.FAILED, kernel.status(b).state)
        val c = PluginInstanceId("c")
        kernel.register(c, plugin(setOf(text)) { assertEquals("a", it.service(text)) })
        kernel.enable(c)
        assertEquals(PluginInstanceState.ACTIVE, kernel.status(c).state)
    }

    @Test fun `bridge retains waiting intent and resumes when dependency returns`() {
        val kernel = PluginKernel()
        val consumer = ContextManagedPlugin(PluginSpec("consumer", "1", PluginTarget.ANDROID_NATIVE),
            kernel, plugin(setOf(text)) { assertEquals("ready", it.service(text)) })
        consumer.onRegister(); consumer.onEnable()
        assertEquals(PluginInstanceState.WAITING, consumer.runtimeStatus()?.state)
        val provider = PluginInstanceId("provider")
        kernel.register(provider, plugin { it.provide(text, "ready") })
        kernel.enable(provider)
        assertEquals(PluginInstanceState.ACTIVE, consumer.runtimeStatus()?.state)
        kernel.disable(provider)
        assertEquals(PluginInstanceState.WAITING, consumer.runtimeStatus()?.state)
        consumer.onDisable()
        kernel.enable(provider)
        assertEquals(PluginInstanceState.DISABLED, consumer.runtimeStatus()?.state)
    }

    @Test fun `legacy bridge exposes startup failure and can retry`() {
        val kernel = PluginKernel()
        var fail = true
        var starts = 0
        val managed = ContextManagedPlugin(
            PluginSpec("demo", "1", PluginTarget.ANDROID_NATIVE), kernel,
            plugin { starts++; if (fail) error("failed startup") },
        )
        managed.onRegister()
        assertFailsWith<IllegalStateException> { managed.onEnable() }
        assertEquals(PluginInstanceState.DISABLED, kernel.status(PluginInstanceId("demo")).state)
        fail = false
        managed.onEnable()
        assertEquals(2, starts)
        managed.onDisable(); managed.onUninstall()
    }

    @Test fun `partial cleanup failure blocks unrelated startup until explicitly cleaned`() {
        val kernel = PluginKernel()
        val broken = PluginInstanceId("broken")
        var fail = true
        kernel.register(broken, plugin { it.effect { if (fail) error("cleanup") }; error("start") })
        assertFailsWith<IllegalStateException> { kernel.enable(broken) }
        val other = PluginInstanceId("other")
        var started = false
        kernel.register(other, plugin { started = true })
        assertFailsWith<IllegalStateException> { kernel.enable(other) }
        assertFalse(started)
        fail = false
        kernel.disable(broken)
        assertTrue(started)
    }

    @Test fun `tool contribution withdraws on disable and rejects foreign owner`() {
        val kernel = PluginKernel()
        val registry = com.cordiact.core.tool.InMemoryToolDefinitionRegistry()
        val tools = PluginToolContributions(registry)
        val definition = com.cordiact.core.tool.ToolDefinition(
            com.cordiact.core.tool.ToolDescriptor("demo.tool", "Demo", "Demo", com.cordiact.core.tool.ToolInputSchema(), "demo"),
            com.cordiact.core.tool.ToolExecutor { _, _ -> error("not executed by registration") },
        )
        val id = PluginInstanceId("demo")
        kernel.register(id, plugin { tools.register(it, definition) })
        kernel.enable(id)
        assertNotNull(registry.get("demo.tool"))
        kernel.disable(id)
        assertNull(registry.get("demo.tool"))
        val foreign = PluginInstanceId("foreign")
        kernel.register(foreign, plugin { tools.register(it, definition) })
        kernel.enable(foreign)
        assertEquals(PluginInstanceState.FAILED, kernel.status(foreign).state)
        val local = PluginInstanceId("demo", "workspace")
        kernel.register(local, plugin { tools.register(it, definition) })
        kernel.enable(local)
        assertEquals(PluginInstanceState.FAILED, kernel.status(local).state) // reserved namespace
        assertTrue(registry.all().isEmpty())
    }

    @Test fun `failed stop blocks restart until cleanup succeeds`() {
        val kernel = PluginKernel()
        val p = PluginInstanceId("p")
        var fail = true
        kernel.register(p, plugin { it.effect { if (fail) error("cleanup") } })
        kernel.enable(p)
        assertFailsWith<IllegalStateException> { kernel.disable(p) }
        assertEquals(PluginInstanceState.FAILED, kernel.status(p).state)
        assertFailsWith<IllegalStateException> { kernel.enable(p) }
        fail = false
        kernel.disable(p)
        kernel.enable(p)
        assertEquals(PluginInstanceState.ACTIVE, kernel.status(p).state)
    }
}

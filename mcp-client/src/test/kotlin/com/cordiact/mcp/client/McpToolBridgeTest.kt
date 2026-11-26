package com.cordiact.mcp.client

import com.cordiact.core.mcp.MCP_PLUGIN_ID
import com.cordiact.core.mcp.McpConnection
import com.cordiact.core.mcp.McpProtocolException
import com.cordiact.core.mcp.McpServerInfo
import com.cordiact.core.mcp.McpToolCallResult
import com.cordiact.core.mcp.McpToolDescriptor
import com.cordiact.core.tool.GlobalToolPublication
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolActivationContext
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDefinitionRegistry
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolValue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpToolBridgeTest {

    @Test
    fun scopedSelectionChecksWorkspaceAndRevocationBeforeDispatch() {
        val registry = InMemoryToolDefinitionRegistry()
        var valid = true
        val bridge = McpToolBridge("scope", registry, "core.tool-manager", 5000) { workspace, raw ->
            valid && workspace == "owner" && raw.name == "echo"
        }
        val allowed = McpToolDescriptor("echo", "Echo")
        val connection = FakeConnection(tools = listOf(allowed, McpToolDescriptor("other", "Other")))
        bridge.sync(connection, listOf(allowed))
        val id = "core.mcp.scope.echo"
        assertNull(registry.get("core.mcp.scope.other"))
        val def = requireNotNull(registry.get(id))
        val gate = requireNotNull(registry.visibility(id))
        assertTrue(gate.visible(com.cordiact.core.tool.ToolActivationContext("owner")))
        assertFalse(gate.visible(com.cordiact.core.tool.ToolActivationContext("foreign")))
        val args = ToolArguments(emptyMap())
        assertTrue("error" in def.executor.invoke(com.cordiact.core.tool.ToolInvocationContext(workspaceId = "foreign", requestId = "s"), args).values)
        assertFalse("error" in def.executor.invoke(com.cordiact.core.tool.ToolInvocationContext(workspaceId = "owner"), args).values)
        valid = false
        assertFalse(gate.visible(com.cordiact.core.tool.ToolActivationContext("owner")))
        assertTrue("error" in def.executor.invoke(com.cordiact.core.tool.ToolInvocationContext(workspaceId = "owner", requestId = "s"), args).values)
    }

    @Test
    fun syncPublishesEveryToolWithTheServerSchemaVerbatim() {
        val registry = InMemoryToolDefinitionRegistry()
        val connection = FakeConnection(
            tools = listOf(
                McpToolDescriptor("echo", "Echo text", """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}"""),
                McpToolDescriptor("now", "Current time"),
            ),
        )
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)

        val published = bridge.sync(connection)

        assertEquals(2, published.size)
        val echo = registry.get("core.mcp.demo.echo")
        assertNotNull(echo)
        assertEquals("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""", echo.descriptor.rawInputSchemaJson)
        assertEquals("core.mcp.demo", echo.descriptor.ownerPluginId)
        // The raw MCP name is what goes back over the wire, even though the tool id is normalized.
        assertEquals("echo", echo.descriptor.name)
    }

    @Test
    fun aFailedListingLeavesTheWorkingGenerationAlone() {
        val registry = InMemoryToolDefinitionRegistry()
        val first = FakeConnection(tools = listOf(McpToolDescriptor("echo", "Echo")))
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
        bridge.sync(first)
        assertNotNull(registry.get("core.mcp.demo.echo"))

        val broken = FakeConnection(tools = emptyList(), listFailure = McpProtocolException("server went away"))
        assertFailsWith<McpProtocolException> { bridge.sync(broken) }

        // The previous generation is still intact: a transient listing failure must not disarm
        // tools the model is already using.
        assertNotNull(registry.get("core.mcp.demo.echo"))
        assertEquals(1, bridge.publishedTools().size)
    }

    @Test
    fun aSecondSyncReplacesRatherThanAccumulates() {
        val registry = InMemoryToolDefinitionRegistry()
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
        val first = FakeConnection(tools = listOf(McpToolDescriptor("old", "Old")))
        bridge.sync(first)

        val second = FakeConnection(tools = listOf(McpToolDescriptor("new", "New")))
        bridge.sync(second)

        assertNull(registry.get("core.mcp.demo.old"))
        assertNotNull(registry.get("core.mcp.demo.new"))
        assertEquals(1, registry.all().size)
    }

    @Test
    fun retainedExecutorsAndVisibilityCannotFollowSameIdOrSameConnection() {
        val registry = InMemoryToolDefinitionRegistry()
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
        val first = FakeConnection(listOf(McpToolDescriptor("echo", "Echo")))
        val second = FakeConnection(listOf(McpToolDescriptor("echo", "Echo")))
        val id = "core.mcp.demo.echo"
        val ctx = ToolInvocationContext(workspaceId = "ws")
        val activation = ToolActivationContext("ws")
        bridge.sync(first)
        val old = requireNotNull(registry.get(id)).executor
        val oldGate = requireNotNull(registry.visibility(id))
        assertTrue(oldGate.visible(activation))
        bridge.sync(second)
        val middle = requireNotNull(registry.get(id)).executor
        assertFalse(oldGate.visible(activation))
        assertEquals("MCP tool registration is no longer active", (old.invoke(ctx, ToolArguments()).values["error"] as ToolValue.StringValue).value)
        assertNull(first.lastArgumentsJson)
        assertNull(second.lastArgumentsJson)
        bridge.sync(first)
        assertTrue("error" in old.invoke(ctx, ToolArguments()).values)
        assertTrue("error" in middle.invoke(ctx, ToolArguments()).values)
        assertNull(first.lastArgumentsJson)
        assertNull(second.lastArgumentsJson)
        assertFalse("error" in requireNotNull(registry.get(id)).executor.invoke(ctx, ToolArguments()).values)
        assertEquals("{}", first.lastArgumentsJson)
    }

    @Test
    fun replacementDuringApprovalCannotSendOnEitherConnection() {
        val registry = InMemoryToolDefinitionRegistry()
        val first = FakeConnection(listOf(McpToolDescriptor("echo", "Echo")))
        val second = FakeConnection(listOf(McpToolDescriptor("echo", "Echo")))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000) { _, _ ->
                entered.countDown()
                release.await(3, TimeUnit.SECONDS)
            }
            bridge.sync(first)
            val executor = requireNotNull(registry.get("core.mcp.demo.echo")).executor
            val result = worker.submit<Boolean> {
                "error" in executor.invoke(ToolInvocationContext(workspaceId = "ws"), ToolArguments()).values
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            bridge.sync(second)
            release.countDown()
            assertTrue(result.get(2, TimeUnit.SECONDS))
            assertNull(first.lastArgumentsJson)
            assertNull(second.lastArgumentsJson)
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun retirementSuppressesLateResultsAndPendingCleanupIsNotPublished() {
        val inner = InMemoryToolDefinitionRegistry()
        var refuseWithdrawal = true
        val registry = object : ToolDefinitionRegistry by inner {
            override fun publishGlobalBatch(entries: List<GlobalToolPublication>): AutoCloseable {
                val handle = inner.publishGlobalBatch(entries)
                return AutoCloseable {
                    check(!refuseWithdrawal) { "Synthetic withdrawal failed" }
                    handle.close()
                }
            }
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
            val connection = object : McpConnection by FakeConnection(listOf(McpToolDescriptor("echo", "Echo"))) {
                override fun callTool(name: String, argumentsJson: String, timeoutMs: Long): McpToolCallResult {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    return McpToolCallResult(text = "late payload")
                }
            }
            bridge.sync(connection)
            val id = "core.mcp.demo.echo"
            val executor = requireNotNull(registry.get(id)).executor
            val ctx = ToolInvocationContext(workspaceId = "ws")
            val result = worker.submit<com.cordiact.core.tool.ToolResult> { executor.invoke(ctx, ToolArguments()) }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertFailsWith<IllegalStateException> { bridge.dispose() }
            assertTrue(bridge.publishedTools().isEmpty())
            assertFalse(requireNotNull(registry.visibility(id)).visible(ToolActivationContext("ws")))
            release.countDown()
            assertTrue("error" in result.get(2, TimeUnit.SECONDS).values)
            refuseWithdrawal = false
            bridge.dispose()
            assertNull(registry.get(id))
        } finally {
            release.countDown()
            worker.shutdownNow()
        }
    }

    @Test
    fun cleanupRetryDoesNotRemoveAReplacementDefinitionOrItsVisibility() {
        val inner = InMemoryToolDefinitionRegistry()
        var refuseWithdrawal = true
        val registry = object : ToolDefinitionRegistry by inner {
            override fun publishGlobalBatch(entries: List<GlobalToolPublication>): AutoCloseable {
                val handle = inner.publishGlobalBatch(entries)
                return AutoCloseable {
                    check(!refuseWithdrawal) { "Synthetic withdrawal failed" }
                    handle.close()
                }
            }
        }
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
        val live = FakeConnection(listOf(McpToolDescriptor("echo", "Echo"), McpToolDescriptor("now", "Time")))
        bridge.sync(live)
        val id = "core.mcp.demo.echo"
        val old = requireNotNull(registry.get(id))
        assertFailsWith<IllegalStateException> { bridge.dispose() }
        assertTrue(registry.unregister(id))
        registry.register(ToolDefinition(old.descriptor.copy(description = "Replacement"), old.executor))
        registry.registerVisibility(id) { false }
        val replacement = requireNotNull(registry.get(id))
        val gate = registry.visibility(id)
        refuseWithdrawal = false
        bridge.dispose()
        assertEquals(replacement, registry.get(id))
        assertEquals(gate, registry.visibility(id))
        assertNull(registry.get("core.mcp.demo.now"))
        assertTrue(bridge.publishedTools().isEmpty())
    }

    @Test
    fun aRegistrationConflictRollsBackToZeroTools() {
        val registry = InMemoryToolDefinitionRegistry()
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
        val connection = FakeConnection(
            tools = listOf(McpToolDescriptor("alpha", "A"), McpToolDescriptor("beta", "B")),
        )
        // A foreign registration already owns the second id, so the swap must not leave alpha
        // behind: the model would then see a server that is half present.
        registry.register(
            com.cordiact.core.tool.ToolDefinition(
                com.cordiact.core.tool.ToolDescriptor(
                    id = "core.mcp.demo.beta",
                    name = "beta",
                    description = "squatter",
                    inputSchema = com.cordiact.core.tool.ToolInputSchema(),
                    ownerPluginId = "core.tools",
                ),
                com.cordiact.core.tool.ToolExecutor { _, _ -> com.cordiact.core.tool.ToolResult() },
            ),
        )

        assertFailsWith<IllegalArgumentException> { bridge.sync(connection) }

        assertNull(registry.get("core.mcp.demo.alpha"))
        assertTrue(bridge.publishedTools().isEmpty())
        // The squatter is not this bridge's to remove.
        assertNotNull(registry.get("core.mcp.demo.beta"))
    }

    @Test
    fun twoRawNamesMappingToOneIdRejectTheWholeGeneration() {
        val registry = InMemoryToolDefinitionRegistry()
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
        // A space and an underscore both normalize to `_`, so these two genuinely collide.
        // (`-` does not: it is an allowed id character and survives normalization.)
        val connection = FakeConnection(
            tools = listOf(McpToolDescriptor("do it", "A"), McpToolDescriptor("do_it", "B")),
        )

        assertFailsWith<McpProtocolException> { bridge.sync(connection) }
        assertTrue(registry.all().isEmpty())
    }

    @Test
    fun dispatchSendsTheRawNameAndMapsContentToAResult() {
        val registry = InMemoryToolDefinitionRegistry()
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
        val connection = FakeConnection(tools = listOf(McpToolDescriptor("echo", "Echo")))
        bridge.sync(connection)

        val definition = registry.get("core.mcp.demo.echo")
        assertNotNull(definition)
        val result = definition.executor.invoke(
            com.cordiact.core.tool.ToolInvocationContext(workspaceId = "ws", requestId = "call-1"),
            ToolArguments(mapOf("text" to ToolValue.StringValue("hi"))),
        )

        assertEquals("echo:hi", (result.values["result"] as ToolValue.StringValue).value)
        assertEquals("""{"text":"hi"}""", connection.lastArgumentsJson)
    }

    @Test
    fun aToolLevelFailureBecomesAnErrorResultNotAnException() {
        val registry = InMemoryToolDefinitionRegistry()
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
        val connection = FakeConnection(tools = listOf(McpToolDescriptor("boom", "Fails")))
        bridge.sync(connection)

        val definition = registry.get("core.mcp.demo.boom")
        assertNotNull(definition)
        val result = definition.executor.invoke(
            com.cordiact.core.tool.ToolInvocationContext(workspaceId = "ws", requestId = "call-2"),
            ToolArguments(),
        )

        // The model needs to see the server's message to correct itself, and a thrown exception
        // would end the turn instead.
        assertEquals("server said no", (result.values["error"] as ToolValue.StringValue).value)
    }

    @Test
    fun disposeWithdrawsEverything() {
        val registry = InMemoryToolDefinitionRegistry()
        val bridge = McpToolBridge("demo", registry, "$MCP_PLUGIN_ID.demo", 5_000)
        val connection = FakeConnection(tools = listOf(McpToolDescriptor("echo", "Echo")))
        bridge.sync(connection)

        bridge.dispose()

        assertTrue(registry.all().isEmpty())
        assertTrue(bridge.publishedTools().isEmpty())
        // Disposing twice is how a crash-then-cleanup sequence looks from here.
        bridge.dispose()
        assertTrue(registry.all().isEmpty())
    }
}

private class FakeConnection(
    private val tools: List<McpToolDescriptor>,
    private val listFailure: Exception? = null,
) : McpConnection {
    var lastArgumentsJson: String? = null

    override fun initialize(): McpServerInfo = McpServerInfo(protocolVersion = "2025-06-18", serverName = "fake")

    override fun listTools(timeoutMs: Long): List<McpToolDescriptor> {
        listFailure?.let { throw it }
        return tools
    }

    override fun callTool(name: String, argumentsJson: String, timeoutMs: Long): McpToolCallResult {
        lastArgumentsJson = argumentsJson
        return when (name) {
            "boom" -> McpToolCallResult(text = "server said no", isError = true)
            "echo" -> {
                val text = JsonParser(argumentsJson).parseDocument().field("text")?.asString() ?: ""
                McpToolCallResult(text = "echo:$text")
            }
            else -> McpToolCallResult(text = "ok")
        }
    }

    override fun close() = Unit
}

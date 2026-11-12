package com.cordiact.app.plugin

import com.cordiact.core.mcp.*
import com.cordiact.core.plugin.*
import com.cordiact.core.tool.*
import org.junit.Test
import org.junit.Assert.*

class PluginManagerSettingsTest {
    private class Records : PluginRecordStore {
        val data = linkedMapOf<String, PluginRecord>()
        override fun get(id: String) = data[id]
        override fun all() = data.values.toList()
        override fun put(record: PluginRecord) { data[record.spec.id] = record }
        override fun remove(id: String) { data.remove(id) }
    }
    private fun lifecycle(records: Records) = PluginLifecycleManager(records,
        object : PluginRuntimeResources {
            override fun quiesce(id: String) = Unit
            override fun resume(id: String) = Unit
            override fun stopAndDrain(id: String) = Unit
            override fun detach(id: String) = Unit
            override fun removeInstalledArtifacts(id: String) = Unit
        }, object : PluginCapabilities {
            override fun activateConsentedAccess(id: String) = Unit
            override fun suspendRuntimeAccess(id: String) = Unit
            override fun revokeAll(id: String) = Unit
        }, object : PluginOwnedData { override fun eraseAll(id: String) = Unit })
    private fun spec() = mcpPluginSpec(McpServerConfig("demo", "https://example.test/mcp"))
    @Test fun failedProbeNeverRegistersPlugin() {
        val store = Records(); val lifecycle = lifecycle(store); var registered = false
        val service = AndroidPluginManagerService(lifecycle, onSpecInstalled = { registered = true; true }, probeMcp = { error("remote error including secret") })
        val result = service.install(spec())
        assertEquals(PluginChangeResult.Application.FAILED, result.application)
        assertFalse(registered); assertTrue(store.data.isEmpty()); assertFalse(result.reason.orEmpty().contains("secret"))
    }
    @Test fun successProbesBeforePublishingAndRemovalCleansCredential() {
        val store = Records(); val lifecycle = lifecycle(store); val order = mutableListOf<String>()
        val service = AndroidPluginManagerService(lifecycle, onSpecInstalled = { value ->
            order += "install"
            lifecycle.register(object : ManagedPlugin { override val spec = value })
            lifecycle.enable(value.id); true
        }, probeMcp = { order += "probe" }, afterRemoved = { order += "credential-clear" })
        assertEquals(PluginChangeResult.Application.APPLIED, service.install(spec()).application)
        assertEquals(listOf("probe", "install"), order)
        assertEquals(PluginChangeResult.Application.APPLIED, service.remove(spec().id).application)
        assertEquals("credential-clear", order.last())
        assertTrue(store.data.isEmpty())
    }
    @Test fun headersCannotBePersistedInPluginConfig() {
        val service = AndroidPluginManagerService(lifecycle(Records()), onSpecInstalled = { error("MUST_NOT_REGISTER") }, probeMcp = { error("MUST_NOT_CONNECT") })
        assertEquals(PluginChangeResult.Application.FAILED, service.install(mcpPluginSpec(McpServerConfig("demo", "https://example.test/mcp", headers = mapOf("Authorization" to "secret")))).application)
    }
    @Test fun modelProposalMustBeConfirmedBeforeCredentialWritesOrInstall() {
        val lifecycle = lifecycle(Records()); var writes = 0; var connects = 0
        val service = AndroidPluginManagerService(lifecycle, probeMcp = { connects++ })
        val registry = InMemoryToolDefinitionRegistry()
        registerPluginManagerTools(registry, service, registerCredential = { _, _, _ -> writes++ }, confirmMcpInstall = { _, _ -> false })
        val tool = registry.all().single()
        val result = tool.executor.invoke(ToolInvocationContext("test", "workspace"), ToolArguments(mapOf(
            "action" to ToolValue.StringValue("add_mcp"), "server_name" to ToolValue.StringValue("demo"), "url" to ToolValue.StringValue("https://example.test/mcp"),
            "header_name" to ToolValue.StringValue("Authorization"), "header_value" to ToolValue.StringValue("secret"))))
        assertTrue(result.values.containsKey("error")); assertEquals(0, writes); assertEquals(0, connects)
    }
    @Test fun credentialIsWrittenBeforeProbeAfterConfirmation() {
        val store = Records(); val lifecycle = lifecycle(store); val events = mutableListOf<String>()
        val service = AndroidPluginManagerService(lifecycle, onSpecInstalled = { value -> lifecycle.register(object : ManagedPlugin { override val spec = value }); true }, probeMcp = { events += "probe" })
        val registry = InMemoryToolDefinitionRegistry()
        registerPluginManagerTools(registry, service, registerCredential = { _, _, _ -> events += "credential" }, confirmMcpInstall = { _, _ -> events += "confirm"; true })
        registry.all().single().executor.invoke(ToolInvocationContext("test", "workspace"), ToolArguments(mapOf(
            "action" to ToolValue.StringValue("add_mcp"), "server_name" to ToolValue.StringValue("demo"), "url" to ToolValue.StringValue("https://example.test/mcp"),
            "header_name" to ToolValue.StringValue("Authorization"), "header_value" to ToolValue.StringValue("secret"))))
        assertEquals(listOf("confirm", "credential", "probe"), events)
    }
}

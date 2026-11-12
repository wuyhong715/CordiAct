package com.cordiact.app.plugin

import com.cordiact.core.plugin.*
import com.cordiact.core.settings.*
import com.cordiact.core.tool.ToolDefinitionRegistry

/** Runtime-owned tools join inventory without reconstructing their lifecycle or executors. */
class BuiltinCapabilityInventory(
    private val registry: ToolDefinitionRegistry,
    private val enabled: (String, String) -> Boolean,
    private val probe: (String) -> Readiness,
) {
    fun entries(): List<PluginInventoryEntry> = registry.all().map { it.descriptor }
        // MCP has a real lifecycle record; never synthesize a protected built-in for it.
        .filterNot { it.ownerPluginId.startsWith("core.mcp.") }
        .mapNotNull { descriptor -> WorkspaceCapabilityPolicy.capabilityFor(descriptor.ownerPluginId)?.let { it to descriptor.id } }
        .groupBy({ it.first }, { it.second }).map { (capability, tools) ->
            PluginInventoryEntry(
                spec = PluginSpec("core.$capability", "0.1.0", PluginTarget.ANDROID_NATIVE),
                origin = PluginOrigin.BUNDLED_SYSTEM, state = PluginState.ENABLED,
                publishedToolIds = tools.sorted(), readOnlyReason = PluginInventoryEntry.ReadOnlyReason.MANAGEMENT_REQUIRED,
                capabilityId = capability,
            )
        }

    fun project(workspaceId: String, entry: PluginInventoryEntry): PluginInventoryEntry {
        val capability = entry.capabilityId ?: WorkspaceCapabilityPolicy.capabilityFor(entry.spec.id) ?: return entry
        val readiness = when {
            capability.startsWith("mcp.") -> Readiness(capability, "MCP: ${capability.removePrefix("mcp.")}",
                if (entry.state == PluginState.ENABLED) ReadinessState.READY else ReadinessState.NEEDS_ACTION,
                if (entry.state == PluginState.ENABLED) "服务器已连接" else "请到 MCP 页启用或重新连接", "MCP_STATE")
            capability == "search" -> Readiness("search", "联网搜索", ReadinessState.READY, "模式和服务请到搜索设置配置", "CONFIGURED")
            else -> probe(capability)
        }
        val titles = mapOf("ssh" to "SSH", "termux" to "Termux", "screen" to "屏幕自动化", "browser" to "离屏浏览器", "privilege" to "特权命令")
        return entry.copy(capabilityId = capability, workspaceId = workspaceId,
            workspaceEnabled = enabled(workspaceId, capability),
            readiness = readiness.copy(title = titles[capability] ?: readiness.title))
    }
}

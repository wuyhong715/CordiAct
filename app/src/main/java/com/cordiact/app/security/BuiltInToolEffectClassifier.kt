package com.cordiact.app.security

import com.cordiact.core.security.ToolEffectClass
import com.cordiact.core.security.ToolEffectClassifier

/** Host-owned allowlist. Unknown effects fail closed instead of impersonating a file read. */
class BuiltInToolEffectClassifier : ToolEffectClassifier {
    override fun classify(toolId: String, ownerPluginId: String): ToolEffectClass = when {
        toolId == com.cordiact.core.plugin.AUTHORED_PLUGIN_TOOL_ID ->
            if (ownerPluginId == com.cordiact.core.plugin.AUTHORED_PLUGIN_HOST_ID) ToolEffectClass.WORKSPACE_WRITE
            else ToolEffectClass.UNKNOWN
        toolId.startsWith("extension.authored.") || com.cordiact.core.plugin.AuthoredPluginNames.isOwner(ownerPluginId) ->
            if (com.cordiact.core.plugin.AuthoredPluginNames.isPureTool(toolId, ownerPluginId)) ToolEffectClass.PURE
            else ToolEffectClass.UNKNOWN
        ownerPluginId == com.cordiact.core.security.CREDENTIAL_VAULT_PLUGIN_ID -> ToolEffectClass.CREDENTIAL
        toolId.startsWith("security_audit_") -> ToolEffectClass.PURE
        toolId in setOf("job_list", "job_output", "job_probe", "tool_output_read", "skill.open") -> ToolEffectClass.WORKSPACE_READ
        toolId == "job_kill" -> ToolEffectClass.COMMAND
        toolId == "screen" -> ToolEffectClass.DEVICE_READ
        toolId == "list_image_models" -> ToolEffectClass.PURE
        toolId == "generate_image" -> ToolEffectClass.WORKSPACE_WRITE
        toolId == "workspace.files.write_text" -> ToolEffectClass.WORKSPACE_WRITE
        toolId in setOf("workspace.files.list", "workspace.files.read_text") -> ToolEffectClass.WORKSPACE_READ
        toolId in setOf("search.web", "search.fetch") -> ToolEffectClass.NETWORK
        toolId.startsWith("ssh_") -> ToolEffectClass.COMMAND
        toolId.startsWith("browser_") -> if (toolId in BROWSER_READ_ONLY) {
            ToolEffectClass.DEVICE_READ
        } else {
            ToolEffectClass.DEVICE_WRITE
        }
        toolId.startsWith("screen_") -> if (toolId == "screen_list" || toolId == "screen_wait") {
            ToolEffectClass.DEVICE_READ
        } else {
            ToolEffectClass.DEVICE_WRITE
        }
        toolId == "priv_status" -> ToolEffectClass.DEVICE_READ
        toolId == "priv_root" -> ToolEffectClass.ROOT
        toolId in setOf("priv_exec", "priv_shell") -> ToolEffectClass.COMMAND
        toolId == "termux_status" -> ToolEffectClass.DEVICE_READ
        toolId.startsWith("termux_") -> ToolEffectClass.COMMAND
        // The manager can disable/remove plugins and install remote MCP endpoints. Treat the
        // whole surface as destructive so a read-only envelope cannot reach it by argument.
        toolId == "plugin_manager" -> ToolEffectClass.DESTRUCTIVE
        // MCP tools run on a user-configured remote server; their transport effect is network.
        // The server's own semantics are opaque to this host, so do not downgrade them to PURE.
        toolId.startsWith("core.mcp.") -> ToolEffectClass.NETWORK
        toolId.startsWith("plugin_") -> ToolEffectClass.UNKNOWN
        toolId.startsWith("core.subagent.") || toolId.startsWith("core.orchestration.") -> ToolEffectClass.PURE
        toolId == "preview.push" -> ToolEffectClass.PURE
        else -> ToolEffectClass.UNKNOWN
    }

    private companion object {
        val BROWSER_READ_ONLY = setOf(
            "browser_read_screen",
            "browser_snapshot",
            "browser_screenshot",
            "browser_wait",
        )
    }
}

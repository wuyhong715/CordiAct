package com.cordiact.app.security

import com.cordiact.core.security.ToolEffectClass
import com.cordiact.core.security.ToolEffectClassifier

/** Host-owned allowlist. Unknown effects fail closed instead of impersonating a file read. */
class BuiltInToolEffectClassifier : ToolEffectClassifier {
    override fun classify(toolId: String, ownerPluginId: String): ToolEffectClass = when {
        ownerPluginId == com.cordiact.core.security.CREDENTIAL_VAULT_PLUGIN_ID -> ToolEffectClass.CREDENTIAL
        toolId.startsWith("security_audit_") -> ToolEffectClass.PURE
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

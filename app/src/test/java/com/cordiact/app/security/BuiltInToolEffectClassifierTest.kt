package com.cordiact.app.security

import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.core.security.EnvelopeToolAccessPolicy
import com.cordiact.core.security.Principal
import com.cordiact.core.security.SecurityContext
import com.cordiact.core.security.ToolAccessDecision
import com.cordiact.core.security.ToolEffectClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInToolEffectClassifierTest {
    @Test fun authoredNamesAreClassifiedOnlyForTheExactHostOrGeneratedOwner() {
        val classifier = BuiltInToolEffectClassifier()
        val tool = "workspace.plugins.author"
        assertEquals(ToolEffectClass.WORKSPACE_WRITE, classifier.classify(tool, "core.authored-plugins"))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify(tool, "core.file-manager"))
        assertEquals(ToolEffectClass.PURE, classifier.classify("extension.authored.demo.show", "authored.demo"))
        assertEquals(ToolEffectClass.PURE, classifier.classify("extension.authored.demo.uppercase", "authored.demo"))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify("extension.authored.demo.show", "authored.other"))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify("extension.authored.demo.show.extra", "authored.demo"))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify("job_list", "authored.demo"))
    }

    @Test fun restoredBuiltInsHaveExplicitEffectsAndUnknownsRemainDenied() {
        val classifier = BuiltInToolEffectClassifier()
        listOf("job_list", "job_output", "job_probe", "tool_output_read", "skill.open").forEach {
            assertEquals(it, ToolEffectClass.WORKSPACE_READ, classifier.classify(it, ""))
        }
        assertEquals(ToolEffectClass.WORKSPACE_WRITE, classifier.classify("generate_image", "model.providers"))
        assertEquals(ToolEffectClass.PURE, classifier.classify("list_image_models", "model.providers"))
        assertEquals(ToolEffectClass.COMMAND, classifier.classify("job_kill", ""))
        assertEquals(ToolEffectClass.DEVICE_READ, classifier.classify("screen", ""))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify("unknown_tool", ""))
        assertEquals(ToolEffectClass.ROOT, classifier.classify("priv_root", ""))
    }

    @Test fun pluginAndMcpManagementSurfacesAreNotUnknown() {
        val classifier = BuiltInToolEffectClassifier()
        // The plugin manager is host-owned but can remove plugins and install remote endpoints.
        assertEquals(ToolEffectClass.DESTRUCTIVE, classifier.classify("plugin_manager", "core.plugin-manager"))
        assertEquals(ToolEffectClass.DESTRUCTIVE, classifier.classify("plugin_manager", ""))
        // MCP tools are remote calls, not pure functions; unknown server semantics must not
        // masquerade as a harmless read. The transport effect is what this host can classify.
        assertEquals(ToolEffectClass.NETWORK, classifier.classify("core.mcp.demo.echo", "core.mcp"))
        assertEquals(ToolEffectClass.NETWORK, classifier.classify("core.mcp.demo.create_issue", "core.mcp"))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify("core.mcp", "core.mcp"))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify("core.mcpx.tool", "core.mcp"))
        assertEquals(ToolEffectClass.NETWORK, classifier.classify("core.mcp.demo.echo", "core.mcp.demo"))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify("core.mcp.demo.echo", "core.mcp.other"))
        val workspaceTool = "core.mcp.wsmcp_digest.echo"
        assertEquals(ToolEffectClass.NETWORK, classifier.classify(workspaceTool, com.cordiact.core.tool.TOOL_MANAGER_PLUGIN_ID))
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify(workspaceTool, "core.mcp"))
        // A legacy plugin_* tool that is not the manager still fails closed.
        assertEquals(ToolEffectClass.UNKNOWN, classifier.classify("plugin_other", "core.plugin-manager"))
    }

    @Test fun pluginAndMcpToolsPassTheEnvelopeButNotInReadOnly() {
        val policy = EnvelopeToolAccessPolicy(BuiltInToolEffectClassifier())
        assertTrue(policy.evaluate(context(EffectiveSecurityProfile.fullAccess("ws")), "plugin_manager", "core.plugin-manager") is ToolAccessDecision.Allow)
        assertTrue(policy.evaluate(context(EffectiveSecurityProfile.fullAccess("ws")), "core.mcp.demo.echo", "core.mcp") is ToolAccessDecision.Allow)
        assertTrue(policy.evaluate(context(EffectiveSecurityProfile.readOnly("ws")), "plugin_manager", "core.plugin-manager") is ToolAccessDecision.Deny)
        assertTrue(policy.evaluate(context(EffectiveSecurityProfile.readOnly("ws")), "core.mcp.demo.echo", "core.mcp") is ToolAccessDecision.Deny)
    }

    private fun context(profile: EffectiveSecurityProfile) = SecurityContext(
        workspaceId = "ws",
        sessionId = "session-1",
        runId = "run-1",
        turnId = "turn-1",
        toolCallId = "call-1",
        principalChain = listOf(Principal("main")),
        profile = profile,
    )
}

package com.cordiact.core.plugin

import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolManagerException
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.concurrent.locks.ReentrantLock

/** Workspace packages can veto a tool. Mandatory host approvals remain in ToolManager. */
class PackageToolPolicies {
    private class Entry(
        val owner: PluginInstanceId,
        val label: String,
        val tools: Set<String>,
        val source: ByteArray,
        val session: PackageScriptSession,
        val active: () -> Boolean,
    ) {
        val calls = ReentrantLock()
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    fun register(context: PluginContext, manifest: ExtensionPackageManifest, runtime: PackageScriptRuntime, admitted: () -> Boolean) {
        val owner = context.id
        require(owner.pluginId == extensionPackageSpec(manifest).id && owner.workspaceId != null && owner.sessionId == null)
        require(ExtensionPackageCapability.AGENT_TOOL_POLICY_PUBLISH in manifest.capabilities)
        context.scope.requireOpen()
        manifest.agent?.beforeTool.orEmpty().forEach { declaration ->
            val session = context.scope.own(runtime.open(owner))
            val entry = Entry(owner, "${manifest.id}@${manifest.version}/${declaration.name}", declaration.tools.toSet(),
                declaration.script.toByteArray(Charsets.UTF_8), session) { context.isContributionActive() && admitted() }
            synchronized(lock) {
                require(entry.tools.all { toolId ->
                    entries.count { it.owner.workspaceId == owner.workspaceId && toolId in it.tools } < MAX_POLICIES_PER_TOOL
                }) { "Too many package policies for the same workspace tool" }
                entries.add(entry)
            }
            context.effect { synchronized(lock) { entries.remove(entry) } }
        }
    }

    /** Evaluates the selected generations once and returns a guard for the final dispatch point. */
    fun preflight(workspaceId: String, descriptor: ToolDescriptor, arguments: ToolArguments): () -> Unit {
        val entries = selected(workspaceId, descriptor.id)
        val checkCurrent = {
            if (selected(workspaceId, descriptor.id) != entries) deny("Package tool policy changed; submit a new call")
        }
        if (entries.isEmpty()) return checkCurrent
        val names = JsonArray()
        descriptor.inputSchema.parameters.asSequence().map { it.name }.filter { it in arguments.values }.sorted().forEach { names.add(it) }
        val frame = encodeScriptInput(JsonObject().apply {
            addProperty("toolId", descriptor.id)
            add("suppliedParameters", names)
            addProperty("argumentCount", arguments.values.size)
        }.toString())
        for (entry in entries) {
            checkCurrent()
            val result = try {
                entry.calls.lockInterruptibly()
                try {
                    checkCurrent()
                    entry.session.evaluate(entry.source.copyOf(), frame.copyOf())
                } finally { entry.calls.unlock() }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                deny("Package tool policy interrupted (${entry.label})")
            } catch (_: Exception) {
                checkCurrent()
                deny("Package tool policy failed (${entry.label})")
            }
            checkCurrent()
            when {
                result.contentEquals(CONTINUE) -> Unit
                result.contentEquals(BLOCK) -> deny("Tool blocked by package policy (${entry.label})")
                else -> deny("Package tool policy returned an invalid decision (${entry.label})")
            }
        }
        return checkCurrent
    }

    private fun selected(workspaceId: String, toolId: String): List<Entry> = synchronized(lock) {
        entries.filter { it.owner.workspaceId == workspaceId && toolId in it.tools }
    }.filter { it.active() }.sortedBy { it.label }

    private fun deny(message: String): Nothing = throw ToolManagerException(ToolManagerException.Code.APPROVAL_DENIED, message)

    private companion object {
        const val MAX_POLICIES_PER_TOOL = 4
        val CONTINUE = "continue".encodeToByteArray()
        val BLOCK = "block".encodeToByteArray()
    }
}

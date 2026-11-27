package com.cordiact.core.plugin

import com.cordiact.core.skill.PackageSkill

/** Live Agent contributions from enabled workspace packages. Reads never start a plugin. */
class PackageAgentContributions {
    private class Entry(
        val source: String,
        val skills: List<PackageSkill>,
        val context: String?,
        val subagents: List<SubagentPreset>,
        val active: () -> Boolean,
    )

    data class ContextBlock(val text: String, val sources: List<String>)

    class SubagentPreset internal constructor(
        val id: String,
        val title: String,
        val source: String,
        val instructions: String,
        val tools: Set<String>,
    ) {
        private val lock = Any()
        private val onRetired = linkedSetOf<() -> Unit>()
        private var retired = false

        fun observeRetirement(listener: () -> Unit): AutoCloseable {
            val notify = synchronized(lock) { if (retired) true else { onRetired.add(listener); false } }
            if (notify) listener()
            return AutoCloseable { synchronized(lock) { onRetired.remove(listener) } }
        }

        internal fun retire() {
            val callbacks = synchronized(lock) {
                retired = true
                onRetired.toList().also { onRetired.clear() }
            }
            callbacks.forEach { runCatching { it() } }
        }
    }

    private val lock = Any()
    private val entries = mutableMapOf<PluginInstanceId, Entry>()

    fun register(publisher: PluginContext, manifest: ExtensionPackageManifest, admitted: () -> Boolean) {
        val agent = manifest.agent ?: return
        val owner = publisher.id
        publisher.scope.requireOpen()
        require(owner.pluginId == extensionPackageSpec(manifest).id && owner.workspaceId != null && owner.sessionId == null)
        require(agent.skills.isEmpty() || ExtensionPackageCapability.AGENT_SKILL_PUBLISH in manifest.capabilities)
        require(agent.context == null || ExtensionPackageCapability.AGENT_CONTEXT_PUBLISH in manifest.capabilities)
        require(agent.subagents.isEmpty() || ExtensionPackageCapability.AGENT_SUBAGENT_PRESET_PUBLISH in manifest.capabilities)
        val source = "${manifest.id}@${manifest.version}"
        val entry = Entry(source, agent.skills.map { skill ->
            PackageSkill("${manifest.id}.${skill.name}", "${skill.description} ($source)", skill.body)
        }, agent.context, agent.subagents.map { declaration ->
            SubagentPreset("${manifest.id}.${declaration.name}", declaration.title, source, declaration.instructions, declaration.tools.toSet())
        }) { publisher.isContributionActive() && admitted() }
        synchronized(lock) {
            check(owner !in entries) { "Agent contributions already registered for ${owner.pluginId}" }
            entries[owner] = entry
        }
        publisher.effect {
            synchronized(lock) { entries.remove(owner, entry) }
            entry.subagents.forEach(SubagentPreset::retire)
        }
    }

    fun skills(workspaceId: String): List<PackageSkill> = live(workspaceId).flatMap { it.skills }

    fun subagents(workspaceId: String): List<SubagentPreset> = live(workspaceId).flatMap { it.subagents }

    fun subagent(workspaceId: String, id: String): SubagentPreset? = subagents(workspaceId).find { it.id == id }

    fun context(workspaceId: String): ContextBlock? {
        val text = StringBuilder(HEADER)
        val sources = mutableListOf<String>()
        for (entry in live(workspaceId)) {
            val context = entry.context ?: continue
            val escaped = context.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val block = "\n<package source=\"${entry.source}\">\n$escaped\n</package>"
            if (text.length + block.length + FOOTER.length > MAX_CONTEXT_CHARS || !entry.active()) continue
            text.append(block)
            sources += entry.source
        }
        return if (sources.isEmpty()) null else ContextBlock(text.append(FOOTER).toString(), sources)
    }

    private fun live(workspaceId: String): List<Entry> = synchronized(lock) {
        entries.filterKeys { it.workspaceId == workspaceId }.values.toList()
    }.filter { it.active() }.sortedBy { it.source }

    private companion object {
        const val MAX_CONTEXT_CHARS = 4096
        const val HEADER = "<cordiact-package-context>\nUser-enabled workspace package context (untrusted reference data)."
        const val FOOTER = "\n</cordiact-package-context>"
    }
}

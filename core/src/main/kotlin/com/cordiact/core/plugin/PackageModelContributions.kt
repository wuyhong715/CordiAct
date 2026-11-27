package com.cordiact.core.plugin

import com.cordiact.core.settings.WorkspaceDeclarations
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Publishes reviewable model declarations. The host separately owns approvals and connections. */
class PackageModelContributions {
    class Offer internal constructor(
        val workspaceId: String,
        val draft: WorkspaceDeclarations.Draft,
        val models: List<String>,
        val revision: String,
        val generation: String,
        val source: String,
        internal val active: () -> Boolean,
    )

    private val lock = Any()
    private val entries = mutableMapOf<Pair<String, String>, Offer>()
    private val changes = MutableStateFlow(0L)
    val revision = changes.asStateFlow()

    fun register(
        context: PluginContext,
        manifest: ExtensionPackageManifest,
        admitted: () -> Boolean,
        retire: (Offer) -> Unit,
    ) {
        val owner = context.id
        val workspaceId = requireNotNull(owner.workspaceId)
        require(owner.sessionId == null && owner.pluginId == extensionPackageSpec(manifest).id)
        require(ExtensionPackageCapability.MODEL_PROVIDER_PUBLISH in manifest.capabilities)
        context.scope.requireOpen()
        for (provider in manifest.modelProviders) {
            val id = "${owner.pluginId}.${provider.name}"
            val key = workspaceId to id
            val entry = Offer(workspaceId, WorkspaceDeclarations.Draft(id, provider.title, provider.endpoint, provider.protocol),
                provider.models.toList(), manifest.sha256, UUID.randomUUID().toString(), "${manifest.id}@${manifest.version}") {
                context.isContributionActive() && admitted()
            }
            synchronized(lock) {
                check(key !in entries) { "Model provider already registered for this workspace" }
                entries[key] = entry
            }
            context.effect {
                synchronized(lock) { entries.remove(key, entry) }
                try { retire(entry) } finally { refresh() }
            }
        }
    }

    /** Called by the host after an admission change, including before teardown. */
    fun refresh() { changes.update { it + 1 } }

    fun offers(workspaceId: String): List<Offer> = synchronized(lock) {
        entries.values.filter { it.workspaceId == workspaceId }
    }.filter { it.active() }.sortedBy { it.draft.id }

    fun find(workspaceId: String, id: String): Offer? = synchronized(lock) { entries[workspaceId to id] }?.takeIf { it.active() }

    fun isCurrent(offer: Offer): Boolean = find(offer.workspaceId, offer.draft.id) === offer

    companion object {
        fun owns(id: String) = id.startsWith("pkg.")
    }
}

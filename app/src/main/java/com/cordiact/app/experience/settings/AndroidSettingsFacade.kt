package com.cordiact.app.experience.settings

import android.content.Context
import com.cordiact.app.CordiActApplication
import com.cordiact.app.context.SummaryModelSelectionStore
import com.cordiact.app.memory.ExtractionModelSelectionStore
import com.cordiact.app.model.AndroidProviderApiKeyStore
import com.cordiact.app.security.AndroidSystemCredentials
import com.cordiact.core.conversation.ModelId
import com.cordiact.model.providers.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings owns writes; UI can only consume sanitized metadata. No plaintext read API. */
class AndroidSettingsFacade(private val app: CordiActApplication) : SettingsFacade {
    override val security = SecuritySettingsFacade(app)
    override val searchSettings: SearchSettings by lazy { AndroidSearchSettings(app) }
    override val mcpSettings: McpSettings by lazy { AndroidMcpSettings(app) }
    override val sshSettings: SshSettings by lazy { AndroidSshSettings(app) }
    override val credentialSettings = AndroidCredentialSettings(app)
    @Volatile private var writeWarning: String? = null
    override fun takeWriteWarning(): String? = writeWarning.also { writeWarning = null }
    private fun <T> audited(target: String, revision: () -> Long, action: () -> T): T = synchronized(AUDIT_WRITE_LOCK) {
        val receipt = com.cordiact.core.settings.SettingsOperationAudit(app.credentialVaultRuntime.database)
            .run(target, revision, action)
        if (!receipt.completionRecorded) writeWarning = "设置已保存，但完成审计失败；请检查审计存储"
        receipt.value
    }
    private fun providerRevision() = com.cordiact.app.model.AndroidModelProviderConfigStore(app).revision()
    private val registry get() = app.modelProviders
    private val credentials = AndroidSystemCredentials.get(app)
    private val summary = SummaryModelSelectionStore(app)
    private val extraction = ExtractionModelSelectionStore(app)
    private val defaults = com.cordiact.app.model.ConversationModelSelectionStore(app)

    private val capabilitySettings = com.cordiact.app.settings.CapabilitySettingsStore(app)
    private val automationReadiness by lazy { com.cordiact.app.settings.AutomationReadiness(app, app.automationRuntime) }
    private var settingsWorkspace: String? = null
    override suspend fun selectWorkspace(id: String) { require(app.workspaceManager.get(id) != null); settingsWorkspace = id }
    override suspend fun testRoot() = withContext(Dispatchers.IO) {
        val id = settingsWorkspace ?: app.workspaceManager.activeWorkspace().id
        require(com.cordiact.app.security.SecurityProfileStore(app).profileFor(id).preset == com.cordiact.core.security.PermissionPreset.FULL_ACCESS) { "ROOT_REQUIRES_FULL_ACCESS" }
        require(app.automationRuntime.privilegeSettings.read().root) { "ROOT_DISABLED" }
        automationReadiness.testRoot()
        Unit
    }
    override suspend fun testScreen() = withContext(Dispatchers.IO) {
        check(app.automationRuntime.screenService.snapshot(app.automationRuntime.screenService.defaultHandle(), 50).let { it.error == null }) { "SCREEN_TEST_FAILED" }
    }
    override suspend fun setCapability(workspaceId: String, capability: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        require(app.workspaceManager.get(workspaceId) != null) { "WORKSPACE_UNAVAILABLE" }
        if (enabled && capability.startsWith("mcp.")) require(app.pluginManagerService().inventory().any { it.spec.id == "core.$capability" && it.state == com.cordiact.core.plugin.PluginState.ENABLED }) { "MCP_NOT_READY" }
        if (enabled && capability != "search" && !capability.startsWith("mcp.")) require(automationReadiness.probe(capability).state == com.cordiact.core.settings.ReadinessState.READY) { "CAPABILITY_NOT_READY" }
        audited("capability:$workspaceId:$capability", { capabilitySettings.revision(workspaceId) }) {
            capabilitySettings.set(workspaceId, capability, enabled, com.cordiact.core.settings.SettingsWriter(audit = {}))
        }
        Unit
    }
    private val attachmentSettings = com.cordiact.app.settings.AttachmentSettingsStore(app)
    override suspend fun saveAttachmentLimits(value: com.cordiact.core.attachment.AttachmentLimits) = withContext(Dispatchers.IO) {
        audited("attachments.policy", attachmentSettings::revision) { attachmentSettings.save(value) }
    }
    override suspend fun testTermux() = withContext(Dispatchers.IO) {
        check(app.automationRuntime.termuxBridge.testReadiness().isSuccess) { "TERMUX_TEST_FAILED" }
    }
    override suspend fun snapshot() = withContext(Dispatchers.IO) {
        val providers = registry.providers().map { config ->
            ProviderSummary(config.providerId, config.displayName, config.protocol, config.endpoint,
                config.discoveredModels, config.models.toSet(), credentials.has("model", config.providerId), config.parameterCapabilities, config.modelDefaults)
        }
        val available = app.modelAdapter.models()
        val roles = mapOf(SettingsModelRole.SUMMARY to summary.selection(), SettingsModelRole.EXTRACTION to extraction.selection())
        val default = readDefault()
        val readiness = buildList {
            val probes = providers.map { p ->
                com.cordiact.core.settings.ConfigurationReadinessProbe("provider:${p.id}", p.name,
                    { p.keyIsSet }, { p.enabled.isNotEmpty() }, "settings://models/providers/${p.id}")
            } + com.cordiact.core.settings.ConfigurationReadinessProbe("search", "第三方搜索", {
                listOf("tavily", "brave", "exa", "bocha", "deepseek").any { credentials.has("search", it) }
            }, route = "settings://search")
            com.cordiact.core.settings.ReadinessRegistry(probes).all().forEach { result ->
                add(SettingsReadiness(result.id, result.title, SettingsStatus.valueOf(result.state.name),
                    result.summary, result.reasonCode, result.actions.firstOrNull()?.route))
            }
            roles.forEach { (role, selection) ->
                if (selection is ModelRoleSelection.Explicit && available.none { it.id == selection.model })
                    add(SettingsReadiness("role:${role.name}", role.title, SettingsStatus.ERROR, "模型已删除或停用，请重新选择"))
            }
            if (default != null && available.none { it.id == default })
                add(SettingsReadiness("role:CONVERSATION", "对话默认模型", SettingsStatus.ERROR, "模型已删除或停用，请重新选择"))

        }
        val workspaceId = settingsWorkspace?.takeIf { app.workspaceManager.get(it) != null } ?: app.workspaceManager.activeWorkspace().id
        val capabilities = app.pluginManagerService().inventory(workspaceId).mapNotNull { entry ->
            val id = entry.capabilityId ?: return@mapNotNull null
            val status = entry.readiness ?: return@mapNotNull null
            CapabilitySummary(id, status.title, entry.workspaceEnabled == true, status)
        }
        val connectionReadiness = capabilities.map { c -> c.readiness.let { r -> SettingsReadiness(r.id, c.label, SettingsStatus.valueOf(r.state.name), r.summary, r.reasonCode, "settings://connections/${c.id}") } }
        SettingsSnapshot(providers, available, readiness + connectionReadiness, providers.map { CredentialMeta("model", it.id, it.keyIsSet) }, roles, default, attachmentSettings.limits(), workspaceId, capabilities, app.workspaceManager.list().map { SecurityWorkspace(it.id, it.name) })
    }
    override suspend fun discover(draft: ProviderDraft, key: String) = withContext(Dispatchers.IO) {
        HttpModelDiscoveryService().discover(draft.connection(), key.trim())
    }
    override suspend fun commit(draft: ProviderDraft, key: String, discovered: List<String>, selected: Set<String>) = withContext(Dispatchers.IO) {
        require(selected.isNotEmpty() && discovered.containsAll(selected))
        audited("model.provider.add", ::providerRevision) {
            registry.commitProvider(draft.connection(), key.trim(), selected.toList(), discovered)
            Unit
        }
    }
    override suspend fun refreshProvider(id: String) = withContext(Dispatchers.IO) {
        audited("model.provider.refresh", ::providerRevision) {
            val before = config(id).discoveredModels.toSet()
            registry.refreshModels(id)
            val after = config(id).discoveredModels.toSet()
            ModelsDiff(after - before, before - after)
        }
    }
    override suspend fun setEnabledModels(id: String, selected: Set<String>) = withContext(Dispatchers.IO) {
        audited("model.provider.enabled", ::providerRevision) {
            val old = config(id)
            registry.updateProvider(old.withModels(selected.toList(), old.discoveredModels))
        }
    }
    override suspend fun updateProvider(id: String, name: String, url: String) = withContext(Dispatchers.IO) {
        val old = config(id)
        val connection = ModelProviderConnection(id, name.trim().ifBlank { id }, old.protocol, url.trim())
        val endpoint = connection.config(old.discoveredModels).endpoint
        // A new endpoint must never receive a saved key implicitly. Require re-entering it.
        require(endpoint == old.endpoint) { "ENDPOINT_REQUIRES_NEW_PROVIDER" }
        audited("model.provider.update", ::providerRevision) {
            registry.updateProvider(ModelProviderConfig(old.providerId, name.trim().ifBlank { id }, old.protocol, endpoint,
                old.models, old.connectTimeoutMillis, old.readTimeoutMillis, old.defaults, old.parameterCapabilities,
                old.attachmentCapabilities, discoveredModels = old.discoveredModels, modelDefaults = old.modelDefaults))
        }
    }
    override suspend fun setKey(id: String, key: String?) = withContext(Dispatchers.IO) {
        config(id)
        audited("model.provider.key", { credentials.revision("model", id) }) {
            AndroidProviderApiKeyStore(app).set(id, key)
        }
    }
    override suspend fun removeProvider(id: String) = withContext(Dispatchers.IO) {
        audited("model.provider.remove", ::providerRevision) {
            registry.removeProvider(id)
            // Registry deletes its credential. Keep role references so the user sees Invalid rather than silent fallback.
        }
    }
    override suspend fun setModelDefaults(id: String, values: Map<String, com.cordiact.core.conversation.GenerationParameters>) = withContext(Dispatchers.IO) {
        audited("model.provider.defaults", ::providerRevision) {
            registry.updateProvider(config(id).withModelDefaults(values))
        }
    }
    override suspend fun setRole(role: SettingsModelRole, selection: ModelRoleSelection) = withContext(Dispatchers.IO) {
        if (selection is ModelRoleSelection.Explicit) require(app.modelAdapter.models().any { it.id == selection.model }) { "MODEL_UNAVAILABLE" }
        if (role == SettingsModelRole.CONVERSATION) require(selection is ModelRoleSelection.Explicit)
        audited("model.role.${role.name.lowercase()}", { when (role) { SettingsModelRole.CONVERSATION -> defaults.revision(); SettingsModelRole.SUMMARY -> summary.revision(); SettingsModelRole.EXTRACTION -> extraction.revision() } }) {
            when (role) {
                SettingsModelRole.CONVERSATION -> {
                    require(selection is ModelRoleSelection.Explicit)
                    defaults.setDefault(selection.model)
                }
                SettingsModelRole.SUMMARY -> when (selection) {
                    ModelRoleSelection.FollowConversation -> summary.selectFollowingConversation()
                    ModelRoleSelection.Disabled -> summary.clear()
                    is ModelRoleSelection.Explicit -> summary.select(selection.model)
                }
                SettingsModelRole.EXTRACTION -> when (selection) {
                    ModelRoleSelection.FollowConversation -> extraction.selectFollowingConversation()
                    ModelRoleSelection.Disabled -> extraction.clear()
                    is ModelRoleSelection.Explicit -> extraction.select(selection.model)
                }
            }
        }
    }
    companion object { private val AUDIT_WRITE_LOCK = Any() }
    private fun readDefault(): ModelId? = defaults.defaultModel()
    private fun config(id: String) = registry.providers().first { it.providerId == id }
    private fun ProviderDraft.connection() = ModelProviderConnection(id, name.trim().ifBlank { java.net.URI(url).host ?: id }, protocol, url.trim())
}

package com.cordiact.app.experience.settings

import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelId
import com.cordiact.model.providers.ModelProtocol

/** UI DTOs never carry credentials or an execution-time credential reader. */
enum class SettingsStatus { READY, NEEDS_ACTION, DISABLED, UNAVAILABLE, ERROR }
data class SettingsReadiness(val id: String, val title: String, val state: SettingsStatus, val summary: String, val reasonCode: String = "", val route: String? = null)
data class ProviderSummary(
    val id: String, val name: String, val protocol: ModelProtocol, val endpoint: String,
    val discovered: List<String>, val enabled: Set<String>, val keyIsSet: Boolean,
    val capabilities: Map<String, com.cordiact.model.providers.ModelParameterCapabilities> = emptyMap(),
    val modelDefaults: Map<String, com.cordiact.core.conversation.GenerationParameters> = emptyMap(),
)
data class CredentialMeta(val namespace: String, val owner: String, val isSet: Boolean)
data class SettingsSnapshot(
    val providers: List<ProviderSummary> = emptyList(),
    val models: List<ModelDescriptor> = emptyList(),
    val readiness: List<SettingsReadiness> = emptyList(),
    val credentials: List<CredentialMeta> = emptyList(),
    val roles: Map<SettingsModelRole, ModelRoleSelection> = emptyMap(),
    val conversationDefault: ModelId? = null,
    val attachmentLimits: com.cordiact.core.attachment.AttachmentLimits = com.cordiact.core.attachment.AttachmentLimits(),
    val capabilityWorkspaceId: String = "",
    val capabilities: List<CapabilitySummary> = emptyList(),
    val workspaces: List<SecurityWorkspace> = emptyList(),
)
enum class SettingsModelRole(val title: String) { CONVERSATION("对话默认模型"), SUMMARY("上下文摘要"), EXTRACTION("记忆抽取") }
data class ProviderDraft(val id: String, val name: String, val protocol: ModelProtocol, val url: String)
data class ModelsDiff(val added: Set<String>, val removed: Set<String>)

interface SettingsFacade {
    fun takeWriteWarning(): String? = null
    val searchSettings: SearchSettings? get() = null
    val mcpSettings: McpSettings? get() = null
    val sshSettings: SshSettings? get() = null
    val credentialSettings: AndroidCredentialSettings? get() = null
    val security: SecuritySettingsFacade? get() = null
    suspend fun selectWorkspace(id: String) { error("WORKSPACE_SELECTION_UNAVAILABLE") }
    suspend fun testRoot() { error("ROOT_UNAVAILABLE") }
    suspend fun testScreen() { error("SCREEN_UNAVAILABLE") }
    suspend fun setCapability(workspaceId: String, capability: String, enabled: Boolean) { error("CAPABILITIES_UNAVAILABLE") }
    suspend fun saveAttachmentLimits(value: com.cordiact.core.attachment.AttachmentLimits) { error("ATTACHMENT_POLICY_UNAVAILABLE") }
    suspend fun testTermux() { error("TERMUX_UNAVAILABLE") }
    suspend fun snapshot(): SettingsSnapshot
    suspend fun discover(draft: ProviderDraft, key: String): List<String>
    suspend fun commit(draft: ProviderDraft, key: String, discovered: List<String>, selected: Set<String>)
    suspend fun refreshProvider(id: String): ModelsDiff
    suspend fun setEnabledModels(id: String, selected: Set<String>)
    suspend fun updateProvider(id: String, name: String, url: String)
    suspend fun setKey(id: String, key: String?)
    suspend fun removeProvider(id: String)
    suspend fun setModelDefaults(id: String, values: Map<String, com.cordiact.core.conversation.GenerationParameters>)
    suspend fun setRole(role: SettingsModelRole, selection: ModelRoleSelection)
}

data class CapabilitySummary(val id: String, val label: String, val enabled: Boolean, val readiness: com.cordiact.core.settings.Readiness)

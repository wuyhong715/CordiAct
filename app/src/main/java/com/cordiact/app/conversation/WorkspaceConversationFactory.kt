package com.cordiact.app.conversation

import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.composer.ComposerDraft
import com.cordiact.app.experience.composer.ComposerDraftStore
import com.cordiact.app.experience.settings.AgentPresetStore
import com.cordiact.app.model.ConversationModelSelectionStore
import com.cordiact.core.conversation.AGENT_PRESET_SYSTEM_METADATA
import com.cordiact.core.conversation.ConversationSession
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.settings.WorkspaceDefaults
import com.cordiact.core.settings.WorkspaceInstructions
import com.cordiact.core.settings.resolveWorkspaceConversationModel

/** Shared creation path for new user conversations. Internal workers keep explicit profiles. */
internal class WorkspaceConversationFactory(private val app: CordiActApplication) {
    data class Created(val session: ConversationSession, val draft: ComposerDraft)

    fun create(workspaceId: String, title: String, allocatedId: String? = null): Created {
        val files = app.conversationCatalog.fileManager
        val defaults = WorkspaceDefaults(files).read(workspaceId)
        val presets = AgentPresetStore(app)
        val inherited = presets.effective(workspaceId)
        val preset = defaults.presetId?.let(presets::get) ?: inherited
        val usable = app.modelAdapter.models().filter(ModelDescriptor::supportsStreaming)
        val model = app.workspaceConnections.models(workspaceId).firstOrNull { it.id == defaults.model && it.supportsStreaming } ?: resolveWorkspaceConversationModel(usable, inherited.model, preset.model,
            ConversationModelSelectionStore(app).defaultModel(), defaults.model)
        val targetEffort = defaults.effort ?: preset.effort
        val effort = targetEffort.takeIf { it in model?.capabilities?.reasoningEfforts.orEmpty() }
            ?: ReasoningEffort.AUTO
        val draft = ComposerDraft(model = model?.id, effort = effort)
        val rules = runCatching { WorkspaceInstructions(files).read(workspaceId).text }.getOrDefault("").trim()
        val prompt = listOfNotNull(preset.systemPrompt.trim().takeIf(String::isNotEmpty),
            rules.takeIf(String::isNotEmpty)?.let { "工作区项目指令（AGENTS.md）：\n$it" }).joinToString("\n\n")
        val session = if (allocatedId == null) app.sessions.createSession(workspaceId, title)
        else app.conversationCatalog.conversationStore.create(allocatedId, workspaceId, title, System.currentTimeMillis())
        if (prompt.isNotEmpty()) app.sessions.appendMessage(session.id, MessageRole.SYSTEM, prompt, AGENT_PRESET_SYSTEM_METADATA)
        model?.id?.let { ConversationModelSelectionStore(app).setForSession(session.id, it) }
        ComposerDraftStore(app).save(session.id, draft)
        return Created(session, draft)
    }
}

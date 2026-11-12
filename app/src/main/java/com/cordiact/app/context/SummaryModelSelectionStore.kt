package com.cordiact.app.context

import android.content.Context
import com.cordiact.core.conversation.ModelId
import com.cordiact.app.experience.settings.*

/**
 * Persists the model the user picked for compaction summaries.
 *
 * Unset is a valid, meaningful state: compaction still happens, it just uses the deterministic
 * rule-based summary instead of a model call. That is the opposite of memory extraction, where an
 * unset model means "do not run at all" — a long conversation must be compacted whether or not the
 * user wants to spend a model call on making the summary better.
 *
 * Stored as provider id plus model id because a bare model id is ambiguous across providers.
 */
class SummaryModelSelectionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The chosen model, or null when the user has not chosen one (rules-only summaries). */
    fun selectedModel(): ModelId? {
        val providerId = prefs.getString(KEY_PROVIDER, null)?.takeIf { it.isNotBlank() } ?: return null
        val modelId = prefs.getString(KEY_MODEL, null)?.takeIf { it.isNotBlank() } ?: return null
        return ModelId(providerId = providerId, modelId = modelId)
    }

    fun selection(): ModelRoleSelection = when (prefs.getString("selection.mode", null)) {
        "follow" -> ModelRoleSelection.FollowConversation
        "disabled" -> ModelRoleSelection.Disabled
        else -> selectedModel()?.let { ModelRoleSelection.Explicit(it) } ?: ModelRoleSelection.Disabled
    }
    fun selectFollowingConversation() = synchronized(WRITE_LOCK) {
        check(prefs.edit().putString("selection.mode", "follow").remove(KEY_PROVIDER).remove(KEY_MODEL).putLong("revision", nextRevision()).commit())
    }
    fun resolve(available: Set<ModelId>, conversation: ModelId?): ModelRoleResolution = selection().resolve(available, conversation)

    fun select(model: ModelId) = synchronized(WRITE_LOCK) {
        check(prefs.edit().putString("selection.mode", "explicit")
            .putString(KEY_PROVIDER, model.providerId)
            .putString(KEY_MODEL, model.modelId)
            .putLong("revision", nextRevision()).commit())
    }

    /** Reverts to rule-based summaries; compaction itself keeps running. */
    fun clear() = synchronized(WRITE_LOCK) {
        check(prefs.edit().putString("selection.mode", "disabled").remove(KEY_PROVIDER).remove(KEY_MODEL).putLong("revision", nextRevision()).commit())
    }

    fun revision(): Long = prefs.getLong("revision", 0)
    private fun nextRevision(): Long = revision().also { check(it < Long.MAX_VALUE) { "ROLE_REVISION_EXHAUSTED" } } + 1

    private companion object {
        val WRITE_LOCK = Any()
        const val PREFS_NAME = "cordiact.context.summary"
        const val KEY_PROVIDER = "summary.provider"
        const val KEY_MODEL = "summary.model"
    }
}

package com.cordiact.app.memory

import android.content.Context
import com.cordiact.core.conversation.ModelId
import com.cordiact.app.experience.settings.*

/**
 * Persists the model the user picked for memory extraction.
 *
 * There is deliberately no default. Extraction makes background model calls, and a silent
 * fallback to the session model would spend the user's quota on work they never asked for.
 * An unset selection means extraction does not run at all.
 *
 * Stored as provider id plus model id because a bare model id is ambiguous across providers:
 * two providers can legitimately offer a model with the same name.
 */
class ExtractionModelSelectionStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Returns the selection, or null when none was made or the stored value is incomplete. */
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

    /** Clears the selection, which stops extraction rather than reverting to a default. */
    fun clear() = synchronized(WRITE_LOCK) {
        check(prefs.edit().putString("selection.mode", "disabled").remove(KEY_PROVIDER).remove(KEY_MODEL).putLong("revision", nextRevision()).commit())
    }

    fun revision(): Long = prefs.getLong("revision", 0)
    private fun nextRevision(): Long = revision().also { check(it < Long.MAX_VALUE) { "ROLE_REVISION_EXHAUSTED" } } + 1

    private companion object {
        val WRITE_LOCK = Any()
        const val PREFS_NAME = "cordiact.memory.extraction"
        const val KEY_PROVIDER = "model.provider"
        const val KEY_MODEL = "model.id"
    }
}

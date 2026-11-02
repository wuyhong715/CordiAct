package com.cordiact.app.memory

import android.content.Context
import com.cordiact.core.conversation.ModelId

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

    fun select(model: ModelId) {
        prefs.edit()
            .putString(KEY_PROVIDER, model.providerId)
            .putString(KEY_MODEL, model.modelId)
            .apply()
    }

    /** Clears the selection, which stops extraction rather than reverting to a default. */
    fun clear() {
        prefs.edit().remove(KEY_PROVIDER).remove(KEY_MODEL).apply()
    }

    private companion object {
        const val PREFS_NAME = "cordiact.memory.extraction"
        const val KEY_PROVIDER = "model.provider"
        const val KEY_MODEL = "model.id"
    }
}

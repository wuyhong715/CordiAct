package com.cordiact.app.experience.composer

import android.content.Context
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.conversation.ResponseSpeed

/** What one session remembers about its composer between launches. */
data class ComposerDraft(
    val text: String = "",
    val model: ModelId? = null,
    val effort: ReasoningEffort = ReasoningEffort.AUTO,
    val speed: ResponseSpeed = ResponseSpeed.AUTO,
)

/**
 * Per-session composer memory (model, tuning, unsent text). Attachments live in the attachment
 * catalogue's draft rows, keyed by the same session id, so they survive process death too.
 */
class ComposerDraftStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(sessionId: String): ComposerDraft {
        val prefix = "$sessionId."
        val provider = prefs.getString(prefix + "provider", null)
        val model = prefs.getString(prefix + "model", null)
        return ComposerDraft(
            text = prefs.getString(prefix + "text", "").orEmpty(),
            model = if (provider.isNullOrBlank() || model.isNullOrBlank()) null else ModelId(provider, model),
            effort = prefs.getString(prefix + "effort", null)?.let { runCatching { ReasoningEffort.valueOf(it) }.getOrNull() }
                ?: ReasoningEffort.AUTO,
            speed = prefs.getString(prefix + "speed", null)?.let { runCatching { ResponseSpeed.valueOf(it) }.getOrNull() }
                ?: ResponseSpeed.AUTO,
        )
    }

    fun save(sessionId: String, draft: ComposerDraft) {
        val prefix = "$sessionId."
        prefs.edit()
            .putString(prefix + "text", draft.text)
            .putString(prefix + "provider", draft.model?.providerId)
            .putString(prefix + "model", draft.model?.modelId)
            .putString(prefix + "effort", draft.effort.name)
            .putString(prefix + "speed", draft.speed.name)
            .apply()
    }

    fun lastUsed(): ComposerDraft = load("__last_used__")
    fun saveLastUsed(draft: ComposerDraft) = save("__last_used__", draft.copy(text = ""))

    fun saveTuning(sessionId: String, effort: ReasoningEffort, speed: ResponseSpeed) {
        prefs.edit()
            .putString("$sessionId.effort", effort.name)
            .putString("$sessionId.speed", speed.name)
            .apply()
    }

    fun clear(sessionId: String) {
        val keys = prefs.all.keys.filter { it.startsWith("$sessionId.") }
        if (keys.isEmpty()) return
        prefs.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    private companion object {
        const val PREFS = "composer_drafts"
    }
}

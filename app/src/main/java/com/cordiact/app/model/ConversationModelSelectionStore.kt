package com.cordiact.app.model

import android.content.Context
import com.cordiact.core.conversation.ModelId

/** A session's own model is not the foreground session's model; workers must resolve by session. */
class ConversationModelSelectionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cordiact.settings.models", Context.MODE_PRIVATE)
    fun defaultModel(): ModelId? = read("")
    fun forSession(sessionId: String): ModelId? = read("session.$sessionId.") ?: defaultModel()
    fun setDefault(model: ModelId) = write("", model)
    fun setForSession(sessionId: String, model: ModelId) = write("session.$sessionId.", model)
    fun revision(): Long = prefs.getLong("revision", 0)
    private companion object { val WRITE_LOCK = Any() }
    private fun read(prefix: String): ModelId? {
        val provider = prefs.getString("${prefix}provider", null) ?: return null
        val model = prefs.getString("${prefix}model", null) ?: return null
        return ModelId(provider, model)
    }
    private fun write(prefix: String, model: ModelId) = synchronized(WRITE_LOCK) {
        check(prefs.edit().putString("${prefix}provider", model.providerId).putString("${prefix}model", model.modelId).putLong("revision", revision().also { check(it < Long.MAX_VALUE) } + 1).commit()) { "MODEL_SELECTION_WRITE_FAILED" }
    }
}

package com.cordiact.core.security

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage

/**
 * Host-only hand-off for an approved plaintext result.
 *
 * The dispatcher stores the payload by tool call id; the turn service consumes it once, only when
 * building the immediate next model request for the same provider/model route. The payload is
 * never part of ModelMessage, session storage, trace or audit. A route change or expiry simply
 * means the placeholder stays in place and the model must request the credential again.
 */
class SensitivePayloadRegistry(
    private val clock: SecurityClock = SecurityClock.system(),
    private val ttlMillis: Long = 60_000L,
) {
    private data class Entry(
        val payload: SensitivePayload,
        val providerId: String?,
        val modelId: String?,
        val expiresAt: java.time.Instant,
    )

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    fun register(
        toolCallId: String,
        payload: SensitivePayload,
        providerId: String?,
        modelId: String?,
    ) {
        require(toolCallId.isNotBlank()) { "toolCallId must not be blank" }
        synchronized(lock) {
            entries.put(toolCallId, Entry(payload, providerId, modelId, clock.now().plusMillis(ttlMillis)))?.payload?.close()
        }
    }

    /** Removes and returns a payload only for the exact route it was approved for. */
    fun consume(toolCallId: String, providerId: String?, modelId: String?): SensitivePayload? {
        val entry = synchronized(lock) { entries.remove(toolCallId) ?: return null }
        val valid = entry.expiresAt.isAfter(clock.now()) &&
            entry.providerId == providerId &&
            entry.modelId == modelId
        if (!valid) {
            entry.payload.close()
            return null
        }
        return entry.payload
    }

    /**
     * Returns a wire-only copy of [messages] with approved plaintext substituted into the matching
     * TOOL message. The input list and any persisted history are left untouched.
     */
    fun wireMessages(
        messages: List<ModelMessage>,
        providerId: String?,
        modelId: String?,
    ): List<ModelMessage> = messages.map { message ->
        val callId = message.toolCallId
        if (message.role != MessageRole.TOOL || callId == null || providerId == null || modelId == null) {
            message
        } else {
            val payload = consume(callId, providerId, modelId) ?: return@map message
            val text = payload.use { it.toString(Charsets.UTF_8) }
            payload.close()
            message.copy(content = text)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.values.forEach { it.payload.close() }
            entries.clear()
        }
    }
}

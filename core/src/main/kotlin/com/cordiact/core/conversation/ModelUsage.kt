package com.cordiact.core.conversation

/** One observable backend invocation; retries internal to a provider are not separate records. */
data class ModelUsage(
    val requestId: String,
    val workspaceId: String?,
    val sessionId: String,
    val model: ModelId,
    val startedAt: Long,
    val status: Status,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
) {
    enum class Status { COMPLETED, FAILED, CANCELLED }
}

package com.cordiact.core.action

/** Stable, provider-neutral contract for an operation requested by a workspace. */
data class ActionRequest(
    val id: String,
    val type: String,
    val workspaceId: String,
    val input: Map<String, String> = emptyMap(),
    val capabilities: Set<String> = emptySet(),
)

enum class PolicyDecision { ALLOW, DENY, CONFIRM }

data class PolicyResult(val decision: PolicyDecision, val reason: String)

interface ActionPolicy {
    fun evaluate(request: ActionRequest): PolicyResult
}

class DenyByDefaultPolicy(
    private val allowedTypes: Set<String> = emptySet(),
) : ActionPolicy {
    override fun evaluate(request: ActionRequest): PolicyResult =
        if (request.type in allowedTypes) {
            PolicyResult(PolicyDecision.CONFIRM, "Action is registered and requires user confirmation")
        } else {
            PolicyResult(PolicyDecision.DENY, "Action type is not registered")
        }
}

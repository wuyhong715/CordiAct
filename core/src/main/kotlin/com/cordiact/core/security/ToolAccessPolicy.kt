package com.cordiact.core.security

/** Coarse effect class used by the envelope check. Argument-level path checks stay in the tool. */
enum class ToolEffectClass {
    UNKNOWN,
    PURE,
    WORKSPACE_READ,
    WORKSPACE_WRITE,
    DEVICE_READ,
    DEVICE_WRITE,
    NETWORK,
    COMMAND,
    ROOT,
    CREDENTIAL,
    DESTRUCTIVE,
}

sealed interface ToolAccessDecision {
    data object Allow : ToolAccessDecision

    data class Deny(val reasonCode: String) : ToolAccessDecision
    data object RequireReview : ToolAccessDecision
}

fun interface ToolEffectClassifier {
    fun classify(toolId: String, ownerPluginId: String): ToolEffectClass
}

/**
 * Host-level envelope check for non-credential tools. It is intentionally coarse: a classifier
 * names the effect, and this policy enforces the currently selected access envelope. Credential
 * tools still go through SensitiveOperationGate for their per-call approval.
 *
 * Unknown tools are classified by the host as PURE by default only if the host chooses that; the
 * classifier in the app maps known built-ins and leaves third-party tools explicit.
 */
class EnvelopeToolAccessPolicy(
    private val classifier: ToolEffectClassifier,
) {
    fun evaluate(context: SecurityContext, toolId: String, ownerPluginId: String): ToolAccessDecision {
        val decision = evaluateEnvelope(context, toolId, ownerPluginId)
        if (decision != ToolAccessDecision.Allow) return decision
        return if (context.profile.preset == PermissionPreset.AUTO_APPROVAL &&
            ownerPluginId != CREDENTIAL_VAULT_PLUGIN_ID
        ) ToolAccessDecision.RequireReview else ToolAccessDecision.Allow
    }

    private fun evaluateEnvelope(context: SecurityContext, toolId: String, ownerPluginId: String): ToolAccessDecision {
        val effect = classifier.classify(toolId, ownerPluginId)
        val envelope = context.profile.accessEnvelope
        return when (effect) {
            ToolEffectClass.UNKNOWN -> ToolAccessDecision.Deny("UNCLASSIFIED_TOOL_DENIED")
            ToolEffectClass.PURE,
            ToolEffectClass.WORKSPACE_READ,
            ToolEffectClass.CREDENTIAL,
            -> ToolAccessDecision.Allow

            ToolEffectClass.WORKSPACE_WRITE -> {
                if (envelope == AccessEnvelope.READ_ONLY) {
                    ToolAccessDecision.Deny("READ_ONLY_WRITE_DENIED")
                } else {
                    ToolAccessDecision.Allow
                }
            }

            ToolEffectClass.DEVICE_READ,
            ToolEffectClass.DEVICE_WRITE,
            -> {
                if (envelope == AccessEnvelope.WORKSPACE_AND_DEVICE_STORAGE) {
                    ToolAccessDecision.Allow
                } else {
                    ToolAccessDecision.Deny("DEVICE_STORAGE_NOT_ALLOWED")
                }
            }

            ToolEffectClass.NETWORK -> {
                if (envelope == AccessEnvelope.READ_ONLY) {
                    ToolAccessDecision.Deny("READ_ONLY_NETWORK_DENIED")
                } else {
                    ToolAccessDecision.Allow
                }
            }

            ToolEffectClass.COMMAND -> {
                // Arbitrary command backends cannot prove the Workspace boundary. They are only
                // reachable after the user explicitly selects the device-storage envelope; this
                // is a documented limitation, not a claim that a shell is confined to one Workspace.
                if (envelope == AccessEnvelope.WORKSPACE_AND_DEVICE_STORAGE) {
                    ToolAccessDecision.Allow
                } else {
                    ToolAccessDecision.Deny("COMMAND_REQUIRES_FULL_ACCESS")
                }
            }

            ToolEffectClass.ROOT -> ToolAccessDecision.Deny("ROOT_REQUIRES_SEPARATE_AUTHORIZATION")

            ToolEffectClass.DESTRUCTIVE -> {
                if (envelope == AccessEnvelope.READ_ONLY) {
                    ToolAccessDecision.Deny("READ_ONLY_DESTRUCTIVE_DENIED")
                } else {
                    ToolAccessDecision.Allow
                }
            }
        }
    }
}

package com.cordiact.core.agent

import com.cordiact.core.tool.ToolAvailability
import com.cordiact.core.tool.ToolManagerService

/**
 * Adapts the tool manager to the resolver's read-only view.
 *
 * `enabledToolIds` deliberately uses [ToolAvailability.dispatchable], not
 * `enabledForWorkspace`: a tool whose owner plugin is disabled or whose
 * capabilities are not granted cannot be used anyway, so surfacing it as
 * "enabled" would let a profile advertise a tool that fails at invoke time.
 *
 * `registeredToolIds` uses the full registry, which is what the dangling-reference
 * check needs: a profile naming a tool that does not exist at all is a defect,
 * while a profile naming a registered-but-disabled tool is merely unavailable.
 */
class ToolManagerVisibility(private val tools: ToolManagerService) : ToolVisibility {

    override fun enabledToolIds(workspaceId: String): Set<String> =
        tools.list(workspaceId)
            .filter(ToolAvailability::dispatchable)
            .map { it.descriptor.id }
            .toSet()

    override fun registeredToolIds(workspaceId: String): Set<String> =
        tools.list(workspaceId)
            .map { it.descriptor.id }
            .toSet()
}

/** A registry entry flattened to what the resolver needs: does this model id exist? */
data class RegisteredModel(val providerId: String, val modelId: String)

/**
 * Adapts a provider list to the resolver's model check.
 *
 * The registry stores `models: List<String>` per provider and has no lookup by id,
 * so resolution flattens the list once per call. That is fine at this size, and
 * keeping the flattening here means the profile contract never depends on the
 * model module.
 */
class ProviderModelVisibility(
    private val modelsProvider: () -> List<RegisteredModel>,
) : ModelVisibility {

    override fun isRegistered(modelId: String): Boolean =
        modelsProvider().any { it.modelId == modelId }
}

package com.cordiact.app.experience.settings

import com.cordiact.core.conversation.ModelId

sealed interface ModelRoleSelection {
    data object FollowConversation : ModelRoleSelection
    data object Disabled : ModelRoleSelection
    data class Explicit(val model: ModelId) : ModelRoleSelection
}
sealed interface ModelRoleResolution {
    data object Disabled : ModelRoleResolution
    data class Resolved(val model: ModelId) : ModelRoleResolution
    data class Invalid(val reason: String) : ModelRoleResolution
}
fun ModelRoleSelection.resolve(available: Set<ModelId>, conversation: ModelId?): ModelRoleResolution {
    val model = when(this) {
        ModelRoleSelection.Disabled -> return ModelRoleResolution.Disabled
        ModelRoleSelection.FollowConversation -> conversation ?: return ModelRoleResolution.Invalid("CONVERSATION_MODEL_MISSING")
        is ModelRoleSelection.Explicit -> model
    }
    return if (model in available) ModelRoleResolution.Resolved(model) else ModelRoleResolution.Invalid("MODEL_UNAVAILABLE")
}

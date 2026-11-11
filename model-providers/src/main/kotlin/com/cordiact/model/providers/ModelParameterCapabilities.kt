package com.cordiact.model.providers

import com.cordiact.core.conversation.ReasoningEffort
import com.cordiact.core.conversation.ResponseSpeed

/** Explicit wire values supported by one model. Missing values are unsupported, never downgraded. */
data class ModelParameterCapabilities(
    val reasoningEffortValues: Map<ReasoningEffort, String> = emptyMap(),
    val responseSpeedValues: Map<ResponseSpeed, String> = emptyMap(),
) {
    init {
        require(reasoningEffortValues.keys.none { it == ReasoningEffort.AUTO }) {
            "AUTO reasoning effort is represented by omitting the parameter"
        }
        require(responseSpeedValues.keys.none { it == ResponseSpeed.AUTO }) {
            "AUTO response speed is represented by omitting the parameter"
        }
        require((reasoningEffortValues.values + responseSpeedValues.values).all { it.isNotBlank() }) {
            "Model parameter wire values must not be blank"
        }
    }

    companion object {
        /** Build a model-specific OpenAI mapping; unsupported levels stay absent. */
        fun openAi(
            reasoningEfforts: Set<ReasoningEffort>,
            supportsFast: Boolean = true,
            ultrafastWireValue: String? = null,
        ): ModelParameterCapabilities {
            require(ReasoningEffort.AUTO !in reasoningEfforts) { "AUTO is represented by omitting the parameter" }
            val standardValues = mapOf(
                ReasoningEffort.NONE to "none",
                ReasoningEffort.MINIMAL to "minimal",
                ReasoningEffort.LOW to "low",
                ReasoningEffort.MEDIUM to "medium",
                ReasoningEffort.HIGH to "high",
                ReasoningEffort.XHIGH to "xhigh",
                ReasoningEffort.MAX to "max",
            )
            val speedValues = buildMap {
                if (supportsFast) put(ResponseSpeed.FAST, "fast")
                ultrafastWireValue?.let { put(ResponseSpeed.ULTRAFAST, it) }
            }
            return ModelParameterCapabilities(
                reasoningEffortValues = standardValues.filterKeys { it in reasoningEfforts },
                responseSpeedValues = speedValues,
            )
        }
    }
}

package com.cordiact.subagent

import com.cordiact.core.subagent.Combine
import com.cordiact.core.subagent.OrchestrationLimits
import com.cordiact.core.subagent.OrchestrationPlan
import com.cordiact.core.subagent.OrchestrationStage
import com.cordiact.core.subagent.StageMode
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** A plan that cannot be run. Always discovered before any child is started. */
class OrchestrationPlanException(message: String) : IllegalArgumentException(message)

/**
 * Parses and validates a plan.
 *
 * Stricter than a JSON Schema could be, on purpose: the value of a plan is that its cost is known
 * before it runs, and the interesting failures are relational (duplicate ids, a `REDUCE` with no
 * previous stage, a `{{item}}` that no stage supplies), not shape-level. Reporting them as
 * "stage 2 references an unknown placeholder" is only possible here.
 *
 * Every rejection happens before the first child, so a malformed plan costs nothing.
 */
object OrchestrationPlanParser {

    fun parse(json: String, limits: OrchestrationLimits): OrchestrationPlan {
        if (json.isBlank()) throw OrchestrationPlanException("plan is empty")

        val root = try {
            JsonParser.parseString(json)
        } catch (failure: RuntimeException) {
            throw OrchestrationPlanException("plan is not valid JSON: ${failure.message}")
        }
        if (!root.isJsonObject) throw OrchestrationPlanException("plan must be a JSON object")
        val planJson = root.asJsonObject

        val name = planJson.optionalString("name")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw OrchestrationPlanException("plan.name is required")
        if (name.length > MAX_NAME_CHARS) {
            throw OrchestrationPlanException("plan.name must be at most $MAX_NAME_CHARS characters")
        }
        if (name.any { it.isWhitespace() }) {
            throw OrchestrationPlanException("plan.name must not contain whitespace; use kebab-case")
        }

        val stagesJson = planJson.get("stages")
            ?: throw OrchestrationPlanException("plan.stages is required")
        if (!stagesJson.isJsonArray) throw OrchestrationPlanException("plan.stages must be an array")
        val stageArray = stagesJson.asJsonArray
        if (stageArray.size() == 0) throw OrchestrationPlanException("plan.stages must not be empty")
        if (stageArray.size() > limits.maxStages) {
            throw OrchestrationPlanException("plan has ${stageArray.size()} stages; the limit is ${limits.maxStages}")
        }

        val stages = stageArray.mapIndexed { index, element -> parseStage(index, element, limits) }

        val duplicates = stages.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) {
            throw OrchestrationPlanException("duplicate stage id(s): ${duplicates.sorted().joinToString()}")
        }

        // A REDUCE with nothing before it has no `{{prev}}` to fill, and there is no sensible
        // empty value for it — the stage would run a prompt with a visible hole in it.
        stages.forEachIndexed { index, stage ->
            if (stage.mode == StageMode.REDUCE && index == 0) {
                throw OrchestrationPlanException(
                    "stage '${stage.id}' is REDUCE but is the first stage; " +
                        "there is no previous stage to reduce",
                )
            }
        }

        val totalChildren = stages.sumOf { it.childCount }
        if (totalChildren > limits.maxTotalChildren) {
            throw OrchestrationPlanException(
                "plan would start $totalChildren agents; the limit is ${limits.maxTotalChildren}",
            )
        }

        return OrchestrationPlan(name, stages)
    }

    private fun parseStage(index: Int, element: JsonElement, limits: OrchestrationLimits): OrchestrationStage {
        if (!element.isJsonObject) throw OrchestrationPlanException("stages[$index] must be an object")
        val stageJson = element.asJsonObject

        val id = stageJson.optionalString("id")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw OrchestrationPlanException("stages[$index].id is required")
        if (!id.matches(STAGE_ID_PATTERN)) {
            throw OrchestrationPlanException(
                "stages[$index].id '$id' must match [a-zA-Z0-9_-]{1,40}",
            )
        }

        val task = stageJson.optionalString("task")?.takeIf { it.isNotBlank() }
            ?: throw OrchestrationPlanException("stages[$index] ('$id') .task is required")

        val mode = when (val raw = stageJson.optionalString("mode")?.trim()?.uppercase()) {
            null, "", "MAP" -> StageMode.MAP
            "REDUCE" -> StageMode.REDUCE
            else -> throw OrchestrationPlanException(
                "stages[$index] ('$id') .mode '$raw' is not one of MAP, REDUCE",
            )
        }

        val combine = when (val raw = stageJson.optionalString("combine")?.trim()?.uppercase()) {
            null, "", "JSON" -> Combine.JSON
            "LINES" -> Combine.LINES
            else -> throw OrchestrationPlanException(
                "stages[$index] ('$id') .combine '$raw' is not one of JSON, LINES",
            )
        }

        val items = parseItems(index, id, stageJson, limits)

        when (mode) {
            StageMode.MAP -> {
                // A MAP stage over items with no placeholder would start N identical children —
                // always a mistake, and an expensive one.
                if (items.isNotEmpty() && !task.contains(ITEM_PLACEHOLDER)) {
                    throw OrchestrationPlanException(
                        "stages[$index] ('$id') is MAP with ${items.size} items but .task has no " +
                            "$ITEM_PLACEHOLDER placeholder",
                    )
                }
            }
            StageMode.REDUCE -> {
                if (items.isNotEmpty()) {
                    throw OrchestrationPlanException(
                        "stages[$index] ('$id') is REDUCE and must not declare items",
                    )
                }
                if (!task.contains(PREV_PLACEHOLDER)) {
                    throw OrchestrationPlanException(
                        "stages[$index] ('$id') is REDUCE but .task has no $PREV_PLACEHOLDER placeholder",
                    )
                }
            }
        }

        return OrchestrationStage(id = id, task = task, items = items, mode = mode, combine = combine)
    }

    private fun parseItems(
        index: Int,
        id: String,
        stageJson: JsonObject,
        limits: OrchestrationLimits,
    ): List<String> {
        val itemsJson = stageJson.get("items") ?: return emptyList()
        if (itemsJson.isJsonNull) return emptyList()
        if (!itemsJson.isJsonArray) {
            throw OrchestrationPlanException("stages[$index] ('$id') .items must be an array of strings")
        }
        val array: JsonArray = itemsJson.asJsonArray
        if (array.size() > limits.maxItemsPerStage) {
            throw OrchestrationPlanException(
                "stages[$index] ('$id') has ${array.size()} items; the limit is ${limits.maxItemsPerStage}",
            )
        }
        return array.mapIndexed { itemIndex, item ->
            if (!item.isJsonPrimitive || !item.asJsonPrimitive.isString) {
                throw OrchestrationPlanException(
                    "stages[$index] ('$id') .items[$itemIndex] must be a string",
                )
            }
            item.asString
        }
    }

    private fun JsonObject.optionalString(key: String): String? {
        val value = get(key) ?: return null
        if (value.isJsonNull) return null
        // A number or boolean here is a plan authoring mistake, not a value to coerce silently.
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
            throw OrchestrationPlanException("'$key' must be a string")
        }
        return value.asString
    }

    const val ITEM_PLACEHOLDER = "{{item}}"
    const val PREV_PLACEHOLDER = "{{prev}}"
    private const val MAX_NAME_CHARS = 120
    private val STAGE_ID_PATTERN = Regex("[a-zA-Z0-9_-]{1,40}")
}

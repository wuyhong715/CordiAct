package com.cordiact.app.tool

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.conversation.ModelToolCall
import com.cordiact.core.conversation.ModelToolSpec
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolManagerException
import com.cordiact.core.tool.ToolManagerService
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType
import com.cordiact.core.toolloop.ToolDispatcher
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Bridges the model's tool contract to the tool manager.
 *
 * All policy stays on the manager's side: enablement, owner state, capabilities and the `@`
 * grant are re-checked there on every dispatch. This class only translates shapes, so a bug here
 * can produce a wrong call but never an unauthorized one.
 */
class AndroidToolDispatcher(
    private val tools: ToolManagerService,
) : ToolDispatcher {

    override fun dispatch(context: ToolInvocationContext, call: ModelToolCall): ModelMessage {
        val result = try {
            tools.invoke(context, call.name, parseArguments(call.argumentsJson))
        } catch (e: ToolManagerException) {
            // Reported back to the model rather than thrown: a refused or unknown tool is
            // something the model can react to, and aborting would lose the turn's context.
            return failure(call, e.code.name, e.message ?: "Tool call was refused")
        } catch (e: JSONException) {
            return failure(call, "INVALID_ARGUMENT", "Arguments were not a JSON object")
        } catch (e: Exception) {
            return failure(call, "TOOL_FAILED", e.message ?: "Tool call failed")
        }
        return ModelMessage(
            role = MessageRole.TOOL,
            content = renderResult(result),
            toolCallId = call.id,
            toolName = call.name,
        )
    }

    private fun failure(call: ModelToolCall, code: String, message: String) = ModelMessage(
        role = MessageRole.TOOL,
        content = """{"error":{"code":"$code","message":${JSONObject.quote(message)}}}""",
        toolCallId = call.id,
        toolName = call.name,
    )

    private fun parseArguments(json: String): ToolArguments {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return ToolArguments()
        val objectValue = JSONObject(trimmed)
        val values = objectValue.keys().asSequence().associateWith { key ->
            toToolValue(objectValue.get(key))
        }
        return ToolArguments(values)
    }

    private fun toToolValue(value: Any?): ToolValue = when (value) {
        null, JSONObject.NULL -> ToolValue.NullValue
        is Boolean -> ToolValue.BooleanValue(value)
        is Int -> ToolValue.NumberValue(value.toDouble())
        is Long -> ToolValue.NumberValue(value.toDouble())
        is Double -> ToolValue.NumberValue(value)
        is String -> ToolValue.StringValue(value)
        is JSONArray -> ToolValue.ArrayValue((0 until value.length()).map { toToolValue(value.get(it)) })
        is JSONObject -> ToolValue.ObjectValue(
            value.keys().asSequence().associateWith { toToolValue(value.get(it)) },
        )
        else -> ToolValue.StringValue(value.toString())
    }

    private fun renderResult(result: ToolResult): String {
        if (result.values.isEmpty()) return "{}"
        return JSONObject().apply {
            result.values.forEach { (key, value) -> put(key, toJson(value)) }
        }.toString()
    }

    private fun toJson(value: ToolValue): Any = when (value) {
        is ToolValue.StringValue -> value.value
        is ToolValue.NumberValue -> value.value
        is ToolValue.BooleanValue -> value.value
        ToolValue.NullValue -> JSONObject.NULL
        is ToolValue.ArrayValue -> JSONArray(value.values.map(::toJson))
        is ToolValue.ObjectValue -> JSONObject().apply {
            value.values.forEach { (key, nested) -> put(key, toJson(nested)) }
        }
    }

    companion object {
        /**
         * Renders the tool catalog as model-facing specs.
         *
         * Uses the same descriptors the manager reports, so what the model is offered and what
         * the manager will accept cannot drift apart.
         */
        fun specs(descriptors: List<ToolDescriptor>): List<ModelToolSpec> = descriptors.map { descriptor ->
            ModelToolSpec(
                name = descriptor.id,
                description = descriptor.description,
                parametersJson = schemaJson(
                    descriptor.inputSchema.parameters,
                    descriptor.inputSchema.allowAdditional,
                ),
            )
        }

        private fun schemaJson(parameters: List<ToolParameter>, allowAdditional: Boolean): String {
            val properties = JSONObject()
            for (parameter in parameters) {
                properties.put(parameter.name, JSONObject().put("type", parameterType(parameter.type)))
            }
            val required = JSONArray()
            parameters.filter { it.required }.forEach { required.put(it.name) }
            return JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", required)
                put("additionalProperties", allowAdditional)
            }.toString()
        }

        private fun parameterType(type: ToolValueType): String = when (type) {
            ToolValueType.STRING -> "string"
            ToolValueType.NUMBER -> "number"
            ToolValueType.BOOLEAN -> "boolean"
        }
    }
}

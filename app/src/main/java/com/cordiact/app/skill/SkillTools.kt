package com.cordiact.app.skill

import com.cordiact.core.skill.SKILL_PLUGIN_ID
import com.cordiact.core.skill.SkillService
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolManagerException
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

const val SKILL_OPEN_TOOL_ID = "skill.open"

/**
 * Registers the `skill` tool.
 *
 * The index injected into the prompt carries names and descriptions only; this is how the model
 * fetches a body once it has decided a skill applies. Reading bodies through a tool rather than
 * injecting all of them keeps the per-turn cost proportional to the skills actually used.
 *
 * Owned by the skill plugin, so disabling that plugin removes the tool with it.
 */
fun registerSkillTools(registry: InMemoryToolDefinitionRegistry, skills: SkillService) {
    registry.register(
        ToolDefinition(
            descriptor = ToolDescriptor(
                id = SKILL_OPEN_TOOL_ID,
                name = "Open a skill",
                description = "Open a Workspace skill by name and return its instructions.",
                inputSchema = ToolInputSchema(
                    listOf(ToolParameter("name", ToolValueType.STRING, required = true)),
                    allowAdditional = false,
                ),
                ownerPluginId = SKILL_PLUGIN_ID,
            ),
            executor = { context, arguments ->
                val name = (arguments.values["name"] as? ToolValue.StringValue)?.value
                    ?: throw ToolManagerException(
                        ToolManagerException.Code.INVALID_ARGUMENT,
                        "Skill name is required",
                    )
                // Resolved against the calling Workspace, so a skill cannot be read out of a
                // Workspace this turn was not granted.
                val body = skills.body(context.workspaceId, name)
                    ?: throw ToolManagerException(
                        ToolManagerException.Code.INVALID_ARGUMENT,
                        "No skill named '$name' in this Workspace",
                    )
                ToolResult(mapOf("content" to ToolValue.StringValue(body)))
            },
        ),
    )
}

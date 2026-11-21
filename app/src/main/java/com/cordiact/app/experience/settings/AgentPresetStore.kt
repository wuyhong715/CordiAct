package com.cordiact.app.experience.settings

import android.content.Context
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.conversation.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class AgentPreset(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val model: ModelId? = null,
    val effort: ReasoningEffort = ReasoningEffort.AUTO,
    val instructions: String = "",
    val capabilities: Set<String> = setOf("files"),
    val scope: String = "read-only",
    val builtIn: Boolean = false,
)

/** The library saves configuration locally; this store does not grant runtime capabilities. */
internal class AgentPresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("cordiact.settings.agent-presets", Context.MODE_PRIVATE)
    fun all() = builtIns + custom()
    fun get(id: String) = all().firstOrNull { it.id == id }
    fun globalId() = prefs.getString("global", "general")?.takeIf { get(it) != null } ?: "general"
    fun workspaceId(workspace: String) = workspace.takeIf { it.isNotBlank() }?.let { prefs.getString("workspace:$it", null) }
        ?.takeIf { get(it) != null }
    fun effective(workspace: String) = get(workspaceId(workspace) ?: globalId()) ?: builtIns.first()
    fun setDefault(workspace: String?, id: String?) {
        if (id != null && get(id) == null) return
        if (workspace != null && workspace.isBlank()) return
        val k = if (workspace == null) "global" else "workspace:$workspace"
        prefs.edit().apply { if (id == null) remove(k) else putString(k, id) }.apply()
    }
    fun save(preset: AgentPreset) {
        if (preset.builtIn || preset.name.isBlank() || builtIns.any { it.id == preset.id }) return
        val record = preset.copy(id = preset.id.ifBlank { UUID.randomUUID().toString() }, name = preset.name.trim(), description = preset.description.trim())
        write(custom().let { records -> if (records.any { it.id == record.id }) records.map { if (it.id == record.id) record else it } else records + record })
    }
    fun remove(id: String) {
        if (builtIns.any { it.id == id }) return
        val editor = prefs.edit().putString("custom", encode(custom().filterNot { it.id == id }))
        prefs.all.filter { (key, value) -> (key == "global" || key.startsWith("workspace:")) && value == id }.keys.forEach { editor.remove(it) }
        editor.apply()
    }
    private fun custom(): List<AgentPreset> = runCatching {
        val arr = JSONArray(prefs.getString("custom", "[]"))
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::decode) }
    }.getOrDefault(emptyList())
    private fun write(records: List<AgentPreset>) = prefs.edit().putString("custom", encode(records)).apply()

    companion object {
        private val builtIns = listOf(
            AgentPreset("general", "通用协作", "梳理需求与日常协作", instructions = "先明确目标，使用已有上下文，核对后再报告。",
                capabilities = setOf("files", "search", "skills"), scope = "workspace", builtIn = true),
            AgentPreset("code", "代码实现", "在当前仓库开发与验证", effort = ReasoningEffort.HIGH,
                instructions = "先读项目约定，小步修改，运行必要的验证。", capabilities = setOf("files", "terminal", "skills", "mcp"), scope = "workspace", builtIn = true),
        )
        private fun encode(records: List<AgentPreset>) = JSONArray().apply {
            records.forEach { p -> put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("description", p.description)
                put("provider", p.model?.providerId); put("model", p.model?.modelId)
                put("effort", p.effort.name); put("instructions", p.instructions); put("scope", p.scope)
                put("capabilities", JSONArray(p.capabilities.toList()))
            }) }
        }.toString()
        private fun decode(o: JSONObject): AgentPreset = AgentPreset(
            o.getString("id"), o.getString("name"), o.optString("description"),
            o.optString("provider").takeIf { it.isNotBlank() }?.let { provider ->
                o.optString("model").takeIf { it.isNotBlank() }?.let { ModelId(provider, it) }
            },
            ReasoningEffort.entries.firstOrNull { it.name == o.optString("effort") } ?: ReasoningEffort.AUTO,
            o.optString("instructions"), o.optJSONArray("capabilities")?.let { a ->
                (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
            }.orEmpty(), o.optString("scope", "read-only"),
        )
    }
}

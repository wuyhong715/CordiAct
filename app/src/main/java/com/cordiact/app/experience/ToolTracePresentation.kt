package com.cordiact.app.experience

import com.cordiact.app.experience.model.ToolActivity
import com.cordiact.app.experience.model.ToolPhase
import com.cordiact.core.conversation.ToolHistoryFormat
import com.cordiact.core.conversation.ToolHistoryProvenance
import com.cordiact.core.conversation.ToolHistorySource
import com.cordiact.core.vault.CredentialToolIds
import org.json.JSONObject

/** Strict lookup metadata from the host trace, not from a model response or result body. */
internal fun decodeToolTraceProvenance(value: JSONObject?): ToolHistoryProvenance? {
    if (value == null || value.length() != 4) return null
    val owner = (value.opt("ownerPluginId") as? String)?.takeIf(historyId::matches) ?: return null
    val id = (value.opt("toolId") as? String)?.takeIf(historyId::matches) ?: return null
    val source = ToolHistorySource.entries.find { it.wireValue == value.opt("source") } ?: return null
    val format = ToolHistoryFormat.entries.find { it.wireValue == value.opt("format") } ?: return null
    return ToolHistoryProvenance(owner, id, source, format)
}

private val historyId = Regex("[A-Za-z0-9_][A-Za-z0-9._:-]{0,159}")
private val restrictedLegacyTools = setOf(
    CredentialToolIds.LIST, CredentialToolIds.READ, CredentialToolIds.WRITE, CredentialToolIds.DELETE, CredentialToolIds.USE,
)

/** Legacy credential traces may contain raw input. Keep status, but do not expose details or copy them. */
internal fun ToolActivity.forToolInspector(): ToolActivity {
    if (name in restrictedLegacyTools || provenance?.toolId in restrictedLegacyTools) {
        return copy(arguments = "保护性隐藏：此工具的调用详情可能包含敏感数据。", summary = null, provenance = null)
    }
    return copy(arguments = arguments.take(600), summary = summary?.take(600),
        provenance = provenance.takeIf { phase == ToolPhase.SUCCEEDED })
}

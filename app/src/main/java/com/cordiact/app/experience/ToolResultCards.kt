package com.cordiact.app.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cordiact.core.conversation.ToolHistoryFormat
import com.cordiact.core.conversation.ToolHistoryProvenance
import com.cordiact.core.conversation.ToolHistorySource
import com.cordiact.core.plugin.ContextManagedPlugin
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.plugin.RuntimeAwarePlugin
import com.cordiact.core.subagent.SUBAGENT_LIST_TOOL_ID
import com.cordiact.core.subagent.SUBAGENT_PLUGIN_ID
import com.cordiact.interaction.ToolResultCard
import com.cordiact.interaction.TrustedToolResultContributions

internal fun builtinToolResultCardsSpec() = PluginSpec("core.tool-result-cards", "0.1.0", PluginTarget.ANDROID_NATIVE)

private fun presentationKey(owner: String, id: String) =
    ToolHistoryProvenance(owner, id, ToolHistorySource.HOST_REGISTRATION, ToolHistoryFormat.BOUNDED_SUMMARY_V1)

private val recipes = linkedMapOf(
    presentationKey("pkg.core-quick-notes", "extension.pkg.core-quick-notes.checklist") to
        ToolResultCard("发布检查清单", listOf("核对改动差异", "运行相关测试", "记录验证结果")),
    presentationKey(SUBAGENT_PLUGIN_ID, SUBAGENT_LIST_TOOL_ID) to
        ToolResultCard("子 Agent 列表", listOf("已读取当前会话的子 Agent 状态。")),
)

internal fun builtinToolResultDirectory() = TrustedToolResultContributions(recipes.keys.associateWith { builtinToolResultCardsSpec().id })

internal class BuiltinToolResultCards(kernel: PluginKernel, directory: TrustedToolResultContributions) : RuntimeAwarePlugin {
    override val spec = builtinToolResultCardsSpec()
    private val runtime = ContextManagedPlugin(spec, kernel, object : ContextPlugin {
        override fun start(context: PluginContext) {
            recipes.forEach { (key, card) -> directory.register(context, key, card) }
        }
    })
    override fun onRegister() = runtime.onRegister()
    override fun onEnable() { runtime.onRegister(); runtime.onEnable() }
    override fun onDisable() = runtime.onDisable()
    override fun onUninstall() = runtime.onUninstall()
    override fun runtimeStatus() = runtime.runtimeStatus()
}

@Composable
internal fun ToolResultCardView(card: ToolResultCard, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag("tool-result-card"), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(card.title, style = MaterialTheme.typography.titleSmall)
            card.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

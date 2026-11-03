package com.cordiact.app.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cordiact.app.conversation.BundledConversationPlugins
import com.cordiact.core.search.SearchMode
import com.cordiact.core.search.WorkspaceSearchPolicy

/**
 * Workspace search settings.
 *
 * Deliberately plain: mode, provider pins, monthly budget, and per-provider API
 * keys. Keys are written through the Keystore-backed store and never displayed
 * back, so a stored key shows as a label rather than its value.
 */
@Composable
fun SearchSettingsDialog(
    plugins: BundledConversationPlugins,
    workspaceId: String,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val registry = plugins.searchRegistry
    val policyStore = plugins.searchPolicyStore
    val usageStore = plugins.searchUsageStore
    val keyStore = plugins.searchApiKeyStore
    val settings = plugins.searchProviderSettings

    var policy by remember { mutableStateOf(policyStore.policy(workspaceId)) }
    var stateVersion by remember { mutableStateOf(0) }
    var keyDraft by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var message by remember { mutableStateOf<String?>(null) }

    val state = remember(policy, stateVersion, registry) {
        if (registry == null) {
            null
        } else {
            buildSearchSettingsState(
                policy = policy,
                registry = registry,
                usedThisMonth = usageStore.usedThisMonth(workspaceId),
                providerHasKey = { providerId ->
                    if (providerId.startsWith("deepseek")) {
                        plugins.apiKeyProvider.apiKey() != null
                    } else {
                        keyStore?.has(providerId) == true
                    }
                },
            )
        }
    }

    fun persist(next: WorkspaceSearchPolicy) {
        policy = next
        policyStore.save(workspaceId, next)
        stateVersion++
        onChanged()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("联网搜索") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("模式", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip("自动", policy.mode == SearchMode.AUTO) {
                        persist(policy.copy(mode = SearchMode.AUTO))
                    }
                    ModeChip("仅工具", policy.mode == SearchMode.TOOL) {
                        persist(policy.copy(mode = SearchMode.TOOL))
                    }
                    ModeChip("仅原生", policy.mode == SearchMode.NATIVE) {
                        persist(policy.copy(mode = SearchMode.NATIVE))
                    }
                    ModeChip("关闭", policy.mode == SearchMode.OFF) {
                        persist(policy.copy(mode = SearchMode.OFF))
                    }
                }
                Text(
                    "自动：当前模型有可用的原生搜索时优先原生，否则使用外部搜索服务。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                state?.selectionWarning()?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                Text("搜索服务", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip("自动选择", policy.searchProviderId == null) {
                        persist(policy.copy(searchProviderId = null))
                    }
                }
                state?.searchProviders?.forEach { choice ->
                    ModeChip(
                        if (choice.configured) choice.label else choice.label + "（未配置）",
                        policy.searchProviderId == choice.id,
                    ) {
                        persist(policy.copy(searchProviderId = choice.id))
                    }
                    choice.priceNote?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text("抓取服务", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeChip("自动选择", policy.fetchProviderId == null) {
                        persist(policy.copy(fetchProviderId = null))
                    }
                }
                state?.fetchProviders?.forEach { choice ->
                    ModeChip(
                        if (choice.configured) choice.label else choice.label + "（未配置）",
                        policy.fetchProviderId == choice.id,
                    ) {
                        persist(policy.copy(fetchProviderId = choice.id))
                    }
                }

                Text("月预算（计费调用次数）", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null, 100, 500, 2000).forEach { limit ->
                        ModeChip(limit?.toString() ?: "不限", policy.monthlyBudgetCalls == limit) {
                            persist(policy.copy(monthlyBudgetCalls = limit))
                        }
                    }
                }
                state?.budgetLabel?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = {
                    usageStore.reset(workspaceId)
                    stateVersion++
                    onChanged()
                }) { Text("重置本月计数") }

                Text("API key", style = MaterialTheme.typography.labelLarge)
                val keyProviderIds = state?.searchProviders.orEmpty()
                    .map { it.id }
                    .filterNot { it.startsWith("deepseek") }
                    .distinct()
                keyProviderIds.forEach { providerId ->
                    val stored = keyStore?.has(providerId) == true
                    OutlinedTextField(
                        value = keyDraft[providerId] ?: "",
                        onValueChange = { keyDraft = keyDraft + (providerId to it) },
                        label = { Text(if (stored) "$providerId（已保存，输入可覆盖）" else providerId) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = {
                            val value = keyDraft[providerId]?.trim().orEmpty()
                            if (value.isEmpty() || keyStore == null) {
                                message = "请先输入 key"
                            } else {
                                runCatching { keyStore.put(providerId, value) }
                                    .onSuccess {
                                        keyDraft = keyDraft - providerId
                                        message = "$providerId 已保存"
                                        stateVersion++
                                        onChanged()
                                    }
                                    .onFailure { message = "保存失败：" + (it.message ?: "系统密钥库不可用") }
                            }
                        }) { Text("保存") }
                        if (stored) {
                            TextButton(onClick = {
                                keyStore?.remove(providerId)
                                message = "$providerId 已删除"
                                stateVersion++
                                onChanged()
                            }) { Text("删除") }
                        }
                    }
                }

                settings?.let {
                    Text(
                        "Brave 地区：" + (it.braveCountry() ?: "默认") +
                            " · Exa 类型：" + it.exaType() +
                            " · Tavily 深度：" + it.searchDepth(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "查询词会发送到所选服务商；原生搜索按模型厂商计费。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Text(
            text = if (selected) "✓ " + label else label,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

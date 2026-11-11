package com.cordiact.app.experience.composer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.*
import com.cordiact.model.providers.ModelProtocol

/** Full-screen management route, never an oversized overlay over the transcript. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModelManagementScreen(state: ExperienceState,
    add: (String, String, ModelProtocol, String, (String?) -> Unit) -> Unit, onBack: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    DisposableEffect(activity) {
        val alreadySecure = activity?.window?.attributes?.flags?.and(android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0
        activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { if (!alreadySecure) activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") } // Secret is never Saveable.
    var protocol by remember { mutableStateOf(ModelProtocol.OPENAI_CHAT_COMPLETIONS) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    fun back() { if (!saving) { if (editing && (name.isNotEmpty() || url.isNotEmpty() || key.isNotEmpty())) confirmDiscard = true else if (editing) editing = false else onBack() } }
    BackHandler { back() }
    Scaffold(topBar = { TopAppBar(title = { Text(if (editing) "添加模型提供商" else "模型提供商") },
        navigationIcon = { IconButton({ back() }) { AppGlyph(GlyphName.Back, "返回") } },
        actions = { if (!editing) TextButton({ editing = true }) { Text("添加") } }) }) { inset ->
        Column(Modifier.padding(inset).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!editing) {
                if (state.models.isEmpty()) Text("尚未添加提供商，填写 URL 和 Key 即可获取模型列表。")
                state.models.groupBy { it.id.providerId }.forEach { (provider, models) ->
                    Text(provider, style = MaterialTheme.typography.titleMedium)
                    Text("${models.size} 个模型", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider()
                }
            } else {
                Text("密钥只发给下方填写的 HTTPS 端点，加密保存在本地凭据库。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("名称（可选）") }, enabled = !saving, modifier = Modifier.fillMaxWidth())
                ModelProtocol.entries.forEach { value ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !saving) { protocol = value }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(protocol == value, { protocol = value }, enabled = !saving)
                        Text(value.name, style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(url, { url = it }, label = { Text("API URL") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = !saving, modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = {
                    saving = true; error = null
                    add(name.trim(), url.trim(), protocol, key.trim()) { failure ->
                        saving = false
                        if (failure == null) { key = ""; url = ""; name = ""; editing = false } else error = failure
                    }
                }, enabled = !saving && url.isNotBlank() && key.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("连接并拉取模型")
                }
            }
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃修改？") },
        confirmButton = { TextButton({ confirmDiscard = false; key = ""; url = ""; name = ""; editing = false }) { Text("放弃") } },
        dismissButton = { TextButton({ confirmDiscard = false }) { Text("继续编辑") } })
}

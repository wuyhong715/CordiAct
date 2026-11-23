package com.cordiact.app.experience.settings

import com.cordiact.app.experience.settings.components.SettingsCard
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.interaction.InputSyntax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun InputVariablesPage() {
    val store = (LocalContext.current.applicationContext as CordiActApplication).fillValues
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var open by rememberSaveable { mutableStateOf(false) }
    var original by rememberSaveable { mutableStateOf<String?>(null) }
    var key by rememberSaveable { mutableStateOf("") }
    var value by rememberSaveable { mutableStateOf("") }
    var deletion by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(revision) {
        runCatching { withContext(Dispatchers.IO) { store.keys().sorted().map { it to store.get(it).orEmpty() } } }
            .onSuccess { entries = it }.onFailure { error = it.message ?: "加载失败" }
    }
    BackHandler(open || deletion != null) { if (!busy) { open = false; deletion = null } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("变量原文会随提示词发送给模型。这不是凭证库，请勿保存密码或密钥。", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy, onClick = { original = null; key = ""; value = ""; error = null; open = true }) { Text("新增变量") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        entries.forEach { (name, text) ->
            SettingsCard() {
                Column(Modifier.clickable(enabled = !busy) { original = name; key = name; value = text; error = null; open = true }.padding(14.dp)) {
                    Text("#$name", style = MaterialTheme.typography.titleSmall)
                    Text(text, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { deletion = name }) { Text("删除") }
                }
            }
        }
        if (entries.isEmpty()) Text("尚未设置变量。输入 # 可补全；发送时按原文展开。")
    }
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false }, title = { Text(if (original == null) "新增变量" else "编辑变量") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = key, onValueChange = { key = it }, enabled = !busy && original == null,
                label = { Text("名称（不含 #）") }, singleLine = true)
            OutlinedTextField(value = value, onValueChange = { value = it }, enabled = !busy,
                label = { Text("原文（支持多行）") }, minLines = 4, maxLines = 10)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(enabled = !busy, onClick = {
            if (!InputSyntax.validFillKey(key)) { error = "名称须为 1–64 个字母、数字、下划线或连字符"; return@TextButton }
            busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) {
                    require(original != null || store.get(key) == null) { "同名变量已存在" }
                    store.set(key, value)
                } }.onSuccess { open = false; error = null; revision++ }.onFailure { error = it.message ?: "保存失败" }
                busy = false
            }
        }) { Text(if (busy) "保存中" else "保存") } }, dismissButton = { TextButton(enabled = !busy, onClick = { open = false }) { Text("取消") } })
    deletion?.let { name -> AlertDialog(onDismissRequest = { if (!busy) deletion = null }, title = { Text("删除 #$name？") },
        text = { Text("已发送的历史内容不受影响。删除后可重新创建变量。") },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { store.remove(name) } }
                    .onSuccess { deletion = null; revision++; error = null }.onFailure { error = it.message ?: "删除失败" }
                busy = false
            }
        }) { Text("删除") } }, dismissButton = { TextButton(enabled = !busy, onClick = { deletion = null }) { Text("取消") } }) }
}

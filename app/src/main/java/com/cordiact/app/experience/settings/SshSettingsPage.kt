package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.settings.components.SecretField
import com.cordiact.app.experience.settings.components.DangerConfirmDialog
import com.cordiact.core.execution.SshHostSummary
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

@Composable
internal fun SshSettingsPage(facade: SshSettings?, dirty: (Boolean) -> Unit, onBusy: (Boolean) -> Unit, onDiscard: ((suspend () -> Unit)?) -> Unit) {
    if (facade == null) { Text("SSH 设置不可用"); return }
    var hosts by remember { mutableStateOf(emptyList<SshHostSummary>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var originalName by remember { mutableStateOf<String?>(null) }
    var importedKey by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }; var user by remember { mutableStateOf("") }
    var useKey by remember { mutableStateOf(false) }
    // Secrets never enter saved state or metadata snapshots.
    var secret by remember { mutableStateOf("") }; var passphrase by remember { mutableStateOf("") }
    var candidate by remember { mutableStateOf<String?>(null) }; var accepted by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<SshHostSummary?>(null) }
    var pin by remember { mutableStateOf<Pair<SshHostSummary, String>?>(null) }
    val scope = rememberCoroutineScope()
    val latestImported = rememberUpdatedState(importedKey)
    DisposableEffect(facade) {
        onDiscard { latestImported.value?.let { facade.clearImportedKey(it) } }
        onDispose { onDiscard(null) }
    }
    SideEffect { onBusy(busy) }
    DisposableEffect(Unit) { onDispose { onBusy(false) } }
    fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; message = null
        scope.launch {
            try { action(); hosts = facade.list() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "SSH 操作未确认成功：请刷新后再重试。检查档案、凭据、指纹及连通性；损坏数据不会被当成空列表覆盖。" }
            finally { busy = false }
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work { importedKey?.let { facade.clearImportedKey(it) }; importedKey = facade.importPrivateKey(uri); secret = ""; useKey = true; dirty(true) }
    }
    LaunchedEffect(Unit) { work {} }
    Text("全局 · 主机档案不含秘密；密码、私钥和口令只存凭据库。", style = MaterialTheme.typography.bodySmall)
    Text("首次读取会迁移旧 SSH 档案；校验凭据成功后删除旧明文文件。未固定指纹的旧主机不能连接。", style = MaterialTheme.typography.bodySmall)
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    message?.let { Text(it) }
    if (!adding) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !busy, onClick = { adding = true; originalName = null; name = ""; host = ""; port = "22"; user = ""; secret = ""; passphrase = ""; importedKey = null; candidate = null; accepted = false }) { Text("添加主机") }
            OutlinedButton(enabled = !busy, onClick = { work {} }) { Text("刷新") }
        }
        hosts.forEach { summary ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(summary.name, style = MaterialTheme.typography.titleMedium)
                    Text("${summary.user}@${summary.host}:${summary.port} · ${summary.authType}")
                    Text(summary.pinnedFingerprint ?: "未固定指纹 · 禁止连接")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = { work { message = facade.test(summary.name) } }) { Text("测试连接") }
                        TextButton(enabled = !busy, onClick = { work {
                            val result = facade.probe(summary.host, summary.port)
                            check(result.reachable && !result.fingerprintSha256.isNullOrBlank())
                            pin = summary to result.fingerprintSha256!!
                        } }) { Text("探测指纹") }
                        TextButton(enabled = !busy, onClick = { originalName = summary.name; name = summary.name; host = summary.host; port = summary.port.toString(); user = summary.user; secret = ""; passphrase = ""; importedKey = null; candidate = null; accepted = false; adding = true }) { Text("编辑") }
                        TextButton(enabled = !busy, onClick = { delete = summary }) { Text("删除") }
                    }
                }
            }
        }
    } else {
        Text(if (originalName == null) "新增 SSH 主机" else "编辑 SSH 主机（凭据不回显；留空保留原认证）", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(name, { name = it; dirty(true) }, label = { Text("名称（不可重名）") }, enabled = !busy && originalName == null, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(host, { host = it; candidate = null; accepted = false; dirty(true) }, label = { Text("主机") }, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it; candidate = null; accepted = false; dirty(true) }, label = { Text("端口") }, enabled = !busy)
        OutlinedTextField(user, { user = it; dirty(true) }, label = { Text("用户") }, enabled = !busy)
        Row { Checkbox(useKey, enabled = !busy, onCheckedChange = { useKey = it; secret = ""; dirty(true) }); Text("使用私钥（否则为密码）") }
        Text(if (useKey) "私钥（粘贴 PEM/OpenSSH 内容）" else "密码")
        SecretField(secret, { secret = it; dirty(true) }, !busy)
        if (useKey) {
            TextButton(enabled = !busy, onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text(if (importedKey != null) "私钥已导入凭据库 · 重新选择" else "从文件导入私钥") }
            Text("私钥口令（可选）"); SecretField(passphrase, { passphrase = it; dirty(true) }, !busy, "私钥口令（可选）") }
        OutlinedButton(enabled = !busy && host.isNotBlank() && port.toIntOrNull() in 1..65535, onClick = { work {
            val result = facade.probe(host.trim(), port.toInt())
            check(result.reachable && !result.fingerprintSha256.isNullOrBlank())
            candidate = result.fingerprintSha256; accepted = false
        } }) { Text("探测主机指纹") }
        candidate?.let { fingerprint ->
            Text("探测指纹：$fingerprint")
            Text("请通过主机控制台等可信途径核对；网络探测本身不能证明主机身份。", style = MaterialTheme.typography.bodySmall)
            Row { Checkbox(accepted, enabled = !busy, onCheckedChange = { accepted = it }); Text("已核对并确认固定此指纹") }
        }
        Button(enabled = !busy && accepted && candidate != null && name.isNotBlank() && user.isNotBlank() && (secret.isNotBlank() || importedKey != null || originalName != null), onClick = { work {
            val draft = SshHostDraft(name.trim(), host.trim(), port.toInt(), user.trim(), if (!useKey) secret else null, if (useKey) secret else null, passphrase.takeIf { useKey && it.isNotBlank() }, importedKey.takeIf { useKey })
            if (originalName == null) facade.save(draft, candidate!!) else facade.update(originalName!!, draft, candidate!!)
            importedKey?.takeIf { !useKey }?.let { facade.clearImportedKey(it) }
            secret = ""; passphrase = ""; importedKey = null; originalName = null; candidate = null; accepted = false; adding = false; dirty(false); message = "主机已保存"
        } }) { Text("保存") }
        TextButton(enabled = !busy, onClick = { confirmCancel = true }) { Text("取消修改") }
    }
    if (confirmCancel) AlertDialog(onDismissRequest = { confirmCancel = false }, title = { Text("放弃 SSH 修改？") },
        text = { Text("未保存输入及临时导入私钥将清理，已保存主机不变。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { work {
            importedKey?.let { facade.clearImportedKey(it) }
            importedKey = null; secret = ""; passphrase = ""; adding = false; originalName = null
            candidate = null; accepted = false; confirmCancel = false; dirty(false)
        } }) { Text("放弃") } }, dismissButton = { TextButton({ confirmCancel = false }) { Text("继续编辑") } })
    delete?.let { summary -> DangerConfirmDialog("删除 SSH 主机？", "将删除 ${summary.name} 的档案及凭据，无法撤销。", busy, { delete = null }, onConfirm = {
        work { facade.delete(summary.name); delete = null; message = "已删除主机及凭据" }
    }) }
    pin?.let { (summary, fingerprint) -> DangerConfirmDialog("确认主机指纹", "${summary.name}\n原指纹：${summary.pinnedFingerprint ?: "未固定"}\n新指纹：$fingerprint\n请独立核对后再更新；不匹配可能表示中间人攻击。", busy, { pin = null }, onConfirm = {
        work { facade.confirmFingerprint(summary.name, fingerprint); pin = null; message = "已固定新指纹" }
    }) }
}

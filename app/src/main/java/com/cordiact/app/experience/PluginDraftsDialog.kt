package com.cordiact.app.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.plugin.AuthoredPluginDefinition
import com.cordiact.core.plugin.AuthoredPluginDraftPage
import com.cordiact.core.plugin.AuthoredPluginException
import com.cordiact.core.plugin.AuthoredPluginInstallationException
import com.cordiact.core.plugin.AuthoredPluginInstallationStoreException
import com.cordiact.core.plugin.AuthoredPluginInstalledRecord
import com.cordiact.core.plugin.AuthoredPluginReceipt
import com.cordiact.core.plugin.AuthoredPluginSource
import com.cordiact.core.plugin.AuthoredPluginTurn
import com.cordiact.core.plugin.AuthoredTextOperation
import com.cordiact.core.security.AccessEnvelope
import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.interaction.InteractionContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

@Composable
internal fun PluginDraftsRoute(app: CordiActApplication, owner: InteractionContext, onDismiss: () -> Unit) = key(owner) {
    var cursor by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    val requestedCursor = cursor
    var page by remember(requestedCursor, revision) { mutableStateOf<AuthoredPluginDraftPage?>(null) }
    var pageError by remember(requestedCursor, revision) { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(requestedCursor, revision) {
        try {
            page = withContext(Dispatchers.IO) { app.listSessionPluginDrafts(owner, requestedCursor) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            pageError = draftFailureMessage(failure, preview = false)
        }
    }
    val pluginId = selected
    if (pluginId == null) {
        PluginDraftsDialog(page = page, error = pageError, isFirstPage = cursor == null,
            onPreview = { selected = it }, onNext = { cursor = it },
            onFirstPage = { cursor = null; revision++ }, onDismiss = onDismiss)
    } else {
        PluginVersionPreviewRoute(app, owner, pluginId, onBack = { selected = null })
    }
}

private data class VersionPreviewSnapshot(
    val definition: AuthoredPluginDefinition,
    val active: AuthoredPluginReceipt?,
    val profile: EffectiveSecurityProfile,
    val confirmation: AuthoredPluginTurn?,
    val replacement: AuthoredPluginDefinition?,
    val replacementNote: String?,
)

@Composable
internal fun PluginVersionPreviewRoute(
    app: CordiActApplication, owner: InteractionContext, pluginId: String,
    saved: AuthoredPluginInstalledRecord? = null,
    onSavedVersionChanged: (() -> Unit)? = null,
    onBack: () -> Unit,
) = key(owner, pluginId, saved?.installationId) {
    val scope = rememberCoroutineScope()
    var snapshot by remember(pluginId) { mutableStateOf<VersionPreviewSnapshot?>(null) }
    var error by remember(pluginId) { mutableStateOf<String?>(null) }
    var actionError by remember(pluginId) { mutableStateOf<String?>(null) }
    var mustReconcile by remember(pluginId) { mutableStateOf(false) }
    var attempt by remember(pluginId) { mutableStateOf<AuthoredPluginTurn?>(null) }
    var enabledHere by remember(pluginId) { mutableStateOf<AuthoredPluginReceipt?>(null) }
    var savedHere by remember(pluginId) { mutableStateOf(false) }
    var previewRevision by remember(pluginId) { mutableStateOf(0) }
    DisposableEffect(owner, pluginId) {
        onDispose {
            snapshot?.confirmation?.close()
            attempt?.close()
        }
    }
    LaunchedEffect(owner, pluginId, previewRevision) {
        // Capture the foreground generation when preview begins, not when the user clicks later.
        val confirmation = app.authoredPluginForeground.openConfirmation(owner.workspaceId, owner.sessionId)
        try {
            snapshot = withContext(Dispatchers.IO) {
                val definition = if (saved == null) app.previewSessionPluginDraft(owner, pluginId) else {
                    require(saved.workspaceId == owner.workspaceId && saved.id == pluginId)
                    app.previewSavedPluginVersion(owner, saved)
                }
                val replacement = if (saved != null) {
                    try {
                        app.previewSessionPluginDraft(owner, pluginId) to null
                    } catch (cancel: CancellationException) {
                        throw cancel
                    } catch (failure: Exception) {
                        val note = if (failure is WorkspaceFileException && failure.code == WorkspaceFileException.Code.FILE_NOT_FOUND)
                            "没有同名草稿可用于替换。" else "同名草稿无法校验，不能用于替换。"
                        null to note
                    }
                } else null to null
                VersionPreviewSnapshot(definition, app.listSessionPlugins(owner).firstOrNull { it.id == pluginId },
                    app.sessionPluginProfile(owner), confirmation, replacement.first, replacement.second)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = if (saved == null) draftFailureMessage(failure, preview = true) else savedVersionFailureMessage(failure)
        } finally {
            if (snapshot?.confirmation !== confirmation) confirmation?.close()
        }
    }
    val shown = snapshot
    fun submit(action: DraftAction) {
        val approved = shown ?: return
        if (attempt != null || snapshot !== approved || savedHere || enabledHere != null) return
        val confirmation = approved.confirmation
        if (confirmation == null || !confirmation.isOpen) {
            actionError = "此预览的前台确认已失效，请重新预览。"
            return
        }
        attempt = confirmation
        actionError = null
        scope.launch {
            try {
                when (action) {
                    DraftAction.ENABLE -> enabledHere = runInterruptible(Dispatchers.IO) {
                        val definition = approved.definition
                        if (saved == null) app.activateSessionPlugin(owner, confirmation, approved.profile, definition.id, definition.sha256)
                        else app.activateSavedPluginVersion(owner, confirmation, approved.profile, saved)
                    }
                    DraftAction.SAVE -> {
                        check(saved == null) { "Cannot save a saved version again from this preview" }
                        runInterruptible(Dispatchers.IO) {
                            app.saveSessionPluginVersion(owner, confirmation, approved.profile, approved.definition.id, approved.definition.sha256)
                        }
                        savedHere = true
                    }
                    DraftAction.REMOVE -> {
                        val expected = requireNotNull(saved)
                        runInterruptible(Dispatchers.IO) { app.removeSavedPluginVersion(owner, confirmation, approved.profile, expected) }
                        onSavedVersionChanged?.invoke()
                    }
                    DraftAction.REPLACE -> {
                        val expected = requireNotNull(saved)
                        val draft = requireNotNull(approved.replacement)
                        runInterruptible(Dispatchers.IO) {
                            app.replaceSavedPluginVersion(owner, confirmation, approved.profile, expected, draft.sha256)
                        }
                        onSavedVersionChanged?.invoke()
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                mustReconcile = (failure as? AuthoredPluginInstallationStoreException)?.code ==
                    AuthoredPluginInstallationStoreException.Code.COMMIT_UNKNOWN
                actionError = when (action) {
                    DraftAction.ENABLE -> if (saved == null) activationFailureMessage(failure) else savedVersionFailureMessage(failure)
                    DraftAction.SAVE -> saveFailureMessage(failure)
                    DraftAction.REMOVE, DraftAction.REPLACE -> savedMutationFailureMessage(failure)
                }
            } finally {
                confirmation.close()
                if (attempt === confirmation) attempt = null
            }
        }
    }
    val actionsAvailable = shown != null && shown.profile.accessEnvelope != AccessEnvelope.READ_ONLY &&
        enabledHere == null && !savedHere
    PluginDraftPreviewDialog(pluginId, shown?.definition, enabledHere ?: shown?.active, error, onBack,
        busy = attempt != null,
        actionMessage = when {
            savedHere -> "此版本已保存到当前工作区；本次操作未启用插件。"
            enabledHere != null -> "已在本会话启用。工具从下一轮对话起可用。"
            else -> null
        },
        actionError = actionError,
        readOnly = shown?.profile?.accessEnvelope == AccessEnvelope.READ_ONLY,
        onRefresh = if (mustReconcile) null else ({
            snapshot?.confirmation?.close()
            snapshot = null
            error = null
            actionError = null
            enabledHere = null
            savedHere = false
            previewRevision++
        }),
        onEnable = if (actionsAvailable) ({ submit(DraftAction.ENABLE) }) else null,
        onSave = if (actionsAvailable && saved == null) ({ submit(DraftAction.SAVE) }) else null,
        fromSaved = saved != null,
        replacement = shown?.replacement,
        replacementNote = shown?.replacementNote,
        onRemoveSaved = if (actionsAvailable && saved != null && onSavedVersionChanged != null) ({ submit(DraftAction.REMOVE) }) else null,
        onReplaceSaved = if (actionsAvailable && saved != null && onSavedVersionChanged != null) ({ submit(DraftAction.REPLACE) }) else null,
    )
}

@Composable
internal fun PluginDraftsDialog(
    page: AuthoredPluginDraftPage?, error: String?, isFirstPage: Boolean,
    onPreview: (String) -> Unit, onNext: (String) -> Unit, onFirstPage: () -> Unit, onDismiss: () -> Unit,
) {
    val nextCursor = page?.nextCursor
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("工作区插件草稿") },
        text = {
            val scroll = rememberScrollState()
            LaunchedEffect(page) { scroll.scrollTo(0) }
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只查看当前工作区的草稿。预览不会发布工具，也不会将内容发送给模型。")
                when {
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    page == null -> CircularProgressIndicator()
                    page.entries.isEmpty() -> Text(when {
                        nextCursor != null -> "本页没有符合命名规则的草稿，可以查看下一页。"
                        isFirstPage -> "当前工作区没有符合命名规则的插件草稿。"
                        else -> "本页没有更多符合命名规则的草稿。"
                    })
                    else -> page.entries.forEach { entry ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(entry.id, style = MaterialTheme.typography.titleSmall)
                                Text("${entry.sizeBytes} 字节", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onPreview(entry.id) }, modifier = Modifier.testTag("plugin-draft-preview-${entry.id}")) { Text("预览") }
                            }
                        }
                    }
                }
                if (nextCursor != null) {
                    TextButton(onClick = { onNext(nextCursor) }, modifier = Modifier.testTag("plugin-draft-next")) { Text("下一页") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("返回会话插件") } },
        dismissButton = { TextButton(onClick = onFirstPage) { Text(if (isFirstPage) "刷新" else "返回首页") } },
    )
}

private enum class DraftAction { ENABLE, SAVE, REMOVE, REPLACE }

@Composable
internal fun PluginDraftPreviewDialog(
    pluginId: String, draft: AuthoredPluginDefinition?, active: AuthoredPluginReceipt?, error: String?, onBack: () -> Unit,
    busy: Boolean = false, actionMessage: String? = null, actionError: String? = null, readOnly: Boolean = false,
    onRefresh: (() -> Unit)? = null, onEnable: (() -> Unit)? = null, onSave: (() -> Unit)? = null,
    fromSaved: Boolean = false,
    replacement: AuthoredPluginDefinition? = null,
    replacementNote: String? = null,
    onRemoveSaved: (() -> Unit)? = null,
    onReplaceSaved: (() -> Unit)? = null,
) {
    var confirming by remember(draft) { mutableStateOf<DraftAction?>(null) }
    val canChange = draft != null && !busy && error == null && actionError == null && !readOnly
    val canEnable = canChange && active == null && onEnable != null
    val canSave = canChange && !fromSaved && onSave != null
    val canRemove = canChange && fromSaved && onRemoveSaved != null
    val canReplace = canChange && fromSaved && replacement != null && replacement.id == draft?.id &&
        replacement.sha256 != draft?.sha256 && onReplaceSaved != null
    val action = confirming
    val confirm = when (action) {
        DraftAction.ENABLE -> onEnable.takeIf { canEnable }
        DraftAction.SAVE -> onSave.takeIf { canSave }
        DraftAction.REMOVE -> onRemoveSaved.takeIf { canRemove }
        DraftAction.REPLACE -> onReplaceSaved.takeIf { canReplace }
        null -> null
    }
    if (draft != null && confirm != null && action != null) {
        val (label, suffix) = when (action) {
            DraftAction.ENABLE -> "确认启用" to "enable"
            DraftAction.SAVE -> "确认保存" to "save"
            DraftAction.REMOVE -> "确认删除" to "remove"
            DraftAction.REPLACE -> "确认替换" to "replace"
        }
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = {
                Text(when (action) {
                    DraftAction.ENABLE -> "在本会话启用 ${draft.id}？"
                    DraftAction.SAVE -> "保存 ${draft.id} 的批准版本？"
                    DraftAction.REMOVE -> "删除 ${draft.id} 的已保存版本？"
                    DraftAction.REPLACE -> "用同名草稿替换 ${draft.id} 的已保存版本？"
                })
            },
            text = {
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (action) {
                        DraftAction.SAVE -> Text("保存刚才预览的版本到当前工作区。重启后仍可查看；本次操作不启用插件，也不会覆盖已保存的其他版本。")
                        DraftAction.ENABLE -> {
                            Text(if (fromSaved) "仅启用下列摘要对应的已保存版本，不读取草稿。" else "仅启用刚才预览的版本。")
                            Text("工具无文件、网络、命令或编排权限；后续调用的结果可能发给所选模型。重启不会自动启用。")
                        }
                        DraftAction.REMOVE -> Text("只删除此工作区的这份保存快照，不撤回正在运行的插件，也不删除草稿或历史对话。删除后无法从清单恢复此快照。")
                        DraftAction.REPLACE -> Text("以刚才预览的同名草稿替换保存快照，不保留旧快照。正在运行的插件保持不变；不会自动启用新版。")
                    }
                    Text(if (action == DraftAction.REPLACE) "旧版 SHA-256：${draft.sha256}" else "SHA-256：${draft.sha256}", fontFamily = FontFamily.Monospace)
                    if (action == DraftAction.REPLACE && replacement != null) {
                        Text("新版 SHA-256：${replacement.sha256}", fontFamily = FontFamily.Monospace)
                        Text("新版工具：${replacement.tools.joinToString { it.name }}")
                    } else {
                        Text("工具：${draft.tools.joinToString { it.name }}")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { confirming = null; confirm() },
                    modifier = Modifier.testTag("plugin-draft-confirm-$suffix")) {
                    Text(label)
                }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text("取消") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onBack() },
        title = { Text(if (fromSaved) "已保存版本：$pluginId" else "草稿预览：$pluginId") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    error != null -> Text(error, color = MaterialTheme.colorScheme.error)
                    draft == null -> CircularProgressIndicator()
                    else -> {
                        if (busy) CircularProgressIndicator()
                        if (actionMessage != null) Text(actionMessage, color = MaterialTheme.colorScheme.primary)
                        if (actionError != null) Text(actionError, color = MaterialTheme.colorScheme.error)
                        if (readOnly) Text("当前工作区权限为只读，只能查看或撤回会话试用，不能修改保存版本或启用插件。")
                        Text(draft.title, style = MaterialTheme.typography.titleMedium)
                        Text(if (fromSaved) "格式版本 ${draft.schemaVersion} · 来源：工作区已保存版本"
                            else "格式版本 ${draft.schemaVersion} · ${AuthoredPluginSource.path(draft.id)}")
                        Text(if (fromSaved) "已保存版本 SHA-256" else "草稿 SHA-256", style = MaterialTheme.typography.labelMedium)
                        SelectionContainer { Text(draft.sha256, fontFamily = FontFamily.Monospace) }
                        when {
                            active == null -> Text("预览时，本会话没有启用这个插件。")
                            active.sha256 == draft.sha256 -> Text(if (fromSaved) "此保存版本与本会话启用的版本一致。"
                                else "预览时，这份草稿与本会话启用的版本一致。")
                            else -> {
                                Text(if (fromSaved) "本会话启用了另一个版本。" else "草稿与本会话启用的版本不同；编辑草稿不会更新启用版本。")
                                Text("启用版本 SHA-256", style = MaterialTheme.typography.labelMedium)
                                SelectionContainer { Text(active.sha256, fontFamily = FontFamily.Monospace) }
                            }
                        }
                        if (active != null && active.sha256 != draft.sha256) Text("要启用新版，请先从会话插件清单撤回旧版。")
                        VersionToolPreviews(draft)
                        if (canEnable) {
                            TextButton(onClick = { confirming = DraftAction.ENABLE }, modifier = Modifier.testTag("plugin-draft-enable")) {
                                Text("在本会话启用")
                            }
                        }
                        if (canSave) {
                            TextButton(onClick = { confirming = DraftAction.SAVE }, modifier = Modifier.testTag("plugin-draft-save")) {
                                Text("保存批准版本")
                            }
                        }
                        if (canRemove) {
                            TextButton(onClick = { confirming = DraftAction.REMOVE }, modifier = Modifier.testTag("plugin-saved-remove")) {
                                Text("删除保存版本")
                            }
                        }
                        if (fromSaved && !readOnly) {
                            val candidate = replacement
                            when {
                                replacementNote != null -> Text(replacementNote)
                                candidate != null && candidate.sha256 == draft.sha256 -> Text("同名草稿与保存快照相同，无需替换。")
                                candidate != null && candidate.id == draft.id -> {
                                    Text("用于替换的同名草稿", style = MaterialTheme.typography.titleMedium)
                                    Text(candidate.title)
                                    Text("草稿格式 ${candidate.schemaVersion} · 新版 SHA-256")
                                    SelectionContainer { Text(candidate.sha256, fontFamily = FontFamily.Monospace) }
                                    VersionToolPreviews(candidate)
                                    if (canReplace) {
                                        TextButton(onClick = { confirming = DraftAction.REPLACE }, modifier = Modifier.testTag("plugin-saved-replace")) {
                                            Text("替换保存版本")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onBack) { Text(if (fromSaved) "返回已保存版本" else "返回草稿清单") } },
        dismissButton = {
            if (onRefresh != null) TextButton(enabled = !busy, onClick = onRefresh) { Text("重新预览") }
        },
    )
}

@Composable
private fun VersionToolPreviews(definition: AuthoredPluginDefinition) {
    definition.tools.forEach { tool ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(tool.name, style = MaterialTheme.typography.titleSmall)
                Text(tool.description)
                val operation = tool.operation
                if (operation == null) {
                    Text("固定文本 · 无输入", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(tool.text) }
                } else {
                    val label = when (operation) {
                        AuthoredTextOperation.TRIM -> "去掉首尾空白"
                        AuthoredTextOperation.UPPERCASE -> "转换为大写（Locale.ROOT）"
                        AuthoredTextOperation.LOWERCASE -> "转换为小写（Locale.ROOT）"
                    }
                    Text("$label · ${operation.wireName}")
                    Text("输入和输出各限 4096 UTF-8 字节，超限拒绝。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun activationFailureMessage(error: Exception): String = when (error) {
    is AuthoredPluginException -> when (error.code) {
        AuthoredPluginException.Code.VERSION_CHANGED -> "草稿已变化，未启用。请重新预览后确认。"
        AuthoredPluginException.Code.ALREADY_ACTIVE -> "已有同名插件在本会话启用；请先查看会话清单。"
        AuthoredPluginException.Code.BUSY -> "本会话已有插件变更正在处理。请等待后重新预览。"
        AuthoredPluginException.Code.QUOTA -> "已达到试用插件数量上限，请先撤回不需要的版本。"
        AuthoredPluginException.Code.CALLER_UNAVAILABLE, AuthoredPluginException.Code.DENIED ->
            "前台确认已失效或工作区权限已变化，未启用。请重新预览。"
        else -> "未确认启用成功，请查看本会话插件清单。"
    }
    is WorkspaceFileException -> draftFailureMessage(error, preview = true)
    else -> "未确认启用成功，请重新预览。"
}

private fun savedVersionFailureMessage(error: Exception): String = when (error) {
    is AuthoredPluginInstallationException -> when (error.code) {
        AuthoredPluginInstallationException.Code.NOT_FOUND, AuthoredPluginInstallationException.Code.VERSION_CHANGED ->
            "已保存版本不存在或已变化。请返回清单刷新；不会改用草稿。"
        AuthoredPluginInstallationException.Code.CORRUPT -> "已保存版本校验失败，不能启用。"
        AuthoredPluginInstallationException.Code.UNSUPPORTED -> "当前应用不支持这个已保存版本，不能启用。"
        else -> "无法使用此已保存版本；请返回清单核对。"
    }
    is AuthoredPluginException -> activationFailureMessage(error)
    else -> "无法读取或确认已保存版本；请返回清单核对。"
}

private fun savedMutationFailureMessage(error: Exception): String = when (error) {
    is AuthoredPluginInstallationStoreException -> if (error.code == AuthoredPluginInstallationStoreException.Code.COMMIT_UNKNOWN)
        "保存版本的变更结果未知。请返回清单核对，不要直接重试。" else "无法修改保存版本，请返回清单核对。"
    is AuthoredPluginInstallationException -> when (error.code) {
        AuthoredPluginInstallationException.Code.NOT_FOUND, AuthoredPluginInstallationException.Code.VERSION_CHANGED ->
            "保存版本或同名草稿已变化，未修改。请返回清单刷新后重新确认。"
        AuthoredPluginInstallationException.Code.CALLER_UNAVAILABLE, AuthoredPluginInstallationException.Code.DENIED ->
            "前台确认已失效或工作区权限已变化，未修改。"
        else -> "不能修改此保存版本，请返回清单核对。"
    }
    is WorkspaceFileException -> "同名草稿已不可用，未替换保存版本。"
    else -> "未确认变更结果。请返回清单核对，不要直接重试。"
}

private fun saveFailureMessage(error: Exception): String = when (error) {
    is AuthoredPluginInstallationException -> when (error.code) {
        AuthoredPluginInstallationException.Code.CALLER_UNAVAILABLE, AuthoredPluginInstallationException.Code.DENIED ->
            "前台确认已失效或权限已变化，未保存。请重新预览。"
        AuthoredPluginInstallationException.Code.VERSION_CHANGED -> "草稿已变化，未保存。请重新预览后确认。"
        AuthoredPluginInstallationException.Code.CONFLICT -> "该插件已保存了另一个版本，未覆盖。请查看已保存版本清单。"
        AuthoredPluginInstallationException.Code.CORRUPT, AuthoredPluginInstallationException.Code.UNSUPPORTED ->
            "同名记录无法校验，未覆盖。请查看已保存版本清单。"
        AuthoredPluginInstallationException.Code.QUOTA -> "当前工作区已达到 32 个已保存版本的上限。"
        AuthoredPluginInstallationException.Code.INVALID_SOURCE -> "草稿格式无效，未保存。请重新预览。"
        AuthoredPluginInstallationException.Code.NOT_FOUND -> "无法找到对应版本，请返回清单核对。"
    }
    is AuthoredPluginInstallationStoreException -> when (error.code) {
        AuthoredPluginInstallationStoreException.Code.COMMIT_UNKNOWN -> "未确认保存结果。请先查看已保存版本清单，不要直接重试。"
        else -> "无法保存批准版本。请查看已保存版本清单。"
    }
    is WorkspaceFileException -> draftFailureMessage(error, preview = true)
    else -> "未确认保存结果。请先查看已保存版本清单。"
}

private fun draftFailureMessage(error: Exception, preview: Boolean): String = when {
    error is WorkspaceFileException -> when (error.code) {
        WorkspaceFileException.Code.FILE_NOT_FOUND -> "草稿已不存在，请返回并刷新。"
        WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED -> "草稿超过 16 KiB，无法预览。"
        WorkspaceFileException.Code.CURSOR_INVALID -> "清单分页已失效，请返回首页刷新。"
        WorkspaceFileException.Code.NOT_A_REGULAR_FILE, WorkspaceFileException.Code.SYMLINK_NOT_ALLOWED ->
            if (preview) "草稿不是允许读取的普通文件。" else "插件目录不可用。"
        else -> "无法读取工作区插件草稿，请重试。"
    }
    preview && error is IllegalArgumentException -> "无法预览：草稿不是受支持的插件格式，或会话已不可用。"
    else -> "无法读取草稿，请确认会话仍然可用后重试。"
}

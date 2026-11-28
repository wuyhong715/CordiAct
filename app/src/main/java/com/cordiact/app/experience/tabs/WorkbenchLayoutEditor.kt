package com.cordiact.app.experience.tabs

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.GlyphName
import com.cordiact.core.workbench.PanelPlacement
import com.cordiact.core.workbench.WorkbenchAccent
import com.cordiact.core.workbench.WorkbenchPanel
import com.cordiact.core.workbench.WorkbenchProfile
import com.cordiact.core.workbench.WorkbenchTheme
import com.cordiact.core.plugin.ExtensionPackageRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun WorkbenchLayoutEditor(
    workspaceName: String,
    library: WorkbenchProfiles,
    publications: List<PublishedTab>,
    onSave: suspend (WorkbenchProfiles) -> Unit,
    loadPackages: suspend () -> List<ExtensionPackageRecord>,
    enablePackages: suspend (List<ExtensionPackageRecord>) -> Boolean,
    onBack: () -> Unit,
) {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val titles = publications.associate { it.id to it.content.title }
    var selectedId by rememberSaveable { mutableStateOf<String?>(library.selectedId) }
    var draft by rememberSaveable(stateSaver = profileSaver) { mutableStateOf(library.selected) }
    var name by rememberSaveable { mutableStateOf(draft.name) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    var importedProfile by rememberSaveable { mutableStateOf<String?>(null) }
    val requirements = remember(importedProfile) { importedProfile?.let(WorkbenchProfileFile::decodePortable)?.packages.orEmpty() }
    var installed by remember { mutableStateOf<List<ExtensionPackageRecord>>(emptyList()) }
    val panels = draft.editorPanels(titles.keys.toList())

    fun changeDraft(id: String?, profile: WorkbenchProfile, preservePackages: Boolean = false) {
        selectedId = id
        draft = profile
        name = profile.name
        status = null
        if (!preservePackages) {
            importedProfile = null
            installed = emptyList()
        }
    }

    fun runEdit(failure: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                status = failure
            } finally {
                busy = false
            }
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runEdit("无法读取布局文件，请检查格式和版本。") {
            val imported = withContext(Dispatchers.IO) {
                checkNotNull(resolver.openInputStream(uri)).use(WorkbenchProfileFile::readPortable)
            }
            val current = if (imported.packages.isNullOrEmpty()) emptyList() else loadPackages()
            changeDraft(null, imported.layout)
            importedProfile = imported.packages?.let { WorkbenchProfileFile.encodePortable(imported) }
            installed = current
            status = "已载入方案预览。"
        }
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val exported = pendingExport
        pendingExport = null
        if (uri == null || exported == null) return@rememberLauncherForActivityResult
        runEdit("未能确认布局文件已写入。") {
            withContext(Dispatchers.IO) {
                checkNotNull(resolver.openOutputStream(uri, "wt")).use {
                    it.write(exported.toByteArray(Charsets.UTF_8))
                }
            }
            status = "工作台方案已导出。"
        }
    }

    fun save(id: String? = selectedId) {
        runEdit("无法保存布局，请检查存储后重试。") {
            val next = library.apply(id, draft.copy(name = name.trim()))
            onSave(next)
            changeDraft(next.selectedId, next.selected, preservePackages = true)
            status = "已应用布局。"
        }
    }

    val validName = WorkbenchProfile.isValidName(name.trim())
    val existing = selectedId?.let(library::get)
    val createsCopy = selectedId?.let(library.custom::containsKey) != true &&
        (existing != draft || draft.name != name.trim())
    val hasRoom = library.custom.size < WorkbenchProfiles.MAX_CUSTOM
    BackHandler { if (!busy) onBack() }
    Surface(Modifier.fillMaxSize().testTag("workbench-layout")) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime)),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, enabled = !busy) { AppGlyph(GlyphName.Back, "返回工作台") }
                    Column {
                        Text("工作台布局", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                        Text(workspaceName, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                val choices = (WorkbenchProfiles.presets + library.custom).map { (id, profile) ->
                    id to if (id == library.selectedId) "${profile.name} · 当前" else profile.name
                }
                LayoutChoice("布局方案", selectedId, choices, !busy) { id -> changeDraft(id, checkNotNull(library.get(id))) }
            }
            item {
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= WorkbenchProfile.MAX_NAME_LENGTH) name = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("方案名称") }, singleLine = true,
                    enabled = !busy, isError = !validName,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LayoutChoice("主题", draft.theme, WorkbenchTheme.entries.map {
                        it to when (it) {
                            WorkbenchTheme.LIGHT -> "浅色"
                            WorkbenchTheme.DARK -> "深色"
                            WorkbenchTheme.SYSTEM -> "跟随系统"
                        }
                    }, !busy) { draft = draft.copy(theme = it) }
                    LayoutChoice("强调色", draft.accent, WorkbenchAccent.entries.map {
                        it to when (it) {
                            WorkbenchAccent.AMBER -> "琥珀"
                            WorkbenchAccent.INDIGO -> "靛蓝"
                            WorkbenchAccent.TEAL -> "青绿"
                        }
                    }, !busy) { draft = draft.copy(accent = it) }
                }
            }
            item {
                Text("面板", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Text("侧栏面板在窄屏上以标签页显示。", style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(panels, key = { _, panel -> panel.id }) { index, panel ->
                val title = titles[panel.id] ?: panel.id
                val editable = !busy && (panels.size <= WorkbenchProfile.MAX_PANELS || panel in draft.panels)
                Column(Modifier.fillMaxWidth().testTag("workbench-panel-${panel.id}")) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    if (panel.id !in titles) Text("插件当前不可用", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LayoutChoice("位置", panel.placement, PanelPlacement.entries.map { it to it.label }, editable) {
                            val editablePanels = if (panels.size <= WorkbenchProfile.MAX_PANELS) panels else draft.panels
                            draft = draft.copy(panels = editablePanels.map { row ->
                                if (row.id == panel.id) row.copy(placement = it) else row
                            })
                        }
                        Spacer(Modifier.weight(1f))
                        if (panel.id !in titles) TextButton(enabled = editable, onClick = {
                            draft = draft.copy(panels = draft.panels.filterNot { it.id == panel.id })
                        }) { Text("移除") }
                        MovePanelButton(title, up = true, enabled = editable && index > 0 && panels.size <= WorkbenchProfile.MAX_PANELS) {
                            draft = draft.copy(panels = panels.move(index, index - 1))
                        }
                        MovePanelButton(title, up = false, enabled = editable && index < panels.lastIndex && panels.size <= WorkbenchProfile.MAX_PANELS) {
                            draft = draft.copy(panels = panels.move(index, index + 1))
                        }
                    }
                    HorizontalDivider()
                }
            }
            item {
                Text("导入与分享", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Row {
                    TextButton(onClick = { openDocument.launch(arrayOf("application/json", "text/plain")) }, enabled = !busy && hasRoom) { Text("导入 JSON") }
                    TextButton(onClick = {
                        runEdit("无法读取当前工作区的插件选择。") {
                            val packages = loadPackages().filter { it.enableIntent }
                                .map { WorkbenchPackageRequirement.from(it) }.sortedBy { it.id }
                            pendingExport = WorkbenchProfileFile.encodePortable(PortableWorkbenchProfile(draft.copy(name = name.trim()), packages))
                            createDocument.launch("cordiact-workbench.json")
                        }
                    }, enabled = !busy && validName) { Text("导出方案") }
                    TextButton(enabled = !busy, onClick = { changeDraft(WorkbenchProfiles.CLASSIC, WorkbenchProfiles.presets.getValue(WorkbenchProfiles.CLASSIC)) }) {
                        Text("载入默认布局")
                    }
                }
            }
            if (requirements.isNotEmpty()) {
                item {
                    Text("方案中的插件", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    val matched = requirements.map { requirement ->
                        val current = installed.firstOrNull { it.manifest.id == requirement.id }
                        Text("${requirement.id} ${requirement.version} · " + when {
                            current == null -> "未安装"
                            WorkbenchPackageRequirement.from(current) != requirement -> "版本、内容或来源不匹配"
                            current.enableIntent -> "已选择启用"
                            else -> "可启用"
                        })
                        current?.takeIf { WorkbenchPackageRequirement.from(it) == requirement }
                    }
                    val disabled = matched.filterNotNull().filterNot { it.enableIntent }
                    TextButton(enabled = !busy && matched.all { it != null } && disabled.isNotEmpty(), onClick = {
                        runEdit("未能完成插件启用，请刷新确认当前状态。") {
                            val finished = try {
                                enablePackages(matched.filterNotNull())
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            installed = loadPackages()
                            status = if (finished) "已提交方案中的插件启用选择。" else "启用中止，已刷新插件清单。"
                        }
                    }) { Text("启用匹配插件") }
                    TextButton(enabled = !busy, onClick = {
                        runEdit("无法刷新插件清单。") { installed = loadPackages() }
                    }) { Text("刷新插件清单") }
                }
            }
            item {
                val id = selectedId
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { save() }, enabled = !busy && validName && (!createsCopy || hasRoom), modifier = Modifier.testTag("apply-workbench-layout")) {
                        Text("保存并应用")
                    }
                    if (id != null && id in library.custom) {
                        OutlinedButton(onClick = { save(null) }, enabled = !busy && validName && hasRoom) { Text("另存为") }
                        TextButton(onClick = {
                            runEdit("无法删除方案。") {
                                val next = library.remove(id)
                                onSave(next)
                                changeDraft(next.selectedId, next.selected)
                            }
                        }, enabled = !busy) { Text("删除") }
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                if (!hasRoom) Text("已保存 ${WorkbenchProfiles.MAX_CUSTOM} 个自定义方案。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun <T> LayoutChoice(label: String, selected: T?, choices: List<Pair<T, String>>, enabled: Boolean, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val value = choices.firstOrNull { it.first == selected }?.second ?: "新方案"
    Box {
        OutlinedButton(onClick = { open = true }, enabled = enabled) { Text("$label · $value") }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            choices.forEach { (id, title) ->
                DropdownMenuItem(text = { Text(title) }, onClick = { open = false; onSelect(id) })
            }
        }
    }
}

@Composable
private fun MovePanelButton(title: String, up: Boolean, enabled: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled) {
        AppGlyph(GlyphName.ChevronDown, if (up) "上移$title" else "下移$title", modifier = Modifier.rotate(if (up) 180f else 0f))
    }
}

private val PanelPlacement.label: String
    get() = when (this) {
        PanelPlacement.TAB -> "标签页"
        PanelPlacement.DOCK -> "侧栏"
        PanelPlacement.HIDDEN -> "隐藏"
    }

private fun List<WorkbenchPanel>.move(from: Int, to: Int): List<WorkbenchPanel> = toMutableList().apply {
    add(to, removeAt(from))
}

private val profileSaver = Saver<WorkbenchProfile, String>(
    save = { WorkbenchProfileFile.encode(it) },
    restore = WorkbenchProfileFile::decode,
)

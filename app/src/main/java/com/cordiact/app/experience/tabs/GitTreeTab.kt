package com.cordiact.app.experience.tabs

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.*
import com.cordiact.core.workspace.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object GitTreeTab : TabContributor {
    override val id = "cordiact.git-tree"
    override val title = "Git 树"
    override val glyph = GlyphName.Branch
    @Composable override fun Content(state: ExperienceState, host: TabHost) {
        key(state.workspace?.id) { GitTreePage(state.workspace) }
    }
}

@Composable private fun GitTreePage(workspace: Workspace?) {
    val app = LocalContext.current.applicationContext as CordiActApplication
    val colors = MaterialTheme.colorScheme
    var refresh by remember { mutableIntStateOf(0) }
    var onlyHead by rememberSaveable { mutableStateOf(false) }
    var asList by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<WorkspaceGitCommit?>(null) }
    val history by produceState<Result<WorkspaceGitSnapshot?>?>(null, refresh) {
        value = null
        value = try { Result.success(withContext(Dispatchers.IO) {
            workspace?.let { WorkspaceGitHistory.read(app.workspaceManager.resolveRoot(it.id)) }
        }) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }
    }
    val data = history?.getOrNull()
    val active = data?.takeIf { it.commits.isNotEmpty() }
    BoxWithConstraints(Modifier.fillMaxSize().padding(LocalChromePadding.current).testTag("git-tree-page")) {
        val narrow = maxWidth < 470.dp
        val short = maxHeight < 250.dp
        Column(Modifier.fillMaxSize().padding(horizontal = if (narrow) 16.dp else 27.dp, vertical = if (short) 3.dp else 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Git 树", style = if (short) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        AppGlyph(GlyphName.Folder, size = GlyphSize.Small, tint = colors.onSurfaceVariant)
                        Text(workspace?.name ?: "默认工作区", style = MaterialTheme.typography.labelMedium, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false))
                        if (!narrow && data != null) Text("· 本地仓库", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
                if (!narrow && active != null) GitOptions(onlyHead, { onlyHead = it }, asList, { asList = it }, active.head != null)
                IconButton(onClick = { refresh++ }, modifier = Modifier.size(42.dp)) {
                    AppGlyph(GlyphName.Reset, size = GlyphSize.Small, description = "刷新提交历史")
                }
            }
            if (narrow && active != null) GitOptions(onlyHead, { onlyHead = it }, asList, { asList = it }, active.head != null)
            if (!short) Spacer(Modifier.height(7.dp))
            val content = Modifier.weight(1f).fillMaxWidth()
            when {
                active != null && !asList -> ScatterGraph(active, onlyHead, { selected = it }, content)
                active != null -> GitList(active, onlyHead, { selected = it }, content)
                else -> GitPanel(content) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        when {
                            history == null -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            history?.isFailure == true -> GitEmpty("无法读取提交历史", "检查仓库状态后，点击右上角刷新。")
                            data == null -> GitEmpty("当前工作区没有 Git 仓库", "Git 树只读取当前工作区根目录的 Git 历史。")
                            else -> GitEmpty("这个仓库还没有提交", "首次提交后，这里会显示分支历史。")
                        }
                    }
                }
            }
        }
        selected?.let { GitDetail(workspace?.id, it, data, narrow, { selected = it }, { selected = null }) }
    }
}

@Composable private fun GitOptions(head: Boolean, onHead: (Boolean) -> Unit, list: Boolean, onList: (Boolean) -> Unit, hasHead: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box {
            Surface(onClick = { menu = true }, enabled = hasHead, shape = RoundedCornerShape(100),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))) {
                Row(Modifier.height(35.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (head && hasHead) "当前分支" else "全部分支", style = MaterialTheme.typography.labelMedium)
                    AppGlyph(GlyphName.ChevronDown, size = GlyphSize.Small)
                }
            }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("全部分支") }, onClick = { onHead(false); menu = false })
                DropdownMenuItem(text = { Text("当前分支") }, onClick = { onHead(true); menu = false })
            }
        }
        Surface(shape = RoundedCornerShape(100), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Row(Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for ((label, value) in listOf("图谱" to false, "列表" to true)) {
                    Surface(onClick = { onList(value) }, shape = RoundedCornerShape(100),
                        color = if (list == value) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Box(Modifier.height(30.dp).padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable internal fun GitPanel(modifier: Modifier, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = c.surfaceContainerLowest,
        border = BorderStroke(1.dp, c.outlineVariant.copy(alpha = .52f)), content = content)
}

@Composable private fun GitEmpty(title: String, help: String) {
    Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        AppGlyph(GlyphName.Branch, size = GlyphSize.Regular, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun historySummary(data: WorkspaceGitSnapshot, head: Boolean): String =
    "${if (head && data.head != null) data.commits.count { it.onHead } else data.commits.size} 个提交" +
        (if (head && data.head != null) "" else " · ${data.branches.size} 个分支") +
        if (data.truncated) " · 已截断" else ""

@Composable private fun GitList(data: WorkspaceGitSnapshot, head: Boolean, open: (WorkspaceGitCommit) -> Unit, modifier: Modifier) {
    Column(modifier) {
        GitPanel(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 13.dp)) {
                items(data.commits.filter { !head || data.head == null || it.onHead }, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth().clickable { open(c) }.heightIn(min = 49.dp).padding(horizontal = 5.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title.ifBlank { "无提交说明" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(listOf(gitDate(c.timeMillis, "MM-dd HH:mm"), if (c.id == data.head) "HEAD" else "",
                                c.refs.joinToString(" · "), if (c.parents.size > 1) "合并" else "").filter(String::isNotEmpty).joinToString(" · "),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(c.id.take(7), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        AppGlyph(GlyphName.ChevronRight, size = GlyphSize.Small)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .42f))
                }
            }
        }
        Text(historySummary(data, head), Modifier.padding(vertical = 5.dp), style = MaterialTheme.typography.labelSmall)
    }
}

internal fun gitDate(time: Long, pattern: String): String = SimpleDateFormat(pattern, Locale.ROOT).format(Date(time))

@Composable private fun GitDetail(workspaceId: String?, c: WorkspaceGitCommit, data: WorkspaceGitSnapshot?, narrow: Boolean,
    open: (WorkspaceGitCommit) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as CordiActApplication
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val d by produceState<WorkspaceGitCommitDetail?>(null, workspaceId, c.id) {
        value = null
        if (workspaceId != null) value = try { withContext(Dispatchers.IO) {
            WorkspaceGitHistory.detail(app.workspaceManager.resolveRoot(workspaceId), c.id)
        } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = close,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null, onClick = close).padding(14.dp), contentAlignment = if (narrow) Alignment.BottomCenter else Alignment.CenterEnd) {
            GitPanel(Modifier.widthIn(max = 350.dp).fillMaxWidth().heightIn(max = maxHeight * if (narrow) .78f else .9f)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}) {
                LazyColumn(contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (c.parents.size > 1) "合并提交" else "提交详情", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            IconButton(close, Modifier.size(40.dp)) { AppGlyph(GlyphName.Close, size = GlyphSize.Small, description = "关闭提交详情") }
                        }
                        Text(c.title.ifBlank { "无提交说明" }, style = MaterialTheme.typography.titleLarge)
                        val refs = (listOfNotNull("HEAD".takeIf { c.id == data?.head }) + c.refs).joinToString(" · ")
                        if (refs.isNotBlank()) Text(refs, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelMedium)
                    }
                    item {
                        Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .58f))) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("提交", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    TextButton({ clip.setText(androidx.compose.ui.text.AnnotatedString(c.id)) }) {
                                        Text(c.id.take(7), fontFamily = FontFamily.Monospace); Spacer(Modifier.width(5.dp))
                                        AppGlyph(GlyphName.Copy, size = GlyphSize.Small, description = "复制完整哈希")
                                    }
                                }
                                HorizontalDivider()
                                Row(Modifier.padding(vertical = 8.dp)) {
                                    Text("时间", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text(gitDate(c.timeMillis, "yyyy-MM-dd HH:mm"), style = MaterialTheme.typography.bodySmall)
                                }
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("父提交", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    if (c.parents.isEmpty()) Text("无", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                                }
                                for (parent in c.parents) {
                                    val row = data?.commits?.firstOrNull { it.id == parent }
                                    if (row == null) Text("${parent.take(7)} · 不在当前快照", Modifier.padding(vertical = 4.dp), style = MaterialTheme.typography.bodySmall)
                                    else TextButton({ open(row) }, contentPadding = PaddingValues(horizontal = 2.dp)) { Text(parent.take(7), fontFamily = FontFamily.Monospace) }
                                }
                            }
                        }
                    }
                    d?.let { detail ->
                        if (detail.message.trim() != c.title) item { Text(detail.message, style = MaterialTheme.typography.bodyMedium) }
                        if (detail.paths.isNotEmpty()) item { Text(if (c.parents.size > 1) "相对第一父提交的改动" else "改动文件", style = MaterialTheme.typography.titleSmall) }
                        items(detail.paths) { path -> Text(path, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                        if (detail.pathsTruncated) item { Text("仅显示前 60 个文件", style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }
    }
}

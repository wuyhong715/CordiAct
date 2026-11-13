package com.cordiact.app.experience.composer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.cordiact.app.attachment.AttachmentPickerRequests
import com.cordiact.app.experience.*
import com.cordiact.core.attachment.*
import com.cordiact.core.composer.*
import com.cordiact.core.conversation.*
import kotlin.math.roundToInt

@Composable
internal fun ComposerBar(state: ExperienceState, composer: ComposerState, controller: ComposerController,
    insert: String, onInserted: () -> Unit, onSend: (String) -> Boolean, onStop: () -> Unit,
    onSelect: (ModelId) -> Unit, onManage: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var waiting by remember(state.sessionId) { mutableStateOf(false) }
    var confirmLocal by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pickSession by rememberSaveable { mutableStateOf<String?>(null) }
    var pickWorkspace by rememberSaveable { mutableStateOf<String?>(null) }
    fun captureTarget() { pickSession = state.sessionId; pickWorkspace = state.workspace?.id }
    fun importResult(uris: List<android.net.Uri>) {
        controller.import(uris, pickSession, pickWorkspace)
        pickSession = null; pickWorkspace = null
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = cameraPath?.let { java.io.File(it) }
        if (success && file != null) {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.attachments", file)
            importResult(listOf(uri))
        } else file?.delete()
        cameraPath = null
    }
    val photo = rememberLauncherForActivityResult(AttachmentPickerRequests.imagePicker()) { importResult(it) }
    val files = rememberLauncherForActivityResult(AttachmentPickerRequests.documentPicker()) { importResult(it) }
    val registry = remember(photo, files, camera, state.sessionId, state.workspace?.id) { ComposerActionRegistry().apply {
        val intent = android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(context.packageManager) != null) register(ComposerAction("camera", "拍照", GlyphName.Camera) {
            val directory = java.io.File(context.cacheDir, "composer_camera").apply { mkdirs() }
            val output = java.io.File.createTempFile("capture-", ".jpg", directory)
            captureTarget(); cameraPath = output.absolutePath
            camera.launch(androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.attachments", output))
        })
    }
        .register(ComposerAction("photo", "照片", GlyphName.Image) { captureTarget(); photo.launch(AttachmentPickerRequests.imageRequest()) })
        .register(ComposerAction("file", "文件", GlyphName.File) { captureTarget(); files.launch(arrayOf("*/*")) }) }
    LaunchedEffect(insert) { if (insert.isNotEmpty()) { controller.text(insert); onInserted() } }
    fun submit() {
        if (composer.localOnlyCount > 0) confirmLocal = true
        else onSend(composer.text)
    }
    LaunchedEffect(waiting, composer.sendAvailability) {
        if (waiting && composer.sendAvailability == ComposerRules.SendAvailability.READY) { waiting = false; submit() }
        else if (composer.sendAvailability == ComposerRules.SendAvailability.BLOCKED) waiting = false
    }
    val expanded = focused || composer.text.isNotEmpty() || composer.attachments.isNotEmpty() || composer.popover == ComposerPopover.Model || composer.popover == ComposerPopover.Effort
    val duration = motionDuration(Motion.Standard)
    val border by animateColorAsState(if (focused) MaterialTheme.colorScheme.primary.copy(alpha = .4f) else MaterialTheme.colorScheme.outlineVariant, tween(duration))
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        AnimatedVisibility(composer.banner != null || composer.localOnlyCount > 0 || composer.failedCount > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val message = composer.banner?.text ?: if (composer.failedCount > 0) "${composer.failedCount} 个附件失败，请移除或重试"
                    else "${composer.localOnlyCount} 个附件当前模型无法读取"
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (composer.localOnlyCount > 0) TextButton(onClick = { controller.popover(ComposerPopover.Model) }) { Text("换模型") }
            }
        }
        Surface(shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, border), color = MaterialTheme.colorScheme.surface,
            shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth().animateContentSize(tween(duration))) {
            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                AnimatedVisibility(composer.attachments.isNotEmpty(), enter = expandVertically(tween(duration)) + fadeIn(), exit = shrinkVertically(tween(duration)) + fadeOut()) {
                    AttachmentTray(composer.attachments, if (state.running) null else controller::remove, if (state.running) null else controller::retry)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        IconButton(onClick = { controller.popover(ComposerPopover.Attach) }, enabled = !state.loading && !state.running && !waiting) { AppGlyph(GlyphName.Plus, "添加附件", tint = if (composer.popover == ComposerPopover.Attach) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                        AnchoredPopover(composer.popover == ComposerPopover.Attach, { controller.popover(null) }) {
                            registry.actions().forEach { action ->
                                Row(Modifier.fillMaxWidth().clickable { controller.popover(null); action.invoke() }.heightIn(min = 48.dp).padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    AppGlyph(action.glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                                    Text(action.label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            if (!composer.capabilities.attachments.declared) Text("当前模型不能读取附件，仅存到工作区", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    BasicTextField(composer.text, controller::text,
                        enabled = !state.loading && !waiting,
                        modifier = Modifier.weight(1f).padding(vertical = 10.dp).heightIn(min = 24.dp, max = 160.dp)
                            .onFocusChanged { focused = it.isFocused }.testTag("composer-input"),
                        minLines = 1, maxLines = 6, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { field ->
                            Box { if (composer.text.isEmpty()) Text("发送消息…", color = MaterialTheme.colorScheme.onSurfaceVariant); field() }
                        })
                    if (!expanded) SendAction(state, composer, waiting, {
                        if (state.running) onStop() else if (waiting) waiting = false else if (composer.sendAvailability == ComposerRules.SendAvailability.WAIT_FOR_ATTACHMENTS) waiting = true else submit()
                    })
                }
                AnimatedVisibility(expanded, enter = expandVertically(tween(duration)) + fadeIn(), exit = shrinkVertically(tween(duration)) + fadeOut()) {
                    Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            Surface(onClick = { if (composer.model == null) onManage() else controller.popover(ComposerPopover.Effort) }, shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.heightIn(min = 40.dp).semantics { contentDescription = "模型与思考深度：${composer.pillLabel()}" }) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (composer.speed != ResponseSpeed.AUTO) AppGlyph(GlyphName.Bolt, size = GlyphSize.Small)
                                    AnimatedContent(composer.pillLabel(), label = "model-pill", transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) }) { label ->
                                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.widthIn(max = 220.dp))
                                    }
                                    AppGlyph(GlyphName.ChevronDown, size = GlyphSize.Small)
                                }
                            }
                            AnchoredPopover(composer.popover == ComposerPopover.Effort, { controller.popover(null) }) {
                                EffortPanel(composer, controller)
                            }
                            AnchoredPopover(composer.popover == ComposerPopover.Model, { controller.popover(null) }) {
                                ModelList(state.models, composer.model, controller.pinned, controller.recent,
                                    onSelect = { onSelect(it); controller.popover(null) }, onPin = controller::pin,
                                    onManage = { controller.popover(null); onManage() })
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmLocal) AlertDialog(onDismissRequest = { confirmLocal = false }, title = { Text("附件无法读取") },
        text = { Text("${composer.localOnlyCount} 个附件不会发送给当前模型。可以换模型，或仅发送可读取的内容，其余附件仍保存在本地。") },
        confirmButton = { TextButton(onClick = { confirmLocal = false; onSend(composer.text) }) { Text("仅发送可读取内容") } },
        dismissButton = { TextButton(onClick = { confirmLocal = false; controller.popover(ComposerPopover.Model) }) { Text("换模型") } })
}

@Composable
private fun SendAction(state: ExperienceState, composer: ComposerState, waiting: Boolean, onClick: () -> Unit) {
    val duration = motionDuration(Motion.Standard)
    val enabled = !state.loading && !state.stopping && (state.running || waiting || (composer.model != null && composer.sendAvailability in setOf(ComposerRules.SendAvailability.READY, ComposerRules.SendAvailability.WAIT_FOR_ATTACHMENTS)))
    val color by animateColorAsState(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, tween(duration))
    val radius by animateDpAsState(if (state.running) 12.dp else 24.dp, tween(duration))
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(if (state.running) "stop-generation" else "send-message")) {
        Surface(color = color, shape = RoundedCornerShape(radius)) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (waiting || state.stopping) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else AnimatedContent(state.running, label = "send-stop", transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) }) { running ->
                    AppGlyph(if (running) GlyphName.Stop else GlyphName.Send, if (running) "停止当前回合" else "发送消息",
                        tint = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun EffortPanel(state: ComposerState, controller: ComposerController) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(controller::speed, enabled = state.speedCycle.isNotEmpty()) {
            AppGlyph(GlyphName.Bolt, "响应速度：${state.speedLabel(state.speed)}", tint = if (state.speed == ResponseSpeed.AUTO) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            AnimatedContent(state.effort, label = "effort-label") { effort -> Text(state.effortLabel(effort), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = { controller.popover(ComposerPopover.Model) }) {
                Text(state.modelName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                AppGlyph(GlyphName.ChevronDown, size = GlyphSize.Small)
            }
        }
        IconButton(controller::reset) { AppGlyph(GlyphName.Reset, "恢复 auto") }
    }
    val stops = state.effortStops
    if (stops.size > 1) {
        val haptic = LocalHapticFeedback.current
        val index = stops.indexOf(state.effort).coerceAtLeast(0)
        var dragged by remember(stops) { mutableFloatStateOf(index.toFloat()) }
        var dragging by remember { mutableStateOf(false) }
        LaunchedEffect(state.effort, dragging) { if (!dragging) dragged = index.toFloat() }
        Box(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Box(Modifier.fillMaxWidth().height(10.dp).align(Alignment.Center).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(Color(0xFF4865DB), Color(0xFF9A65E8)))))
            Slider(value = dragged, onValueChange = {
                val next = it.roundToInt().coerceIn(stops.indices)
                if (next != dragged.roundToInt()) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                dragging = true; dragged = it; controller.effort(stops[next])
            }, onValueChangeFinished = { dragging = false; dragged = stops.indexOf(state.effort).coerceAtLeast(0).toFloat() }, valueRange = 0f..stops.lastIndex.toFloat(), steps = (stops.size - 2).coerceAtLeast(0),
                colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent, thumbColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.semantics { contentDescription = "思考深度"; stateDescription = state.effortLabel(state.effort) })
        }
        Text(stops.joinToString(" · ") { state.effortLabel(it) }, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
    } else Text("此模型不支持调节思考深度", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    if (state.speed != ResponseSpeed.AUTO) Text("${state.speedLabel(state.speed)} · 可能增加用量", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModelList(models: List<ModelDescriptor>, selected: ModelId?, pinned: List<String>, recent: List<String>,
    onSelect: (ModelId) -> Unit, onPin: (ModelId) -> Unit, onManage: () -> Unit) {
    var search by remember { mutableStateOf("") }
    OutlinedTextField(search, { search = it }, placeholder = { Text("搜索模型") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    val ordered = models.filter { search.isBlank() || "${it.id.modelId} ${it.displayName}".contains(search, true) }
        .sortedWith(compareBy<ModelDescriptor> {
            val key = "${it.id.providerId}/${it.id.modelId}"
            when { key in pinned -> 0; key in recent -> 1; else -> 2 }
        }.thenBy { it.id.providerId }.thenBy { it.id.modelId })
    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
        ordered.forEach { model ->
            val key = "${model.id.providerId}/${model.id.modelId}"
            Row(Modifier.fillMaxWidth().combinedClickable(onClick = { onSelect(model.id) }, onLongClick = { onPin(model.id) }).heightIn(min = 56.dp).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.id.modelId, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(buildString { append(model.id.providerId); if (key in pinned) append(" · 置顶") else if (key in recent) append(" · 最近")
                        if (model.capabilities.attachments.declared) append(" · 附件")
                        if (model.capabilities.reasoningEfforts.isNotEmpty()) append(" · 思考")
                        model.contextWindowTokens?.let { append(" · ${it / 1000}K") }
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (selected == model.id) AppGlyph(GlyphName.Check, "已选中")
            }
        }
        if (ordered.isEmpty()) Text("暂无可用模型", Modifier.padding(16.dp))
    }
    TextButton(onManage, Modifier.fillMaxWidth()) { Text("管理模型 →") }
}

@Composable
internal fun AttachmentTray(chips: List<AttachmentChip>, onRemove: ((AttachmentId) -> Unit)? = null, onRetry: ((AttachmentId) -> Unit)? = null) {
    LazyRow(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(chips, key = { it.id.value }) { chip ->
            var details by remember { mutableStateOf(false) }
            var preview by remember { mutableStateOf(false) }
            val failed = chip.state == AttachmentState.FAILED || chip.compatibility is AttachmentCompatibility.OverLimit
            Surface(onClick = { if (chip.ready && !failed) preview = true else details = true }, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
                color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.animateItem().heightIn(min = 64.dp).widthIn(max = 220.dp).semantics { contentDescription = "${chip.displayName}，${chip.state}" }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                        if (chip.thumbnail != null) Image(chip.thumbnail.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else AppGlyph(if (chip.mediaKind == AttachmentMediaKind.IMAGE) GlyphName.Image else GlyphName.File)
                        if (chip.state == AttachmentState.IMPORTING || chip.state == AttachmentState.VERIFYING) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                    Column(Modifier.widthIn(max = 110.dp)) {
                        Text(chip.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        Text(chip.compatibility?.badge() ?: if (failed) "失败" else if (chip.state == AttachmentState.IMPORTING) "导入中" else "${chip.byteSize / 1024} KB",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (onRemove != null) IconButton({ onRemove(chip.id) }, Modifier.size(32.dp)) { AppGlyph(GlyphName.Close, "移除 ${chip.displayName}", size = GlyphSize.Small) }
                }
            }
            if (preview) AttachmentPreview(chip) { preview = false }
            if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text(chip.displayName) },
                text = { Text(if (failed) (chip.errorCode ?: (chip.compatibility as? AttachmentCompatibility.OverLimit)?.code).message()
                    else if (chip.compatibility == AttachmentCompatibility.LocalOnly) "仅保存在工作区，未发送给模型" else "${chip.descriptor.mimeType} · ${chip.byteSize} bytes") },
                confirmButton = { TextButton({ details = false }) { Text("关闭") } },
                dismissButton = { if (failed && onRetry != null) TextButton({ details = false; onRetry(chip.id) }) { Text("重试") } })
        }
    }
}

package com.cordiact.app.experience.composer

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.cordiact.app.attachment.AttachmentPickerRequests
import com.cordiact.app.experience.*
import com.cordiact.core.attachment.*
import com.cordiact.core.composer.*
import com.cordiact.core.conversation.*
import com.cordiact.interaction.InteractionContext
import com.cordiact.interaction.PreparedInteraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun ComposerBar(state: ExperienceState, composer: ComposerState, controller: ComposerController,
    insert: String, onInserted: () -> Unit, onSend: (String) -> Boolean, onStop: () -> Unit,
    onSelect: (ModelId) -> Unit,
    compact: Boolean = false, maxEditorLines: Int = 3,
    onSendLocal: ((PreparedInteraction) -> Boolean)? = null,
    prepareLocal: ((String, InteractionContext) -> PreparedInteraction?)? = null,
) {
    var waiting by remember(state.sessionId) { mutableStateOf(false) }
    var confirmLocal by remember(state.sessionId) { mutableStateOf(false) }
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
    val registry = remember(photo, files, camera, state.sessionId.orEmpty(), state.workspace?.id) { ComposerActionRegistry().apply {
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
    LaunchedEffect(insert, state.loading) { if (insert.isNotEmpty() && !state.loading) { controller.text(insert); onInserted() } }
    val workspaceId = state.workspace?.id
    val sessionId = state.sessionId
    val localOwner = if (workspaceId != null && sessionId != null && onSendLocal != null && prepareLocal != null &&
        !state.loading && !state.archived && !state.running && !state.stopping &&
        composer.attachments.isEmpty() && composer.text.trimStart().startsWith('/')) {
        InteractionContext(sessionId, workspaceId)
    } else null
    // Reset immediately when the draft or owner changes, before the new IO lookup completes.
    var localOffer by remember(prepareLocal, localOwner, composer.text) { mutableStateOf<PreparedInteraction?>(null) }
    LaunchedEffect(prepareLocal, localOwner, composer.text) {
        if (prepareLocal != null && localOwner != null) {
            localOffer = withContext(Dispatchers.IO) { prepareLocal(composer.text, localOwner) }
        }
    }
    fun submit() {
        if (state.loading || state.archived || state.running || state.stopping ||
            composer.sendAvailability != ComposerRules.SendAvailability.READY) return
        val offer = localOffer
        if (offer != null && onSendLocal != null) onSendLocal(offer)
        else if (composer.model != null) {
            if (composer.localOnlyCount > 0) confirmLocal = true
            else onSend(composer.text)
        }
    }
    LaunchedEffect(waiting, composer.sendAvailability, state.loading) {
        if (state.loading) { waiting = false; confirmLocal = false; return@LaunchedEffect }
        if (waiting && composer.sendAvailability == ComposerRules.SendAvailability.READY) { waiting = false; submit() }
        else if (composer.sendAvailability == ComposerRules.SendAvailability.BLOCKED) waiting = false
    }
    val editor: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth()) {
            CompletionEditor(context.applicationContext as? com.cordiact.app.CordiActApplication,
                sessionId.orEmpty(), workspaceId, composer.text,
                !state.loading && !waiting, controller::text,
                maxLines = if (compact) maxEditorLines else 6, compact = compact,
                onSubmit = { if (!waiting) submit() })
        }
    }
    val attach: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { controller.popover(ComposerPopover.Attach) }, enabled = !state.loading && !state.running && !waiting) { Box {
                AppGlyph(GlyphName.Plus, if (composer.attachments.isEmpty()) "添加附件" else "附件 ${composer.attachments.size} 个",
                    tint = if (composer.popover == ComposerPopover.Attach) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                if (compact && composer.attachments.isNotEmpty()) Badge(Modifier.align(Alignment.TopEnd))
            } }
            AnchoredPopover(composer.popover == ComposerPopover.Attach, { controller.popover(null) }, width = 160.dp, maxHeight = 156.dp) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    registry.actions().forEach { action ->
                        Row(Modifier.fillMaxWidth().clickable { controller.popover(null); action.invoke() }.heightIn(min = 48.dp).padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            AppGlyph(action.glyph, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                            Text(action.label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
    val model: @Composable (Boolean) -> Unit = { iconOnly ->
        Box(Modifier.fillMaxWidth()) {
            Surface(onClick = { controller.popover(if (composer.model == null) ComposerPopover.Model else ComposerPopover.Effort) }, enabled = !compact || !state.loading, shape = CircleShape,
                color = Color.Transparent, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "模型与思考深度：${composer.pillLabel()}" }) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (compact && iconOnly) AppGlyph(GlyphName.Model)
                    if ((!compact || !iconOnly) && composer.hasPaidSpeed() && composer.speed != ResponseSpeed.AUTO) AppGlyph(composer.speed.glyph(), size = GlyphSize.Small)
                    if (!compact || !iconOnly) Text(composer.pillLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false))
                    if (!compact || !iconOnly) AppGlyph(GlyphName.ChevronDown, size = GlyphSize.Small)
                }
            }
            val choosing = composer.popover == ComposerPopover.Model
            AnchoredPopover(choosing || composer.popover == ComposerPopover.Effort,
                { controller.popover(if (choosing && composer.model != null) ComposerPopover.Effort else null) },
                width = if (choosing) 212.dp else 264.dp, maxHeight = if (choosing) 212.dp else 200.dp) {
                if (choosing) {
                    ModelList(state.models, state.providerNames, composer.model, controller.pinned, controller.recent,
                        onSelect = { onSelect(it); controller.popover(ComposerPopover.Effort) }, onPin = controller::pin)
                } else EffortPanel(composer, controller)
            }
        }
    }
    val trailing: @Composable () -> Unit = {
        ComposerPermission(state.workspace?.id, composer.model, !state.running && !state.loading && !state.stopping && !waiting)
        SendAction(state, composer, waiting, localReady = localOffer != null) {
            if (state.running) onStop() else if (waiting) waiting = false else if (composer.sendAvailability == ComposerRules.SendAvailability.WAIT_FOR_ATTACHMENTS) waiting = true else submit()
        }
    }
    if (compact) {
        FrostedSurface(radius = 24.dp, modifier = Modifier.fillMaxWidth()) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                val iconOnly = maxWidth < 410.dp || maxEditorLines < 3
                Column {
                    if (composer.attachments.isNotEmpty()) AttachmentTray(composer.attachments,
                        if (state.running) null else controller::remove, if (state.running) null else controller::retry)
                    if (maxEditorLines < 3) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                            attach()
                            Box(Modifier.weight(1f)) { editor() }
                            Box(Modifier.width(48.dp)) { model(true) }
                            trailing()
                        }
                    } else {
                        Column(Modifier.fillMaxWidth()) {
                            Box(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 2.dp)) { editor() }
                            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                                attach()
                                Box(Modifier.width(if (iconOnly) 48.dp else 132.dp)) { model(iconOnly) }
                                Spacer(Modifier.weight(1f))
                                trailing()
                            }
                        }
                    }
                }
            }
        }
    } else {
    val duration = motionDuration(Motion.Standard)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        AnimatedVisibility(composer.banner != null || composer.localOnlyCount > 0 || composer.failedCount > 0) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val message = composer.banner?.text ?: if (composer.failedCount > 0) "${composer.failedCount} 个附件失败，请移除或重试"
                    else "${composer.localOnlyCount} 个附件当前模型无法读取"
                Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        com.cordiact.app.experience.FrostedSurface(radius = 28.dp,
            modifier = Modifier.fillMaxWidth().animateContentSize(tween(duration))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                AnimatedVisibility(composer.attachments.isNotEmpty(), enter = expandVertically(tween(duration)) + fadeIn(), exit = shrinkVertically(tween(duration)) + fadeOut()) {
                    AttachmentTray(composer.attachments, if (state.running) null else controller::remove, if (state.running) null else controller::retry)
                }
                if (state.running && composer.text.isNotBlank()) {
                    Text("本轮运行中，草稿已保留", Modifier.padding(horizontal = 10.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                editor()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    attach()
                    Box(Modifier.weight(1f)) { model(false) }
                    trailing()
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
}

@Composable
private fun SendAction(state: ExperienceState, composer: ComposerState, waiting: Boolean, localReady: Boolean, onClick: () -> Unit) {
    val duration = motionDuration(Motion.Standard)
    val canSend = when (composer.sendAvailability) {
        ComposerRules.SendAvailability.READY -> localReady || composer.model != null
        ComposerRules.SendAvailability.WAIT_FOR_ATTACHMENTS -> composer.model != null
        ComposerRules.SendAvailability.BLOCKED, ComposerRules.SendAvailability.DISABLED -> false
    }
    val enabled = !state.loading && !state.stopping && (state.running || (!state.archived && (waiting || canSend)))
    val color by animateColorAsState(when { !enabled -> MaterialTheme.colorScheme.surfaceContainerHigh; state.running -> com.cordiact.app.experience.Lumen.Signal; else -> MaterialTheme.colorScheme.onSurface }, tween(duration))
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp).testTag(if (state.running) "stop-generation" else "send-message")) {
        Surface(color = color, shape = CircleShape) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                if (waiting || state.stopping) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else AnimatedContent(state.running, label = "send-stop", transitionSpec = { fadeIn(tween(duration)) togetherWith fadeOut(tween(duration)) }) { running ->
                    AppGlyph(if (running) GlyphName.Stop else GlyphName.Send, if (running) "停止当前回合" else "发送消息",
                        tint = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant else if (state.running) com.cordiact.app.experience.Lumen.Ink else MaterialTheme.colorScheme.surface)
                }
            }
        }
    }
}

private fun ComposerState.hasPaidSpeed() = model?.modelId?.let { id ->
    Regex("(^|[^a-z0-9])gpt(?:[-_.]|[0-9]|$)", RegexOption.IGNORE_CASE).containsMatchIn(id)
} == true && speedCycle.any { it != ResponseSpeed.AUTO }

private fun ResponseSpeed.glyph() = if (this == ResponseSpeed.ULTRAFAST) GlyphName.DoubleBolt else GlyphName.Bolt

@Composable
private fun EffortPanel(state: ComposerState, controller: ComposerController) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp))
            .clickable(role = Role.Button, onClickLabel = "切换模型") { controller.popover(ComposerPopover.Model) }
            .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(state.modelName, style = MaterialTheme.typography.titleSmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            AppGlyph(GlyphName.ChevronDown, size = GlyphSize.Small)
        }
        if (state.hasPaidSpeed()) {
            val tint = if (state.speed == ResponseSpeed.AUTO) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
            val highlight = when (state.speed) {
                ResponseSpeed.FAST -> MaterialTheme.colorScheme.primary.copy(alpha = .10f)
                ResponseSpeed.ULTRAFAST -> MaterialTheme.colorScheme.primary.copy(alpha = .19f)
                else -> Color.Transparent
            }
            IconButton(controller::speed, Modifier.size(48.dp).semantics { stateDescription = state.speedLabel(state.speed) }) {
                Box(Modifier.size(32.dp).background(highlight, CircleShape), contentAlignment = Alignment.Center) {
                    AppGlyph(state.speed.glyph(), "付费快速思考，切换档位", tint = tint)
                }
            }
        }
    }
    val stops = state.effortStops
    if (stops.size > 1) {
        val haptic = LocalHapticFeedback.current
        val index = stops.indexOf(state.effort).coerceAtLeast(0)
        var preview by remember(state.model, stops, state.effort) { mutableFloatStateOf(index.toFloat()) }
        val shown = stops[preview.roundToInt().coerceIn(stops.indices)]
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .50f))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("思考深度", style = MaterialTheme.typography.labelLarge)
                Text(state.effortLabel(shown), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
            val fill = preview / stops.lastIndex
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(28.dp).align(Alignment.Center).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(MaterialTheme.colorScheme.primary.copy(alpha = .24f)))
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        stops.indices.forEach { Box(Modifier.size(4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f), CircleShape)) }
                    }
                }
                Slider(value = preview, onValueChange = {
                    if (it.roundToInt() != preview.roundToInt()) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    preview = it
                }, onValueChangeFinished = {
                    val chosen = stops[preview.roundToInt().coerceIn(stops.indices)]
                    if (chosen != state.effort) controller.effort(chosen)
                }, valueRange = 0f..stops.lastIndex.toFloat(), steps = stops.size - 2,
                    colors = SliderDefaults.colors(activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent,
                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent, thumbColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "思考深度"; stateDescription = state.effortLabel(shown) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val labels = if (stops.size > 5) listOf(stops.first(), stops.last()) else stops
                labels.forEach { Text(state.effortLabel(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelList(models: List<ModelDescriptor>, providers: Map<String, String>, selected: ModelId?, pinned: List<String>, recent: List<String>,
    onSelect: (ModelId) -> Unit, onPin: (ModelId) -> Unit) {
    val ordered = remember(models, pinned, recent) {
        models.sortedWith(compareBy<ModelDescriptor> {
            val key = "${it.id.providerId}/${it.id.modelId}"
            when { key in pinned -> 0; key in recent -> 1; else -> 2 }
        }.thenBy { it.id.providerId }.thenBy { it.id.modelId })
    }
    val selectedIndex = ordered.indexOfFirst { it.id == selected }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (selectedIndex - 1).coerceAtLeast(0))
    if (models.isEmpty()) Text("暂无可用模型", Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
    LazyColumn(state = list) {
        items(ordered) { model ->
            val checked = model.id == selected
            val shape = RoundedCornerShape(9.dp)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(shape)
                .background(if (checked) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else Color.Transparent)
                .combinedClickable(role = Role.RadioButton, onClick = { onSelect(model.id) }, onLongClick = { onPin(model.id) })
                .semantics { this.selected = checked }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(model.displayName.ifBlank { model.id.modelId }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(providers[model.id.providerId]?.takeIf { it.isNotBlank() } ?: "模型服务商",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (checked) AppGlyph(GlyphName.Check, tint = MaterialTheme.colorScheme.primary, size = GlyphSize.Small)
            }
        }
    }
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

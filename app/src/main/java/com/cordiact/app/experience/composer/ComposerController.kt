package com.cordiact.app.experience.composer

import android.graphics.BitmapFactory
import android.net.Uri
import com.cordiact.app.CordiActApplication
import com.cordiact.core.attachment.*
import com.cordiact.core.composer.ComposerRules
import com.cordiact.core.conversation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.UUID

/** Owns draft state independently of Compose lifetimes. All mutations happen on the main thread. */
class ComposerController(
    private val app: android.content.Context,
    private val scope: CoroutineScope,
    private val catalogue: () -> com.cordiact.app.attachment.AndroidAttachmentCatalog,
    private val importer: () -> com.cordiact.app.attachment.AndroidAttachmentImportService,
) {
    constructor(app: CordiActApplication, scope: CoroutineScope) : this(app, scope, { app.attachments }, { app.attachmentImporter })
    private val drafts = ComposerDraftStore(app)
    private val mutable = MutableStateFlow(ComposerState())
    val state = mutable.asStateFlow()
    private val snapshots = mutableMapOf<String, ComposerState>()
    private val importJobs = mutableMapOf<String, Job>()
    private val writes = Channel<() -> Unit>(Channel.UNLIMITED)
    private var active: String? = null
    private var workspace: String? = null
    private var last = drafts.lastUsed()
    var pinned by mutableStatePreference(app, "pinned")
        private set
    var recent by mutableStatePreference(app, "recent")
        private set

    init {
        // Remove obsolete global model selection, never read it as a fallback.
        app.getSharedPreferences("experience", 0).edit().remove("provider").remove("model").apply()
        scope.launch(Dispatchers.IO) { for (write in writes) runCatching(write).onFailure { failure ->
            withContext(Dispatchers.Main) { mutable.update { it.copy(banner = ComposerBanner("草稿保存失败：${failure.javaClass.simpleName}", BannerTone.ERROR)) } }
        } }
    }

    private fun key(session: String) = "composer:$session"
    private fun update(session: String? = active, block: (ComposerState) -> ComposerState) {
        if (session == null) { mutable.update(block); return }
        val next = block(if (active == session) mutable.value else snapshots[session] ?: ComposerState())
        snapshots[session] = next
        if (active == session) mutable.value = next
    }

    suspend fun open(session: String, workspaceId: String, models: List<ModelDescriptor>, fallback: ModelId?) {
        active?.let { snapshots[it] = mutable.value.copy(popover = null) }
        active = session; workspace = workspaceId
        val cached = snapshots[session]
        val flushed = CompletableDeferred<Unit>()
        writes.send { flushed.complete(Unit) }
        flushed.await()
        val draft = withContext(Dispatchers.IO) { drafts.load(session) }
        if (active != session) return
        val chosen = models.find { it.id == (cached?.model ?: draft.model ?: last.model) }
            ?: models.find { it.id == fallback } ?: models.firstOrNull()
        val base = cached ?: ComposerState(text = draft.text,
            effort = if (draft.model == null) last.effort else draft.effort,
            speed = if (draft.model == null) last.speed else draft.speed)
        val restored = if (cached != null) cached.attachments else withContext(Dispatchers.IO) {
            catalogue().draftAttachments(key(session)).map { descriptor ->
                val rendition = draftsRendition(session, descriptor.id)
                val derived = rendition?.let { catalogue().rendition(workspaceId, descriptor.id, it) }
                val effective = normalizedDescriptor(com.cordiact.app.attachment.ImportedAttachmentResult(descriptor, derived))
                AttachmentChip(effective, descriptor.state, renditionId = rendition,
                    thumbnail = thumbnail(descriptor, rendition))
            }
        }
        if (active != session) return
        update(session) { base.copy(attachments = restored, popover = null) }
        if (chosen != null) select(chosen) else update { it.copy(model = null, modelName = "添加模型", capabilities = ModelCapabilities.NONE) }
    }

    private fun draftsRendition(session: String, id: AttachmentId): String? =
        app.getSharedPreferences("composer_renditions", 0).getString("$session:${id.value}", null)

    private fun thumbnail(descriptor: AttachmentDescriptor, rendition: String?): android.graphics.Bitmap? {
        if (descriptor.mediaKind != AttachmentMediaKind.IMAGE) return null
        return runCatching {
            catalogue().open(descriptor.workspaceId, descriptor.id, rendition).use { input ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }.getOrNull()
    }

    fun select(descriptor: ModelDescriptor) {
        update { old ->
            val tuning = ComposerRules.reconcile(old.tuning, descriptor.capabilities)
            old.copy(model = descriptor.id, modelName = descriptor.id.modelId, capabilities = descriptor.capabilities,
                effort = tuning.effort, speed = tuning.speed,
                attachments = old.attachments.map { it.withModel(descriptor.capabilities) },
                banner = if (tuning != old.tuning) ComposerBanner("不支持原档位，已恢复 auto", BannerTone.INFO) else null)
        }
        save()
    }
    fun text(value: String) { update { it.copy(text = value, banner = null) }; save() }
    fun effort(value: ReasoningEffort) {
        if (value == ReasoningEffort.AUTO || value in mutable.value.capabilities.reasoningEfforts) {
            update { it.copy(effort = value, banner = null) }; save()
        }
    }
    fun speed() { update { it.copy(speed = ComposerRules.nextSpeed(it.speed, it.capabilities)) }; save() }
    fun reset() { update { it.copy(effort = ReasoningEffort.AUTO, speed = ResponseSpeed.AUTO) }; save() }
    fun popover(value: ComposerPopover?) { update { it.copy(popover = value) } }
    fun banner(text: String) { update { it.copy(banner = ComposerBanner(text, BannerTone.ERROR)) } }
    fun pin(id: ModelId) {
        val encoded = "${id.providerId}/${id.modelId}"
        pinned = if (encoded in pinned) pinned - encoded else pinned + encoded
        update { it.copy(usageRevision = it.usageRevision + 1) }
    }

    private fun save() {
        val session = active ?: return
        val s = mutable.value
        val draft = ComposerDraft(s.text, s.model, s.effort, s.speed)
        last = draft.copy(text = "")
        writes.trySend { drafts.save(session, draft); drafts.saveLastUsed(draft) }
    }

    fun import(uris: List<Uri>, sessionId: String? = active, workspaceId: String? = workspace) {
        val session = sessionId ?: return
        val ws = workspaceId ?: return
        val draft = if (active == session) mutable.value else snapshots[session] ?: return
        val room = draft.maxAttachments - draft.attachments.size
        if (uris.size > room) update(session) { it.copy(banner = ComposerBanner("最多 ${draft.maxAttachments} 个附件，超出的未添加", BannerTone.ERROR)) }
        uris.take(room.coerceAtLeast(0)).forEach { uri -> startImport(session, ws, uri) }
    }

    private fun startImport(session: String, ws: String, uri: Uri) {
        val token = "import-${UUID.randomUUID()}"
        val pending = AttachmentDescriptor(AttachmentId(token), ws, "application/octet-stream", "导入中…", 0, "", AttachmentState.IMPORTING, System.currentTimeMillis())
        update(session) { it.copy(attachments = it.attachments + AttachmentChip(pending, AttachmentState.IMPORTING, sourceUri = uri)) }
        importJobs[token] = scope.launch {
            var imported: com.cordiact.app.attachment.ImportedAttachmentResult? = null
            try {
                // This synchronous importer is confined to IO. Cancellation is checked before a
                // result is published; abandoned output is removed in finally.
                val importContext = currentCoroutineContext()
                withContext(NonCancellable + Dispatchers.IO) {
                    imported = importer().importUri(ws, uri,
                        checkCancelled = { importContext.ensureActive() },
                        onVerifying = { scope.launch {
                            update(session) { s -> s.copy(attachments = s.attachments.map { chip ->
                                if (chip.id.value == token) chip.copy(state = AttachmentState.VERIFYING) else chip
                            }) }
                        } })
                }
                ensureActive()
                val result = requireNotNull(imported)
                val bitmap = withContext(Dispatchers.IO) {
                    check(catalogue().addDraftAttachment(key(session), ws, session, result.descriptor.id, System.currentTimeMillis()))
                    app.getSharedPreferences("composer_renditions", 0).edit()
                        .putString("$session:${result.descriptor.id.value}", result.sendRendition?.id).apply()
                    thumbnail(result.descriptor, result.sendRendition?.id)
                }
                ensureActive()
                update(session) { state -> state.copy(attachments = state.attachments.map { chip ->
                    if (chip.id.value == token) AttachmentChip(normalizedDescriptor(result), AttachmentState.READY,
                        renditionId = result.sendRendition?.id, sourceUri = uri, thumbnail = bitmap).withModel(state.capabilities) else chip
                }) }
                imported = null // draft owns the imported object now
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                ensureActive()
                update(session) { s -> s.copy(attachments = s.attachments.map { chip -> if (chip.id.value == token)
                    chip.copy(state = AttachmentState.FAILED, errorCode = (failure as? AttachmentException)?.code ?: AttachmentErrorCode.STORAGE_FAILURE) else chip }) }
            } finally {
                val orphan = imported
                if (orphan != null) withContext(NonCancellable + Dispatchers.IO) {
                    catalogue().removeDraftAttachment(key(session), orphan.descriptor.id)
                    if (!catalogue().isReferenced(ws, orphan.descriptor.id)) catalogue().delete(ws, orphan.descriptor.id)
                }
                if (uri.authority == "${app.packageName}.attachments") {
                    withContext(NonCancellable + Dispatchers.IO) { runCatching { app.contentResolver.delete(uri, null, null) } }
                }
                importJobs.remove(token)
            }
        }
    }

    private fun normalizedDescriptor(result: com.cordiact.app.attachment.ImportedAttachmentResult): AttachmentDescriptor {
        val rendition = result.sendRendition ?: return result.descriptor
        val transform = runCatching { org.json.JSONObject(rendition.transformJson) }.getOrNull()
        val width = transform?.optInt("width")?.takeIf { it > 0 }
        val height = transform?.optInt("height")?.takeIf { it > 0 }
        return result.descriptor.copy(byteSize = rendition.byteSize,
            mimeType = rendition.mimeType, width = width ?: result.descriptor.width, height = height ?: result.descriptor.height)
    }

    fun remove(id: AttachmentId) {
        val session = active ?: return
        val chip = mutable.value.attachments.find { it.id == id } ?: return
        importJobs[id.value]?.cancel()
        update { it.copy(attachments = it.attachments.filterNot { a -> a.id == id }) }
        writes.trySend {
            catalogue().removeDraftAttachment(key(session), id)
            if (!id.value.startsWith("import-") && !catalogue().isReferenced(chip.descriptor.workspaceId, id))
                catalogue().delete(chip.descriptor.workspaceId, id)
        }
    }
    fun retry(id: AttachmentId) {
        val chip = mutable.value.attachments.find { it.id == id } ?: return
        val uri = chip.sourceUri ?: return banner("原文件授权已失效，请重新选择")
        remove(id); import(listOf(uri))
    }

    fun clearSubmittedText(session: String, text: String) {
        update(session) { it.copy(text = if (it.text == text) "" else it.text) }
        if (active == session) save()
    }

    /** Call only after submitOrdinary has synchronously persisted the user message parts. */
    fun submitted(session: String, sent: ComposerState) {
        val encoded = sent.model?.let { "${it.providerId}/${it.modelId}" }
        if (encoded != null) recent = (listOf(encoded) + recent).distinct().take(5)
        // Later edits/imports belong to the next message and must not be removed.
        update(session) { current -> current.copy(
            text = if (current.text == sent.text) "" else current.text,
            attachments = current.attachments.filterNot { chip -> sent.attachments.any { it.id == chip.id } }, banner = null) }
        val next = snapshots[session] ?: sent.copy(text = "", attachments = emptyList())
        writes.trySend {
            sent.attachments.forEach { chip ->
                catalogue().removeDraftAttachment(key(session), chip.id)
                if (!catalogue().isReferenced(chip.descriptor.workspaceId, chip.id)) catalogue().delete(chip.descriptor.workspaceId, chip.id)
            }
            drafts.save(session, ComposerDraft(next.text, next.model, next.effort, next.speed))
        }
    }
}

/** Small preference delegate; model IDs are JSON strings so slashes/newlines remain unambiguous. */
private fun mutableStatePreference(app: android.content.Context, name: String) = object : kotlin.properties.ReadWriteProperty<Any?, List<String>> {
    private val prefs = app.getSharedPreferences("composer_model_usage", 0)
    private var values = runCatching {
        val array = org.json.JSONArray(prefs.getString(name, "[]"))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())
    override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = values
    override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: List<String>) {
        values = value; prefs.edit().putString(name, org.json.JSONArray(value).toString()).apply()
    }
}

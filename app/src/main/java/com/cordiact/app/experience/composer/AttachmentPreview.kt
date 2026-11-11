package com.cordiact.app.experience.composer

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.*
import com.cordiact.core.attachment.*
import kotlinx.coroutines.*

private sealed interface PreviewData {
    data class Picture(val bitmap: Bitmap) : PreviewData
    data class Text(val text: String) : PreviewData
    data class Failure(val message: String) : PreviewData
}

/** Read-only, bounded local preview: no WebView, external URL, or file-permission grant. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentPreview(chip: AttachmentChip, onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as? CordiActApplication
    val data by produceState<PreviewData?>(null, chip.id) {
        value = withContext(Dispatchers.IO) {
            if (app == null) return@withContext PreviewData.Failure("本地预览不可用")
            try {
                when (chip.mediaKind) {
                    AttachmentMediaKind.IMAGE -> app.attachments.open(chip.descriptor.workspaceId, chip.id, chip.renditionId).use {
                        val bitmap = BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                        bitmap?.let(PreviewData::Picture) ?: PreviewData.Failure("图片无法解码")
                    }
                    AttachmentMediaKind.TEXT -> app.attachments.open(chip.descriptor.workspaceId, chip.id).use {
                        PreviewData.Text(it.readNBytes(262144).toString(Charsets.UTF_8))
                    }
                    AttachmentMediaKind.PDF -> {
                        val temp = java.io.File.createTempFile("attachment-preview-", ".pdf", app.cacheDir)
                        try {
                            app.attachments.open(chip.descriptor.workspaceId, chip.id).use { input -> temp.outputStream().use { input.copyTo(it) } }
                            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                                PdfRenderer(fd).use { renderer ->
                                    if (renderer.pageCount == 0) PreviewData.Failure("PDF 没有页面") else renderer.openPage(0).use { page ->
                                        val factor = minOf(2f, 1600f / maxOf(page.width, page.height).coerceAtLeast(1))
                                        val bitmap = Bitmap.createBitmap((page.width * factor).toInt().coerceAtLeast(1), (page.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                        bitmap.eraseColor(android.graphics.Color.WHITE)
                                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                        PreviewData.Picture(bitmap)
                                    }
                                }
                            }
                        } finally { temp.delete() }
                    }
                    else -> PreviewData.Failure("此类型仅支持保存到工作区")
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { PreviewData.Failure("无法打开附件，文件可能已移除") }
        }
    }
    Dialog(onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(topBar = { TopAppBar(title = { Text(chip.displayName, maxLines = 1) },
            navigationIcon = { IconButton(onClose) { AppGlyph(GlyphName.Close, "关闭附件预览") } }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                when (val content = data) {
                    null -> CircularProgressIndicator()
                    is PreviewData.Failure -> Text(content.message)
                    is PreviewData.Text -> androidx.compose.foundation.text.selection.SelectionContainer {
                        Text(content.text, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp))
                    }
                    is PreviewData.Picture -> {
                        var zoom by remember { mutableFloatStateOf(1f) }
                        val gestures = rememberTransformableState { scale, _, _ -> zoom = (zoom * scale).coerceIn(1f, 5f) }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (chip.mediaKind == AttachmentMediaKind.PDF) Text("PDF 首页预览", style = MaterialTheme.typography.labelSmall)
                            Image(content.bitmap.asImageBitmap(), chip.displayName, Modifier.fillMaxSize().transformable(gestures).graphicsLayer { scaleX = zoom; scaleY = zoom })
                        }
                    }
                }
            }
        }
    }
}

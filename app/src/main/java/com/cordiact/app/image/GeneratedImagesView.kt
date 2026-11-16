package com.cordiact.app.image

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun GeneratedImagesView(workspace: String, session: String, refreshKey: Any, running: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember(workspace, session) { mutableStateOf(emptyList<GeneratedImageStore.Entry>()) }
    var pending by remember { mutableStateOf<GeneratedImageStore.Entry?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val entry = pending
        pending = null
        if (uri != null && entry != null) scope.launch {
            notice = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output -> entry.preview.inputStream().use { it.copyTo(output) } }
                        ?: error("无法打开目标文件")
                }.fold({ "图片已导出" }, { "导出失败，请重试" })
            }
        }
    }
    LaunchedEffect(workspace, session, refreshKey, running) {
        do {
            entries = withContext(Dispatchers.IO) { GeneratedImageStore(context).list(workspace, session) }
            if (running) delay(2000)
        } while (running)
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (entries.isNotEmpty()) Text("生成的图片", style = MaterialTheme.typography.titleSmall)
        entries.forEach { entry ->
            key(entry.id) {
                var enlarged by remember { mutableStateOf(false) }
                val bitmap by produceState<android.graphics.Bitmap?>(null, entry.id) {
                    value = withContext(Dispatchers.IO) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(entry.preview.path, bounds)
                        val options = BitmapFactory.Options().apply {
                            var sample = 1
                            while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                            inSampleSize = sample
                        }
                        BitmapFactory.decodeFile(entry.preview.path, options)
                    }
                }
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        bitmap?.let { Image(it.asImageBitmap(), "生成的图片，点击放大", Modifier.fillMaxWidth().heightIn(max = 360.dp).clickable { enlarged = true }) }
                        Text(entry.model, style = MaterialTheme.typography.labelMedium)
                        Text(entry.path, style = MaterialTheme.typography.bodySmall)
                        TextButton({ pending = entry; saver.launch(entry.path) }) { Text("导出图片") }
                    }
                }
                if (enlarged) Dialog(onDismissRequest = { enlarged = false }) {
                    Surface { Column {
                        bitmap?.let { Image(it.asImageBitmap(), "生成的图片", Modifier.fillMaxWidth()) }
                        TextButton({ enlarged = false }) { Text("关闭") }
                    } }
                }
            }
        }
        notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.core.attachment.*

@Composable
internal fun AttachmentSettingsPage(initial: AttachmentLimits, busy: Boolean, dirty: (Boolean) -> Unit, save: (AttachmentLimits) -> Unit) {
    var values by remember(initial) { mutableStateOf(listOf(initial.maxAttachmentsPerMessage.toString(), initial.maxImageBytes.toString(), initial.maxImagePixels.toString(), initial.maxImageEdge.toString(), initial.maxPdfBytes.toString(), initial.maxPdfPages.toString(), initial.maxTextBytes.toString(), initial.maxTextTotalBytes.toString())) }
    val labels = listOf("每条消息附件数", "单张图片字节数", "图片像素数", "图片长边像素", "单个 PDF 字节数", "PDF 页数", "单个文本字节数", "文本合计字节数")
    val maximums = listOf(10L, 10485760L, 20000000L, 8192L, 10485760L, 100L, 262144L, 524288L)
    val parsed = values.map { it.toLongOrNull() }
    val valid = parsed.withIndex().all { (i, v) -> v != null && v in 1..maximums[i] } && (parsed[7] ?: 0) >= (parsed[6] ?: 1)
    Text("全局 · 导入与发送时重新检查；模型声明的限制仍会取更小值。", style = MaterialTheme.typography.bodySmall)
    labels.forEachIndexed { index, label ->
        OutlinedTextField(values[index], onValueChange = { value -> values = values.toMutableList().apply { set(index, value) }; dirty(true) },
            label = { Text(label) }, supportingText = { Text("1–${maximums[index]}") },
            isError = parsed[index] == null || parsed[index] !in 1..maximums[index], enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
    Text("请求体：原始 16 MiB / 编码后 24 MiB（只读）")
    Text("未声明附件能力的模型仍拒绝发送；此处不根据模型名称推测支持。", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = !busy, onClick = { values = maximums.map(Long::toString); dirty(true) }) { Text("恢复默认") }
        Button(enabled = valid && !busy, onClick = {
            save(AttachmentLimits(parsed[0]!!.toInt(), parsed[1]!!, parsed[2]!!, parsed[3]!!.toInt(), parsed[4]!!, parsed[5]!!.toInt(), parsed[6]!!, parsed[7]!!))
        }) { Text("保存") }
    }
}

package com.cordiact.app.experience.settings.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.settings.SettingsStatus
import kotlinx.coroutines.delay

@Composable
fun SettingsSectionLabel(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.outlineVariant) {
        Column(Modifier.fillMaxWidth().padding(1.dp), verticalArrangement = Arrangement.spacedBy(1.dp), content = content)
    }
}

@Composable
fun SettingsRow(title: String, summary: String, icon: GlyphName? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(0.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 58.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (icon != null) AppGlyph(icon, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = if (icon == null) 0.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            AppGlyph(GlyphName.ChevronRight, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
@Composable
fun StatusChip(status: SettingsStatus, text: String) {
    val color = when(status) {
        SettingsStatus.READY -> MaterialTheme.colorScheme.secondary
        SettingsStatus.NEEDS_ACTION -> MaterialTheme.colorScheme.tertiary
        SettingsStatus.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    Text("${if (status == SettingsStatus.DISABLED || status == SettingsStatus.UNAVAILABLE) "○" else "●"} $text", color = color, style = MaterialTheme.typography.bodySmall)
}
private tailrec fun Context.activity(): Activity? = when(this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
@Composable
fun SecretField(value: String, onValueChange: (String) -> Unit, enabled: Boolean = true, label: String = "API Key（不会回显已存密钥）") {
    val context = androidx.compose.ui.platform.LocalContext.current
    val window = remember(context) { context.activity()?.window }
    var owned by remember { mutableStateOf(false) }
    fun release() { if (owned) { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE); owned = false } }
    DisposableEffect(window) { onDispose { release() } }
    OutlinedTextField(value, { onValueChange(it.trim()) }, label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = enabled,
        modifier = Modifier.fillMaxWidth().onFocusChanged { focus ->
            if (focus.isFocused && window != null && window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE); owned = true
            } else if (!focus.isFocused) release()
        })
}
@Composable
fun DangerConfirmDialog(title: String, impact: String, busy: Boolean, onCancel: () -> Unit, onConfirm: () -> Unit, confirmLabel: String = "确认删除") {
    var acknowledged by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(3000); ready = true }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text(title) }, text = {
        Column {
            Text(impact, color = MaterialTheme.colorScheme.error)
            Row { Checkbox(acknowledged, { acknowledged = it }, enabled = !busy); Text("我了解以上影响", Modifier.padding(top = 12.dp)) }
        }
    }, dismissButton = { TextButton(onCancel, enabled = !busy) { Text("取消") } },
        confirmButton = { TextButton(onConfirm, enabled = ready && acknowledged && !busy) { Text(if (ready) confirmLabel else "请稍候…") } })
}

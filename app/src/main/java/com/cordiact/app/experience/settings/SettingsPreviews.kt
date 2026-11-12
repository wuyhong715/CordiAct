package com.cordiact.app.experience.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.settings.components.*

@Preview(showBackground = true, widthDp = 390)
@Composable
private fun SettingsPhonePreview() {
    MaterialTheme { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        SettingsRow("1 项需要处理", "记忆抽取：模型已删除或停用") {}
        SettingsRow("模型提供商", "2 个 · 12 个启用模型") {}
        SettingsRow("模型角色", "对话默认 / 上下文摘要 / 记忆抽取") {}
        StatusChip(SettingsStatus.NEEDS_ACTION, "需要设置 Key")
    } }
}
@Preview(showBackground = true, widthDp = 390, fontScale = 2f)
@Composable
private fun SettingsLargeTextPreview() { SettingsPhonePreview() }

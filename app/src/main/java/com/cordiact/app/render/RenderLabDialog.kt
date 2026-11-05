package com.cordiact.app.render

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cordiact.core.render.SnapshotFinding

/**
 * Developer-facing harness for the render surface.
 *
 * It exists to answer two questions by hand before any of this is wired into the message
 * stream: does the preview look right at each viewport, and does the snapshot really catch the
 * defects the checks are supposed to catch. The bundled sample is deliberately a little
 * broken — a 40dp button, a low-contrast caption and a row that overflows its card — so that
 * "检查" produces findings instead of an empty list.
 */
@Composable
fun RenderLabDialog(onDismiss: () -> Unit) {
    var viewport by remember { mutableStateOf(PreviewViewport.Phone) }
    var findings by remember { mutableStateOf<List<SnapshotFinding>>(emptyList()) }
    var status by remember { mutableStateOf("等待首帧") }
    var timeMs by remember { mutableStateOf(0f) }

    val host = rememberRenderSurfaceHost { event ->
        when (event) {
            is RenderEvent.Ready -> status = "已就绪"
            is RenderEvent.Mounted -> status = "已渲染 ${event.viewport.widthDp}dp"
            is RenderEvent.Failed -> status = "失败(${event.where}): ${event.message}"
            is RenderEvent.SnapshotReady ->
                findings = event.findings
            else -> Unit
        }
    }

    LaunchedEffect(host) {
        host.setContent(SAMPLE_HTML, SAMPLE_CSS, viewport)
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("预览器", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("关闭") }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewport = PreviewViewport.SmallPhone; host.setViewport(viewport) },
                        enabled = viewport.widthDp != 360,
                    ) { Text("360") }
                    OutlinedButton(
                        onClick = { viewport = PreviewViewport.Phone; host.setViewport(viewport) },
                        enabled = viewport.widthDp != 390,
                    ) { Text("390") }
                    OutlinedButton(
                        onClick = { viewport = PreviewViewport.Tablet; host.setViewport(viewport) },
                        enabled = viewport.widthDp != 768,
                    ) { Text("768") }
                    OutlinedButton(onClick = { host.setContent(SAMPLE_HTML, SAMPLE_CSS, viewport) }) {
                        Text("重载")
                    }
                }

                RenderSurface(
                    host = host,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .padding(vertical = 8.dp),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { host.play() }) { Text("播放") }
                    OutlinedButton(onClick = { host.pause() }) { Text("暂停") }
                    OutlinedButton(onClick = { host.requestSnapshot() }) { Text("检查") }
                }

                Text("定格 ${timeMs.toInt()} ms", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = timeMs,
                    onValueChange = {
                        timeMs = it
                        host.seek(it.toDouble())
                    },
                    valueRange = 0f..2000f,
                )

                Text(status, style = MaterialTheme.typography.labelMedium)
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (findings.isEmpty()) {
                        Text("没有发现问题", style = MaterialTheme.typography.bodySmall)
                    } else {
                        findings.forEach { finding ->
                            Text(
                                text = "• ${finding.message}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val SAMPLE_CSS = """
  :root { color-scheme: light; }
  body {
    margin: 0; padding: 16px; font-family: system-ui, sans-serif;
    background: #ffffff; color: #111111;
  }
  .card {
    width: 100%; border-radius: 16px; padding: 16px; box-sizing: border-box;
    background: linear-gradient(140deg, #eef2ff, #ffffff 60%);
    border: 1px solid #e5e7eb;
  }
  .title { font-size: 24px; font-weight: 700; margin: 0 0 6px; }
  .caption { color: #a8a8a8; font-size: 13px; margin: 0 0 16px; }
  .row { display: flex; gap: 8px; width: 720px; }
  .pill {
    display: inline-block; padding: 6px 12px; border-radius: 999px;
    background: #111111; color: #ffffff; font-size: 12px;
  }
  .cta {
    width: 40px; height: 40px; border-radius: 50%; border: 0;
    background: #4f46e5; color: #fff; font-size: 18px;
  }
  .bounce { animation: bounce 1.2s ease-in-out infinite; }
  @keyframes bounce {
    0%, 100% { transform: translateY(0); }
    50%      { transform: translateY(-10px); }
  }
"""

private const val SAMPLE_HTML = """
  <div class="card">
    <p class="title">本周回顾</p>
    <p class="caption">自动生成 · 点击查看详情</p>
    <div class="row">
      <span class="pill">12 次提交</span>
      <span class="pill">3 个发布</span>
      <span class="pill">1 次回滚</span>
      <span class="pill">7 个新任务</span>
      <span class="pill">2 个阻塞项</span>
    </div>
    <div style="height:16px"></div>
    <button class="cta bounce">→</button>
  </div>
"""

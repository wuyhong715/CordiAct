package com.cordiact.app.ui.window.preview

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Web 页面视图
 *
 * 设计要点：
 * - 支持切换「WebView 实时渲染」与「提取文本视图」
 * - 提供「在浏览器打开」按钮
 */
@Composable
fun WebPageView(
    webPage: PreviewItem.WebPage,
    onOpenInBrowser: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var renderLiveWeb by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // URL 栏 + 视图切换
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = webPage.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )

                TextButton(onClick = { renderLiveWeb = !renderLiveWeb }) {
                    Text(if (renderLiveWeb) "文本视图" else "网页渲染")
                }

                IconButton(onClick = { onOpenInBrowser(webPage.url) }) {
                    Icon(
                        imageVector = Icons.Default.OpenInBrowser,
                        contentDescription = "在浏览器打开"
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 内容预览（支持内嵌 WebView 渲染或文本摘要）
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                if (renderLiveWeb) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                webViewClient = WebViewClient()
                                settings.javaScriptEnabled = false
                                settings.domStorageEnabled = true
                                if (webPage.url.startsWith("http")) {
                                    loadUrl(webPage.url)
                                } else {
                                    loadDataWithBaseURL(null, webPage.content, "text/html", "utf-8", null)
                                }
                            }
                        },
                        update = { view ->
                            if (webPage.url.startsWith("http") && view.url != webPage.url) {
                                view.loadUrl(webPage.url)
                            }
                        }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    ) {
                        Text(
                            text = webPage.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

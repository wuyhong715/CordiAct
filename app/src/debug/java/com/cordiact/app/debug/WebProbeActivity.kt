package com.cordiact.app.debug

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

/**
 * Debug-build only: a WebView host with fixed content.
 *
 * `browser_read_screen` reads WebViews that belong to *other* apps, which have
 * no debugging socket, so the only honest way to exercise that path on a device
 * is a page we control. This activity never ships in a release build.
 */
class WebProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = false
        web.loadDataWithBaseURL(
            "https://example.test/probe",
            """
            <!doctype html>
            <html>
              <head><title>CordiAct Web Probe</title></head>
              <body>
                <h1>虚拟屏网页探针</h1>
                <p>这是用于验证浏览器联动的示例正文。</p>
                <p><a href="https://example.test/more">更多内容</a></p>
              </body>
            </html>
            """.trimIndent(),
            "text/html",
            "UTF-8",
            null,
        )
        setContentView(web)
    }
}

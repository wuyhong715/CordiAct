package com.cordiact.app.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.webkit.WebView
import com.cordiact.app.automation.AutomationRuntime
import com.cordiact.app.screen.ScreenServiceBridge
import com.cordiact.core.settings.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Passive probes only: no su launch, privilege grants or shell commands on refresh. */
class AutomationReadiness(private val context: Context, private val runtime: AutomationRuntime) {
    @Volatile private var rootResult: Boolean? = null
    fun testRoot() { rootResult = runtime.rootChannel.status().ready; check(rootResult == true) { "ROOT_UNAVAILABLE" } }
    fun rootReady() = rootResult == true
    fun probe(id: String): Readiness = try {
        when (id) {
            "screen" -> status(id, ScreenServiceBridge.connected, "屏幕无障碍已连接", "请在系统设置中开启屏幕无障碍", "ACCESSIBILITY_DISCONNECTED")
            "termux" -> {
                val installed = runCatching { context.packageManager.getPackageInfo("com.termux", 0) }.isSuccess
                val allowed = context.checkSelfPermission("com.termux.permission.RUN_COMMAND") == PackageManager.PERMISSION_GRANTED
                // A permission check is NOT proof that allow-external-apps or command execution works.
                val tested = runtime.termuxBridge.readinessVerified
                Readiness(id, id, if (installed && allowed && tested) ReadinessState.READY else ReadinessState.NEEDS_ACTION,
                    if (!installed) "请安装 Termux" else if (!allowed) "请授予运行 Termux 命令权限" else if (!tested) "请完成 Termux 连通测试" else "Termux 连通测试已通过",
                    if (tested && installed && allowed) "READY" else "TERMUX_SETUP_REQUIRED",
                    listOf(ReadinessStep("installed", "安装 Termux", installed),
                        ReadinessStep("permission", "运行命令权限", allowed),
                        ReadinessStep("execution", "外部命令与连通测试", tested)))
            }
            "browser" -> status(id, Build.VERSION.SDK_INT >= 26 && WebView.getCurrentWebViewPackage() != null,
                "WebView 已安装", "未找到 WebView 运行时", "WEBVIEW_UNAVAILABLE")
            "ssh" -> status(id, runtime.sshHostStore.list().any { it.pinnedFingerprint?.startsWith("SHA256:") == true },
                "存在已固定指纹的 SSH 主机", "请先探测并固定主机指纹", "SSH_PIN_REQUIRED")
            "root" -> status(id, runtime.rootChannel.peekReady(), "Root 检测通过", "Root 未检测或未授权；点击检测才会请求 su", "ROOT_UNVERIFIED")
            "privilege" -> status(id, runtime.shizukuChannel.status().ready || runtime.privilegeSettings.read().root && runtime.rootChannel.peekReady(), "特权通道已连接", "请启动并授权 Shizuku，或在完全权限档开启并检测 Root", "PRIVILEGE_NOT_READY")
            else -> Readiness(id, id, ReadinessState.UNAVAILABLE, "尚无就绪探测器", "PROBE_NOT_IMPLEMENTED")
        }
    } catch (failure: Exception) {
        Readiness(id, id, ReadinessState.ERROR, "就绪检查失败", "PROBE_FAILED")
    }
    suspend fun all(): List<Readiness> = withContext(Dispatchers.IO) { (IDS + "root").map(::probe) }
    private fun status(id: String, ready: Boolean, yes: String, no: String, reason: String) =
        Readiness(id, id, if (ready) ReadinessState.READY else ReadinessState.NEEDS_ACTION, if (ready) yes else no, if (ready) "READY" else reason)
    companion object { val IDS = listOf("ssh", "termux", "screen", "browser", "privilege") }
}

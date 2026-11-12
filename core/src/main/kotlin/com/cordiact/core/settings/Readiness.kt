package com.cordiact.core.settings

/** Readiness is evidence at probe time, never permission to execute a tool. No secrets. */
enum class ReadinessState { READY, NEEDS_ACTION, DISABLED, UNAVAILABLE, ERROR }
data class ReadinessStep(val id: String, val summary: String, val complete: Boolean)
data class ReadinessAction(val label: String, val route: String)
data class Readiness(
    val id: String,
    val title: String,
    val state: ReadinessState,
    val summary: String,
    val reasonCode: String,
    val steps: List<ReadinessStep> = emptyList(),
    val actions: List<ReadinessAction> = emptyList(),
)
interface ReadinessProbe {
    val id: String
    fun probe(): Readiness
}
class ReadinessRegistry(probes: List<ReadinessProbe>) {
    private val entries = probes.toList()
    init { require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate readiness probe" } }
    fun all(): List<Readiness> = entries.map { probe ->
        try {
            probe.probe().also { require(it.id == probe.id) { "Probe identity mismatch" } }
        } catch (cancelled: java.util.concurrent.CancellationException) { throw cancelled }
        catch (_: Exception) {
            // Do not leak endpoint response bodies, credentials or exception messages.
            Readiness(probe.id, probe.id, ReadinessState.ERROR, "无法读取就绪状态", "PROBE_FAILED")
        }
    }
}
/** Local checks only. No network request or decrypted credential read. */
class ConfigurationReadinessProbe(
    override val id: String,
    private val title: String,
    private val credentialIsSet: () -> Boolean,
    private val enabled: () -> Boolean = { true },
    private val route: String,
) : ReadinessProbe {
    override fun probe(): Readiness {
        val active = enabled()
        val configured = credentialIsSet()
        return Readiness(id, title,
            when { !active -> ReadinessState.DISABLED; !configured -> ReadinessState.NEEDS_ACTION; else -> ReadinessState.READY },
            when { !active -> "未启用"; !configured -> "需要配置凭据"; else -> "配置就绪 · 未检测网络连接" },
            when { !active -> "DISABLED"; !configured -> "CREDENTIAL_MISSING"; else -> "CONFIGURED_NETWORK_UNTESTED" },
            steps = listOf(ReadinessStep("credential", "配置凭据", configured), ReadinessStep("enabled", "启用能力或模型", active)),
            actions = listOf(ReadinessAction("查看配置", route)))
    }
}

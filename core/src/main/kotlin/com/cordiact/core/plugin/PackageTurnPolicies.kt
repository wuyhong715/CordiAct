package com.cordiact.core.plugin

import java.util.concurrent.locks.ReentrantLock

class PackageTurnRejected(message: String) : IllegalStateException(message)

/** Admission for user-submitted model turns, based only on input shape. */
class PackageTurnPolicies {
    private class Entry(
        val label: String,
        val source: ByteArray,
        val session: PackageScriptSession,
        val active: () -> Boolean,
    ) {
        val calls = ReentrantLock()
    }

    private val entries = mutableMapOf<PluginInstanceId, Entry>()

    fun register(context: PluginContext, manifest: ExtensionPackageManifest, runtime: PackageScriptRuntime, admitted: () -> Boolean) {
        val policy = requireNotNull(manifest.agent?.beforeTurn)
        val owner = context.id
        require(owner.pluginId == extensionPackageSpec(manifest).id && owner.workspaceId != null && owner.sessionId == null)
        require(ExtensionPackageCapability.AGENT_TURN_POLICY_PUBLISH in manifest.capabilities)
        context.scope.requireOpen()
        val entry = Entry("${manifest.id}@${manifest.version}/${policy.name}", policy.script.encodeToByteArray(),
            context.scope.own(runtime.open(owner))) { context.isContributionActive() && admitted() }
        synchronized(entries) {
            check(owner !in entries) { "Turn policy already registered" }
            require(entries.keys.count { it.workspaceId == owner.workspaceId } < 4) { "Too many workspace turn policies" }
            entries[owner] = entry
        }
        context.effect { synchronized(entries) { entries.remove(owner, entry) } }
    }

    fun check(workspaceId: String, hasText: Boolean, attachmentCount: Int) {
        require(attachmentCount >= 0)
        val policies = selected(workspaceId)
        if (policies.isEmpty()) return
        val input = """{"hasText":$hasText,"attachmentCount":$attachmentCount}""".encodeToByteArray()
        fun requireCurrent() {
            if (selected(workspaceId) != policies) throw PackageTurnRejected("回合策略已变更，请重新发送")
        }
        for (entry in policies) {
            val result = try {
                entry.calls.lockInterruptibly()
                try {
                    requireCurrent()
                    entry.session.evaluate(entry.source.copyOf(), input.copyOf())
                } finally { entry.calls.unlock() }
            } catch (withdrawn: PackageTurnRejected) {
                throw withdrawn
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PackageTurnRejected("回合策略检查已中断（${entry.label}）")
            } catch (_: Exception) {
                requireCurrent()
                throw PackageTurnRejected("回合策略执行失败（${entry.label}）")
            }
            requireCurrent()
            when {
                result.contentEquals(CONTINUE) -> Unit
                result.contentEquals(BLOCK) -> throw PackageTurnRejected("回合被插件策略拦截（${entry.label}）")
                else -> throw PackageTurnRejected("回合策略返回了无效决定（${entry.label}）")
            }
        }
    }

    private fun selected(workspaceId: String): List<Entry> = synchronized(entries) {
        entries.filterKeys { it.workspaceId == workspaceId }.values.toList()
    }.filter { it.active() }.sortedBy { it.label }

    private companion object {
        val CONTINUE = "continue".encodeToByteArray()
        val BLOCK = "block".encodeToByteArray()
    }
}

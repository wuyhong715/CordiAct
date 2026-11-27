package com.cordiact.core.plugin

import com.cordiact.core.jobs.JobKind
import com.cordiact.core.jobs.JobRegistry
import com.cordiact.core.jobs.JobSnapshot
import com.cordiact.core.jobs.JobSpec
import com.cordiact.core.jobs.JobState
import com.cordiact.core.jobs.JobSurvival
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Runs explicit user requests for enabled package workflows as ordinary workspace jobs. */
class PackageWorkflows(private val jobs: JobRegistry, private val executor: Executor) {
    class Offer internal constructor(
        val owner: PluginInstanceId,
        val title: String,
        val name: String,
        val source: String,
        val stepTitles: List<String>,
    )

    private class Entry(
        val offer: Offer,
        val workflow: ExtensionPackageWorkflow,
        val runtime: PackageScriptRuntime,
        val active: () -> Boolean,
    ) {
        var runToken: Any? = null
        var jobId: String? = null
    }

    private val lock = Any()
    private val entries = mutableMapOf<Offer, Entry>()

    fun register(context: PluginContext, manifest: ExtensionPackageManifest, runtime: PackageScriptRuntime, admitted: () -> Boolean) {
        val owner = context.id
        require(owner.pluginId == extensionPackageSpec(manifest).id && owner.workspaceId != null && owner.sessionId == null)
        require(ExtensionPackageCapability.WORKFLOW_PUBLISH in manifest.capabilities)
        context.scope.requireOpen()
        // Verify platform support at activation without running a step.
        runtime.open(owner).close()
        for (workflow in manifest.workflows) {
            val offer = Offer(owner, workflow.title, workflow.name, "${manifest.id}@${manifest.version}", workflow.steps.map { it.title })
            val entry = Entry(offer, workflow, runtime) { context.isContributionActive() && admitted() }
            synchronized(lock) { entries[offer] = entry }
            context.effect {
                synchronized(lock) { entries.remove(offer) }
                val jobId = synchronized(entry) { entry.jobId }
                if (jobId != null) jobs.kill(jobId, "Plugin workflow withdrawn")
            }
        }
    }

    fun offers(workspaceId: String, pluginId: String): List<Offer> = synchronized(lock) { entries.values.toList() }
        .filter { it.offer.owner.workspaceId == workspaceId && it.offer.owner.pluginId == pluginId && it.active() }
        .map { it.offer }

    fun start(offer: Offer, ownerSessionId: String, input: String, stillAuthorized: () -> Boolean): JobSnapshot {
        require(ownerSessionId.isNotBlank())
        val first = encodeScriptInput(input)
        val entry = synchronized(lock) { entries[offer] } ?: error("Workflow registration retired")
        synchronized(entry) {
            check(entry.runToken == null && entry.active() && stillAuthorized()) { "Workflow is unavailable or already running" }
            val token = Any()
            entry.runToken = token
            fun release() {
                synchronized(entry) {
                    if (entry.runToken === token) {
                        entry.runToken = null
                        entry.jobId = null
                    }
                }
            }
            val cancelled = AtomicBoolean()
            val taskRef = AtomicReference<FutureTask<Unit>?>()
            val handle = try {
                jobs.start(JobSpec(JobKind.PLUGIN_WORKFLOW, JobSurvival.PROCESS_LOCAL, "${offer.title} · ${offer.source}", ownerSessionId,
                    outputLimitBytes = PACKAGE_SCRIPT_MAX_TEXT_BYTES, cancel = {
                        cancelled.set(true)
                        taskRef.get()?.cancel(true)
                        release()
                    }))
            } catch (failure: Exception) {
                release()
                throw failure
            }
            if (entry.runToken === token) entry.jobId = handle.id
            fun admitted() = !cancelled.get() && entry.active() && stillAuthorized() && !Thread.currentThread().isInterrupted
            val task = FutureTask<Unit> {
                try {
                    check(admitted())
                    var value = first
                    entry.runtime.open(offer.owner).use { session ->
                        entry.workflow.steps.forEach { step ->
                            check(admitted())
                            handle.updateProgress(step.title)
                            value = session.evaluate(step.script.encodeToByteArray(), value)
                            check(value.size <= PACKAGE_SCRIPT_MAX_TEXT_BYTES)
                            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(value))
                        }
                    }
                    synchronized(entry) {
                        check(admitted())
                        handle.completeWithOutput(value.decodeToString())
                    }
                } catch (failure: Exception) {
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                    synchronized(entry) {
                        if (!cancelled.get() && handle.snapshot.state == JobState.RUNNING) {
                            val active = admitted()
                            handle.settle(if (active) JobState.FAILED else JobState.KILLED,
                                diagnostic = if (active) "Plugin workflow step failed" else "Plugin workflow withdrawn")
                        }
                    }
                } finally {
                    release()
                }
            }
            taskRef.set(task)
            if (cancelled.get()) task.cancel(false)
            try { executor.execute(task) } catch (failure: RuntimeException) {
                release()
                handle.settle(JobState.FAILED, diagnostic = "Workflow executor unavailable")
                throw failure
            }
            return handle.snapshot
        }
    }
}

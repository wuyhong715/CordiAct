package com.cordiact.app.ssh

import com.cordiact.core.execution.ExecutionResult
import com.cordiact.core.execution.SshExecParams
import com.cordiact.core.execution.SshHostConfig
import com.cordiact.core.jobs.DetachedProbeResult
import com.cordiact.core.jobs.DetachedProbeStates
import com.cordiact.core.jobs.JobKind
import com.cordiact.core.jobs.JobRegistry
import com.cordiact.core.jobs.JobSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * Answers "is that detached remote process still running?" for a job started over SSH.
 *
 * The registry knows a job is remote-detached; only the SSH backend knows how to reach the host.
 * This is that bridge, and it is deliberately conservative: every ambiguous answer stays
 * indeterminate rather than being rounded to "finished".
 *
 * Ad-hoc hosts are kept in memory only. Their credentials live in memory anyway, so a probe after
 * a restart cannot succeed, and pretending otherwise would produce a probe that always fails.
 *
 * Takes two functions rather than the plugin so the mapping is testable without a host store or a
 * network.
 */
class SshJobProbe(
    private val execute: (SshHostConfig, SshExecParams) -> ExecutionResult,
    private val resolveSavedHost: (String) -> SshHostConfig?,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {

    private val adHocHosts = ConcurrentHashMap<String, SshHostConfig>()

    /** Remembers an ad-hoc host so its job can be probed while this process lives. */
    fun rememberAdHocHost(jobId: String, config: SshHostConfig) {
        adHocHosts[jobId] = config
    }

    fun forgetHost(jobId: String) {
        adHocHosts.remove(jobId)
    }

    fun registerWith(registry: JobRegistry) {
        registry.registerProbeHandler(JobKind.SSH) { snapshot -> probe(snapshot) }
    }

    fun probe(snapshot: JobSnapshot): JobSnapshot {
        val pid = snapshot.remotePid
            ?: return indeterminate(snapshot, "no remote PID was recorded, so there is nothing to check")

        val config = resolveHost(snapshot)
            ?: return indeterminate(
                snapshot,
                "the host this job ran on is no longer known; an ad-hoc host's credentials do not " +
                    "survive a restart",
            )

        // `kill -0` alone answers EPERM for a process we do not own, which would read as "gone".
        // Falling back to `ps -p` distinguishes "not permitted" from "not there".
        val command = "if kill -0 $pid 2>/dev/null; then echo ALIVE; " +
            "elif ps -p $pid >/dev/null 2>&1; then echo ALIVE; else echo GONE; fi"

        val result = try {
            execute(config, SshExecParams(command = command, timeoutMs = PROBE_TIMEOUT_MS, background = false))
        } catch (failure: Exception) {
            return indeterminate(snapshot, failure.message ?: "the probe could not be sent")
        }

        val outcome = DetachedProbeResult.classify(
            stdout = result.stdout,
            timedOut = result.timedOut,
            failedToRun = result.stderr.isNotBlank() && result.stdout.isBlank(),
        )
        return DetachedProbeStates.apply(snapshot, outcome, clockMillis())
    }

    private fun resolveHost(snapshot: JobSnapshot): SshHostConfig? {
        val savedName = snapshot.remoteHostRef
        if (savedName != null) return resolveSavedHost(savedName)
        return adHocHosts[snapshot.id]
    }

    private fun indeterminate(snapshot: JobSnapshot, reason: String): JobSnapshot =
        DetachedProbeStates.apply(snapshot, DetachedProbeResult.Indeterminate(reason), clockMillis())

    companion object {
        /** Long enough for a slow link, short enough that a probe is never a hang. */
        const val PROBE_TIMEOUT_MS = 15_000L
    }
}

package com.cordiact.app.ssh

import com.cordiact.core.execution.ExecutionResult
import com.cordiact.core.execution.SshExecParams
import com.cordiact.core.execution.SshHostConfig
import com.cordiact.core.jobs.JobKind
import com.cordiact.core.jobs.JobSnapshot
import com.cordiact.core.jobs.JobState
import com.cordiact.core.jobs.JobSurvival
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshJobProbeTest {

    private val prod = SshHostConfig(name = "prod", host = "prod.example", user = "deploy", password = "secret")

    private fun snapshot(pid: Long? = 4242L, hostRef: String? = "prod") = JobSnapshot(
        id = "job-1",
        kind = JobKind.SSH,
        survival = JobSurvival.REMOTE_DETACHED,
        state = JobState.DETACHED_UNKNOWN,
        label = "deploy",
        ownerSessionId = "s1",
        startedAtMillis = 1_000L,
        remotePid = pid,
        remoteHostRef = hostRef,
    )

    private fun probeWith(
        result: ExecutionResult,
        saved: (String) -> SshHostConfig? = { if (it == "prod") prod else null },
        onCommand: (String) -> Unit = {},
    ) = SshJobProbe(
        execute = { _, params -> onCommand(params.command); result },
        resolveSavedHost = saved,
        clockMillis = { 7_000L },
    )

    @Test
    fun `an alive pid is reported as still running`() {
        val probed = probeWith(ExecutionResult(exitCode = 0, stdout = "ALIVE\n")).probe(snapshot())

        assertEquals(JobState.DETACHED_RUNNING, probed.state)
        assertTrue(probed.diagnostic!!.contains("still exists"))
    }

    @Test
    fun `a missing pid is reported as finished`() {
        val probed = probeWith(ExecutionResult(exitCode = 0, stdout = "GONE\n")).probe(snapshot())

        assertEquals(JobState.DETACHED_FINISHED, probed.state)
        assertTrue(probed.diagnostic!!.contains("is gone"))
    }

    @Test
    fun `the probe command tolerates a process we do not own`() {
        var command = ""
        probeWith(ExecutionResult(exitCode = 0, stdout = "ALIVE\n")) { command = it }.probe(snapshot())

        // kill -0 answers EPERM for another user's process, which would read as "gone".
        assertTrue(command.contains("kill -0 4242"))
        assertTrue(command.contains("ps -p 4242"))
    }

    @Test
    fun `a host that is no longer known is indeterminate rather than finished`() {
        val probed = probeWith(
            ExecutionResult(exitCode = 0, stdout = "GONE\n"),
            saved = { null },
        ).probe(snapshot(hostRef = "deleted-host"))

        assertEquals(JobState.DETACHED_UNKNOWN, probed.state)
        assertTrue(probed.diagnostic!!.contains("no longer known"))
    }

    @Test
    fun `an ad-hoc host works while remembered and stops working once forgotten`() {
        val probe = probeWith(
            ExecutionResult(exitCode = 0, stdout = "ALIVE\n"),
            saved = { null },
        )
        val adHoc = snapshot(hostRef = null)
        probe.rememberAdHocHost(adHoc.id, prod)

        assertEquals(JobState.DETACHED_RUNNING, probe.probe(adHoc).state)

        probe.forgetHost(adHoc.id)
        assertEquals(JobState.DETACHED_UNKNOWN, probe.probe(adHoc).state)
    }

    @Test
    fun `a job without a recorded pid cannot be checked`() {
        val probed = probeWith(ExecutionResult(exitCode = 0, stdout = "ALIVE\n")).probe(snapshot(pid = null))

        assertEquals(JobState.DETACHED_UNKNOWN, probed.state)
        assertTrue(probed.diagnostic!!.contains("no remote PID"))
    }

    @Test
    fun `a transport failure stays indeterminate`() {
        val probe = SshJobProbe(
            execute = { _, _: SshExecParams -> throw IllegalStateException("connection refused") },
            resolveSavedHost = { prod },
            clockMillis = { 7_000L },
        )

        val probed = probe.probe(snapshot())

        assertEquals(JobState.DETACHED_UNKNOWN, probed.state)
        assertTrue(probed.diagnostic!!.contains("connection refused"))
    }

    @Test
    fun `a timeout is not read as a finished process`() {
        val probed = probeWith(
            ExecutionResult(exitCode = -1, stdout = "", timedOut = true),
        ).probe(snapshot())

        assertEquals(JobState.DETACHED_UNKNOWN, probed.state)
        assertTrue(probed.diagnostic!!.contains("failed"))
    }
}

package com.cordiact.core.jobs

import com.cordiact.core.context.StandardSpillStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DetachedProbeTest {

    private fun detachedSnapshot(
        state: JobState = JobState.DETACHED_UNKNOWN,
        pid: Long? = 4242L,
        host: String? = "prod",
    ) = JobSnapshot(
        id = "job-1",
        kind = JobKind.SSH,
        survival = JobSurvival.REMOTE_DETACHED,
        state = state,
        label = "deploy",
        ownerSessionId = "s1",
        startedAtMillis = 1_000L,
        remotePid = pid,
        remoteHostRef = host,
    )

    @Test
    fun `only an explicit answer is treated as an answer`() {
        assertEquals(DetachedProbeResult.Alive, DetachedProbeResult.classify("ALIVE\n", false, false))
        assertEquals(DetachedProbeResult.Gone, DetachedProbeResult.classify("GONE\n", false, false))

        // Silence, a partial read, or a failure to run are all "I could not ask".
        assertTrue(DetachedProbeResult.classify("", false, false) is DetachedProbeResult.Indeterminate)
        assertTrue(DetachedProbeResult.classify("ALIVE", true, false) is DetachedProbeResult.Indeterminate)
        assertTrue(DetachedProbeResult.classify("", false, true) is DetachedProbeResult.Indeterminate)
    }

    @Test
    fun `an alive process becomes detached-running, a gone one detached-finished`() {
        val alive = DetachedProbeStates.apply(detachedSnapshot(), DetachedProbeResult.Alive, 5_000L)
        assertEquals(JobState.DETACHED_RUNNING, alive.state)
        assertTrue(alive.diagnostic!!.contains("still exists"))
        assertEquals(5_000L, alive.lastHeartbeatAtMillis)

        val gone = DetachedProbeStates.apply(detachedSnapshot(), DetachedProbeResult.Gone, 5_000L)
        assertEquals(JobState.DETACHED_FINISHED, gone.state)
        assertTrue(gone.diagnostic!!.contains("is gone"))
    }

    @Test
    fun `a failed probe says so instead of guessing an outcome`() {
        val unknown = DetachedProbeStates.apply(
            detachedSnapshot(),
            DetachedProbeResult.Indeterminate("the probe timed out"),
            5_000L,
        )

        assertEquals(JobState.DETACHED_UNKNOWN, unknown.state)
        assertTrue(unknown.diagnostic!!.contains("failed"))
        assertTrue(unknown.diagnostic!!.contains("may still be running"))
    }

    @Test
    fun `detached states do not hold a local slot`() {
        assertTrue(detachedSnapshot(state = JobState.DETACHED_UNKNOWN).isTerminal)
        assertTrue(detachedSnapshot(state = JobState.DETACHED_RUNNING).isTerminal)
        assertTrue(detachedSnapshot(state = JobState.DETACHED_FINISHED).isTerminal)
        assertTrue(!detachedSnapshot(state = JobState.RUNNING).isTerminal)
    }

    @Test
    fun `the registry routes probe to the registered handler and persists the answer`(@TempDir tempDir: File) {
        val store = InMemoryJobStore()
        val registry = DefaultJobRegistry(spillStore = StandardSpillStore(tempDir), jobStore = store)

        val handle = registry.start(
            JobSpec(
                kind = JobKind.SSH,
                survival = JobSurvival.REMOTE_DETACHED,
                label = "deploy",
                ownerSessionId = "s1",
                remotePid = 4242L,
                remoteHostRef = "prod",
            ),
        )
        handle.settle(JobState.DETACHED_UNKNOWN, diagnostic = "dispatched")

        registry.registerProbeHandler(JobKind.SSH) { snapshot ->
            DetachedProbeStates.apply(snapshot, DetachedProbeResult.Gone, 9_000L)
        }

        val probed = registry.probe(handle.id)

        assertEquals(JobState.DETACHED_FINISHED, probed!!.state)
        assertEquals(JobState.DETACHED_FINISHED, store.find(handle.id)!!.state, "the answer is durable")
        assertEquals("prod", store.find(handle.id)!!.remoteHostRef)
    }

    @Test
    fun `probing a local job does not reach for a remote handler`(@TempDir tempDir: File) {
        val registry = DefaultJobRegistry(spillStore = StandardSpillStore(tempDir))
        var called = false
        registry.registerProbeHandler(JobKind.SHELL) { called = true; it }

        val handle = registry.start(
            JobSpec(
                kind = JobKind.SHELL,
                survival = JobSurvival.PROCESS_LOCAL,
                label = "local",
                ownerSessionId = "s1",
            ),
        )

        val probed = registry.probe(handle.id)

        assertEquals(JobState.RUNNING, probed!!.state)
        assertTrue(!called, "a local job has nothing remote to check")
    }
}

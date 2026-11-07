package com.cordiact.core.jobs

import com.cordiact.core.context.StandardSpillStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JobStoreReconciliationTest {

    @Test
    fun `local jobs abort on restart while detached remote jobs become unknown`(@TempDir tempDir: File) {
        val store = InMemoryJobStore()
        val spillStore = StandardSpillStore(tempDir)

        // Simulate the previous process: two jobs were running when it died.
        store.insert(
            JobSnapshot(
                id = "job-local",
                kind = JobKind.TERMUX,
                survival = JobSurvival.PROCESS_LOCAL,
                state = JobState.RUNNING,
                label = "gradle build",
                ownerSessionId = "sess-1",
                startedAtMillis = 1_000L,
            ),
        )
        store.insert(
            JobSnapshot(
                id = "job-remote",
                kind = JobKind.SSH,
                survival = JobSurvival.REMOTE_DETACHED,
                state = JobState.RUNNING,
                label = "deploy worker",
                ownerSessionId = "sess-1",
                startedAtMillis = 1_100L,
                remotePid = 4242L,
            ),
        )

        val registry = DefaultJobRegistry(spillStore = spillStore, jobStore = store)
        val reconciled = registry.reconcileOnStartup()

        assertEquals(2, reconciled.size)

        val local = store.find("job-local")
        assertNotNull(local)
        assertEquals(JobState.ABORTED, local!!.state)
        assertTrue(local.diagnostic!!.contains("did not survive"))

        val remote = store.find("job-remote")
        assertNotNull(remote)
        assertEquals(JobState.DETACHED_UNKNOWN, remote!!.state)
        assertEquals(4242L, remote.remotePid)
        assertTrue(remote.diagnostic!!.contains("job_probe"))

        // Nothing is left occupying an active slot.
        assertTrue(store.listActive().isEmpty())
        assertTrue(registry.listActive().isEmpty())
    }

    @Test
    fun `settled detached jobs release the active set so the execution window can close`(@TempDir tempDir: File) {
        val spillStore = StandardSpillStore(tempDir)
        val registry = DefaultJobRegistry(spillStore = spillStore, jobStore = InMemoryJobStore())

        val handle = registry.start(
            JobSpec(
                kind = JobKind.SHELL,
                survival = JobSurvival.PROCESS_LOCAL,
                label = "one shot",
                ownerSessionId = "sess-1",
            ),
        )
        assertEquals(1, registry.listActive().size)

        // A detached dispatch has no local worker left; it must not hold the window open.
        handle.settle(JobState.DETACHED_UNKNOWN, diagnostic = "dispatched")
        assertTrue(registry.listActive().isEmpty())
        assertFalse(registry.get(handle.id)!!.isTerminal.not())
    }

    @Test
    fun `heartbeat refreshes the lease without producing output`(@TempDir tempDir: File) {
        val spillStore = StandardSpillStore(tempDir)
        var now = 5_000L
        val store = InMemoryJobStore()
        val registry = DefaultJobRegistry(
            spillStore = spillStore,
            jobStore = store,
            clockMillis = { now },
        )

        val handle = registry.start(
            JobSpec(
                kind = JobKind.SSH,
                survival = JobSurvival.PROCESS_LOCAL,
                label = "long call",
                ownerSessionId = "sess-1",
            ),
        )
        assertEquals(5_000L, store.find(handle.id)!!.lastHeartbeatAtMillis)

        now = 20_000L
        handle.heartbeat()
        assertEquals(20_000L, store.find(handle.id)!!.lastHeartbeatAtMillis)

        // Reaper sees a fresh lease and leaves the job alone.
        val reaped = JobReaper(registry, maxHeartbeatLagMillis = 15_000L, clockMillis = { now }).sweep()
        assertTrue(reaped.isEmpty())
    }
}

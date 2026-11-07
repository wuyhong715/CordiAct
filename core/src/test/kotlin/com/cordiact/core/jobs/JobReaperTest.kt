package com.cordiact.core.jobs

import com.cordiact.core.context.StandardSpillStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class JobReaperTest {

    @Test
    fun `test job reaper sweeps stalled process local jobs but preserves remote detached jobs`(@TempDir tempDir: File) {
        val spillStore = StandardSpillStore(tempDir)
        var currentTime = 10_000L
        val registry = DefaultJobRegistry(
            spillStore = spillStore,
            clockMillis = { currentTime },
        )

        val localJob = registry.start(
            JobSpec(
                kind = JobKind.SHELL,
                survival = JobSurvival.PROCESS_LOCAL,
                label = "local command",
                ownerSessionId = "sess-1",
            ),
        )

        val remoteJob = registry.start(
            JobSpec(
                kind = JobKind.SSH,
                survival = JobSurvival.REMOTE_DETACHED,
                label = "remote command",
                ownerSessionId = "sess-1",
                remotePid = 9999L,
            ),
        )

        val reaper = JobReaper(
            registry = registry,
            maxHeartbeatLagMillis = 15_000L,
            clockMillis = { currentTime },
        )

        // 1. Initial sweep: no lag, nothing reaped
        val reaped1 = reaper.sweep()
        assertTrue(reaped1.isEmpty())

        // 2. Advance time past maxHeartbeatLagMillis (20 seconds later)
        currentTime += 20_000L
        val reaped2 = reaper.sweep()

        // Local job should be killed due to expired heartbeat lease
        assertEquals(1, reaped2.size)
        assertEquals(localJob.id, reaped2[0].id)
        val killedLocal = registry.get(localJob.id)
        assertEquals(JobState.KILLED, killedLocal?.state)
        assertTrue(killedLocal?.diagnostic?.contains("Heartbeat lease expired") == true)

        // Remote job must NOT be swept as its survival is REMOTE_DETACHED
        val stillRemote = registry.get(remoteJob.id)
        assertFalse(stillRemote?.isTerminal == true)
        assertEquals(JobState.RUNNING, stillRemote?.state)
    }
}

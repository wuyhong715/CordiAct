package com.cordiact.core.jobs

import com.cordiact.core.context.StandardSpillStore
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.concurrent.thread

class JobContractsTest {

    @Test
    fun `test job registry admission quota limits`(@TempDir tempDir: File) {
        val spillStore = StandardSpillStore(tempDir)
        val registry = DefaultJobRegistry(
            spillStore = spillStore,
            maxRunningPerSession = 2,
            maxRunningGlobal = 3,
        )

        val handle1 = registry.start(
            JobSpec(
                kind = JobKind.SHELL,
                survival = JobSurvival.PROCESS_LOCAL,
                label = "task 1",
                ownerSessionId = "session_a",
            ),
        )
        val handle2 = registry.start(
            JobSpec(
                kind = JobKind.SHELL,
                survival = JobSurvival.PROCESS_LOCAL,
                label = "task 2",
                ownerSessionId = "session_a",
            ),
        )

        // Session quota reached
        assertThrows<JobAdmissionException> {
            registry.start(
                JobSpec(
                    kind = JobKind.SHELL,
                    survival = JobSurvival.PROCESS_LOCAL,
                    label = "task 3",
                    ownerSessionId = "session_a",
                ),
            )
        }

        // Another session can still start 1 job
        val handle3 = registry.start(
            JobSpec(
                kind = JobKind.SSH,
                survival = JobSurvival.REMOTE_DETACHED,
                label = "task 3",
                ownerSessionId = "session_b",
            ),
        )

        // Global limit reached (3 running)
        assertThrows<JobAdmissionException> {
            registry.start(
                JobSpec(
                    kind = JobKind.SHELL,
                    survival = JobSurvival.PROCESS_LOCAL,
                    label = "task 4",
                    ownerSessionId = "session_b",
                ),
            )
        }

        // Settling handle1 frees up slot for session_a
        handle1.settle(JobState.SUCCEEDED)
        val handle4 = registry.start(
            JobSpec(
                kind = JobKind.SHELL,
                survival = JobSurvival.PROCESS_LOCAL,
                label = "task 4",
                ownerSessionId = "session_a",
            ),
        )
        assertNotNull(handle4)
    }

    @Test
    fun `test job execution lifecycle append and read pagination`(@TempDir tempDir: File) {
        val spillStore = StandardSpillStore(tempDir)
        val registry = DefaultJobRegistry(spillStore = spillStore)

        val handle = registry.start(
            JobSpec(
                kind = JobKind.TERMUX,
                survival = JobSurvival.PROCESS_LOCAL,
                label = "build script",
                ownerSessionId = "session_test",
            ),
        )

        handle.append("BUILD_STEP_1\n")
        handle.append("BUILD_STEP_2\n")

        val read1 = registry.read(handle.id, offset = 0, limitBytes = 13)
        assertNotNull(read1)
        assertEquals("BUILD_STEP_1\n", read1!!.text)
        assertFalse(read1.eof)

        val read2 = registry.read(handle.id, offset = read1.nextOffset, limitBytes = 100)
        assertNotNull(read2)
        assertEquals("BUILD_STEP_2\n", read2!!.text)

        handle.settle(JobState.SUCCEEDED, exitCode = 0)
        val finalRead = registry.read(handle.id, offset = read1.nextOffset, limitBytes = 100)
        assertTrue(finalRead!!.eof)
        assertEquals(JobState.SUCCEEDED, finalRead.snapshot.state)
    }

    @Test
    fun `test job tools list output kill and probe`(@TempDir tempDir: File) {
        val toolRegistry = InMemoryToolDefinitionRegistry()
        val spillStore = StandardSpillStore(tempDir)
        val jobRegistry = DefaultJobRegistry(spillStore = spillStore)
        registerJobTools(toolRegistry, jobRegistry)

        val context = ToolInvocationContext(workspaceId = "ws1", sessionId = "session_model")

        // 1. job_list when empty
        val listDef = toolRegistry.get(JOB_LIST_ID)!!
        val emptyListRes = listDef.executor.invoke(context, ToolArguments())
        assertEquals(ToolValue.BooleanValue(true), emptyListRes.values["ok"])
        assertTrue((emptyListRes.values["content"] as ToolValue.StringValue).value.contains("No background jobs"))

        // 2. Start a background job
        var cancelled = false
        val handle = jobRegistry.start(
            JobSpec(
                kind = JobKind.SSH,
                survival = JobSurvival.REMOTE_DETACHED,
                label = "deploy worker",
                ownerSessionId = "session_model",
                remotePid = 12345L,
                cancel = { cancelled = true },
            ),
        )

        // 3. job_list contains job
        val listRes = listDef.executor.invoke(context, ToolArguments())
        assertTrue((listRes.values["content"] as ToolValue.StringValue).value.contains("deploy worker"))

        // 4. job_output with wait=true on running job should return status="running" without error
        val outputDef = toolRegistry.get(JOB_OUTPUT_ID)!!
        val outputRes = outputDef.executor.invoke(
            context,
            ToolArguments(
                mapOf(
                    "job_id" to ToolValue.StringValue(handle.id),
                    "wait" to ToolValue.BooleanValue(true),
                    "timeout_ms" to ToolValue.NumberValue(200.0),
                ),
            ),
        )
        assertEquals(ToolValue.BooleanValue(true), outputRes.values["ok"])
        assertEquals(ToolValue.StringValue("running"), outputRes.values["state"])
        assertTrue((outputRes.values["content"] as ToolValue.StringValue).value.contains("(No new output)"))

        // 5. Append output asynchronously
        thread {
            Thread.sleep(100)
            handle.append("Deployment successful!\n")
            handle.settle(JobState.SUCCEEDED, exitCode = 0)
        }

        val waitOutputRes = outputDef.executor.invoke(
            context,
            ToolArguments(
                mapOf(
                    "job_id" to ToolValue.StringValue(handle.id),
                    "wait" to ToolValue.BooleanValue(true),
                    "timeout_ms" to ToolValue.NumberValue(2000.0),
                ),
            ),
        )
        assertEquals(ToolValue.BooleanValue(true), waitOutputRes.values["ok"])
        assertEquals(ToolValue.StringValue("succeeded"), waitOutputRes.values["state"])
        assertTrue((waitOutputRes.values["text"] as ToolValue.StringValue).value.contains("Deployment successful!"))

        // 6. Test job_kill
        val killHandle = jobRegistry.start(
            JobSpec(
                kind = JobKind.SHELL,
                survival = JobSurvival.PROCESS_LOCAL,
                label = "long sleep",
                ownerSessionId = "session_model",
                cancel = { cancelled = true },
            ),
        )
        val killDef = toolRegistry.get(JOB_KILL_ID)!!
        val killRes = killDef.executor.invoke(
            context,
            ToolArguments(mapOf("job_id" to ToolValue.StringValue(killHandle.id))),
        )
        assertEquals(ToolValue.BooleanValue(true), killRes.values["ok"])
        assertEquals(JobState.KILLED, killHandle.snapshot.state)
        assertTrue(cancelled)

        // 7. Test job_probe
        jobRegistry.registerProbeHandler(JobKind.SSH) { snapshot ->
            snapshot.copy(diagnostic = "remote process verified active")
        }
        val probeDef = toolRegistry.get(JOB_PROBE_ID)!!
        val probeRes = probeDef.executor.invoke(
            context,
            ToolArguments(mapOf("job_id" to ToolValue.StringValue(handle.id))),
        )
        assertEquals(ToolValue.BooleanValue(true), probeRes.values["ok"])
        assertTrue((probeRes.values["content"] as ToolValue.StringValue).value.contains("verified active"))
    }
}

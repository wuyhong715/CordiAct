package com.cordiact.app.termux

import android.content.Context
import com.cordiact.core.execution.ExecutionResult
import com.cordiact.core.execution.TermuxCommandParams
import com.cordiact.core.execution.TermuxStatus
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class TermuxToolsTest {

    private class FakeTermuxBridge(context: Context) : TermuxBridge(context) {
        var fakeInstalled = true
        var fakeHasPermission = true
        var commandsRun = mutableListOf<String>()
        /** Termux session type of the last call: true means a background session. */
        var lastBackground: Boolean? = null

        override fun inspectStatus(): TermuxStatus {
            return TermuxStatus(
                isInstalled = fakeInstalled,
                hasPermission = fakeHasPermission,
                allowsExternalApps = true,
                message = if (fakeInstalled && fakeHasPermission) "OK" else "Not available",
            )
        }

        override fun executeCommand(params: TermuxCommandParams): ExecutionResult {
            commandsRun.add(params.command)
            lastBackground = params.background
            return when {
                params.command.contains("has-session") -> ExecutionResult(exitCode = 1, stdout = "")
                params.command.contains("new-session") -> ExecutionResult(exitCode = 0, stdout = "")
                params.command.contains("send-keys") -> ExecutionResult(exitCode = 0, stdout = "")
                params.command.contains("capture-pane") -> ExecutionResult(exitCode = 0, stdout = "terminal output\nprompt$ ")
                params.command.contains("kill-session") -> ExecutionResult(exitCode = 0, stdout = "")
                params.command.contains("list-sessions") -> ExecutionResult(exitCode = 0, stdout = "s1: 1 windows\n")
                else -> ExecutionResult(exitCode = 0, stdout = "output of ${params.command}")
            }
        }
    }

    private val dummyContext = ToolInvocationContext(workspaceId = "test-ws")

    @Test
    fun `test termux status inspection`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val fakeBridge = FakeTermuxBridge(context)
        val plugin = TermuxPlugin(fakeBridge)
        val toolMap = termuxTools(plugin).associateBy { it.descriptor.id }

        val res = toolMap["termux_status"]!!.executor.invoke(dummyContext, ToolArguments())
        assertTrue((res.values["ok"] as ToolValue.BooleanValue).value)
        assertTrue((res.values["installed"] as ToolValue.BooleanValue).value)
        assertTrue((res.values["has_permission"] as ToolValue.BooleanValue).value)
        assertTrue((res.values["is_available"] as ToolValue.BooleanValue).value)
    }

    @Test
    fun `test termux run command execution`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val fakeBridge = FakeTermuxBridge(context)
        val plugin = TermuxPlugin(fakeBridge)
        val toolMap = termuxTools(plugin).associateBy { it.descriptor.id }

        val res = toolMap["termux_run_command"]!!.executor.invoke(
            dummyContext,
            ToolArguments(
                linkedMapOf(
                    "command" to ToolValue.StringValue("python3 -c 'print(1+1)'"),
                )
            )
        )
        assertTrue((res.values["ok"] as ToolValue.BooleanValue).value)
        assertEquals(0, (res.values["exit_code"] as ToolValue.NumberValue).value.toInt())
        assertTrue((res.values["stdout"] as ToolValue.StringValue).value.contains("output of python3"))
        assertEquals(1, fakeBridge.commandsRun.size)
    }

    @Test
    fun `test termux session full lifecycle`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val fakeBridge = FakeTermuxBridge(context)
        val plugin = TermuxPlugin(fakeBridge)
        val toolMap = termuxTools(plugin).associateBy { it.descriptor.id }

        // 1. Start session
        val startRes = toolMap["termux_session"]!!.executor.invoke(
            dummyContext,
            ToolArguments(
                linkedMapOf(
                    "session_name" to ToolValue.StringValue("interactive1"),
                    "action" to ToolValue.StringValue("start"),
                )
            )
        )
        assertTrue((startRes.values["ok"] as ToolValue.BooleanValue).value)
        assertTrue((startRes.values["active"] as ToolValue.BooleanValue).value)

        // 2. Send input with pattern match
        val sendRes = toolMap["termux_session"]!!.executor.invoke(
            dummyContext,
            ToolArguments(
                linkedMapOf(
                    "session_name" to ToolValue.StringValue("interactive1"),
                    "action" to ToolValue.StringValue("send"),
                    "input" to ToolValue.StringValue("echo hello"),
                    "wait_for_pattern" to ToolValue.StringValue("prompt\\$ "),
                    "timeout_ms" to ToolValue.NumberValue(2000.0),
                )
            )
        )
        assertTrue((sendRes.values["ok"] as ToolValue.BooleanValue).value)
        assertTrue((sendRes.values["matched"] as ToolValue.BooleanValue).value)
        assertTrue((sendRes.values["screen_output"] as ToolValue.StringValue).value.contains("prompt$ "))

        // 3. Kill session
        val killRes = toolMap["termux_session"]!!.executor.invoke(
            dummyContext,
            ToolArguments(
                linkedMapOf(
                    "session_name" to ToolValue.StringValue("interactive1"),
                    "action" to ToolValue.StringValue("kill"),
                )
            )
        )
        assertTrue((killRes.values["ok"] as ToolValue.BooleanValue).value)
        assertFalse((killRes.values["active"] as ToolValue.BooleanValue).value)
    }

    // --- Termux session type vs. CordiAct job: two different questions ------------------------

    @Test
    fun `background selects the Termux session type and defaults to a background session`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val fakeBridge = FakeTermuxBridge(context)
        val tool = termuxTools(TermuxPlugin(fakeBridge)).associateBy { it.descriptor.id }
            .getValue("termux_run_command")

        // Default: a background session. A foreground one would open the Termux UI and hand back
        // a transcript with prompt noise instead of the command's own output.
        tool.executor.invoke(dummyContext, ToolArguments(mapOf("command" to ToolValue.StringValue("echo hi"))))
        assertEquals(true, fakeBridge.lastBackground)

        tool.executor.invoke(
            dummyContext,
            ToolArguments(
                mapOf(
                    "command" to ToolValue.StringValue("echo hi"),
                    "background" to ToolValue.BooleanValue(false),
                ),
            ),
        )
        assertEquals(false, fakeBridge.lastBackground)
    }

    @Test
    fun `detach returns a job id immediately and runs the command as a background session`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val fakeBridge = FakeTermuxBridge(context)
        val jobRegistry = com.cordiact.core.jobs.DefaultJobRegistry(
            spillStore = com.cordiact.core.context.StandardSpillStore(context.cacheDir.resolve("spills")),
            jobStore = com.cordiact.core.jobs.InMemoryJobStore(),
        )
        val tool = termuxTools(TermuxPlugin(fakeBridge), jobRegistry).associateBy { it.descriptor.id }
            .getValue("termux_run_command")

        val res = tool.executor.invoke(
            dummyContext.copy(sessionId = "s1"),
            ToolArguments(
                mapOf(
                    "command" to ToolValue.StringValue("sleep 90"),
                    "detach" to ToolValue.BooleanValue(true),
                ),
            ),
        )

        val jobId = (res.values["job_id"] as ToolValue.StringValue).value
        assertTrue(jobId.isNotBlank())
        assertEquals(ToolValue.StringValue("running"), res.values["state"])
        // The job exists and is registered even before the bridge answers.
        assertEquals(com.cordiact.core.jobs.JobKind.TERMUX, jobRegistry.get(jobId)!!.kind)

        // The detached worker must still ask Termux for a background session: a foreground one
        // would pop the terminal UI in the user's face while the agent works.
        val deadline = System.currentTimeMillis() + 5_000
        while (fakeBridge.commandsRun.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(true, fakeBridge.lastBackground)
    }
}

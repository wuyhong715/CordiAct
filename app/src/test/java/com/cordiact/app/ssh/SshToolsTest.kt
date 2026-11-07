package com.cordiact.app.ssh

import com.cordiact.core.execution.ExecutionResult
import com.cordiact.core.execution.SshExecParams
import com.cordiact.core.execution.SshExecutionService
import com.cordiact.core.execution.SshFileResult
import com.cordiact.core.execution.SshHostConfig
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SshToolsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeSshService : SshExecutionService {
        var lastCommand: String? = null
        var lastStdin: String? = null

        override fun execute(config: SshHostConfig, params: SshExecParams): ExecutionResult {
            lastCommand = params.command
            lastStdin = params.stdin
            return ExecutionResult(
                exitCode = 0,
                stdout = "output of ${params.command}",
                stderr = "",
            )
        }

        override fun testConnection(config: SshHostConfig, timeoutMs: Long): Pair<Boolean, String> {
            return true to "Connected"
        }

        override fun upload(config: SshHostConfig, localBytes: ByteArray, remotePath: String): SshFileResult {
            return SshFileResult(success = true, path = remotePath, bytesTransferred = localBytes.size.toLong())
        }

        override fun download(config: SshHostConfig, remotePath: String, maxBytes: Long): Pair<ByteArray?, String?> {
            return "hello world".toByteArray(Charsets.UTF_8) to null
        }
    }

    private val dummyContext = ToolInvocationContext(workspaceId = "test-ws")

    @Test
    fun `test save list delete and exec saved host flow`() {
        val file = tempFolder.newFile("hosts.json")
        val store = SshHostStore(file)
        val fakeService = FakeSshService()
        val plugin = SshPlugin(service = fakeService, hostStore = store)
        val toolMap = sshTools(plugin).associateBy { it.descriptor.id }

        // 1. Save host
        val saveResult = toolMap["ssh_save_host"]!!.executor.invoke(
            dummyContext,
            ToolArguments(
                linkedMapOf(
                    "name" to ToolValue.StringValue("vps1"),
                    "host" to ToolValue.StringValue("192.168.1.100"),
                    "port" to ToolValue.NumberValue(22.0),
                    "user" to ToolValue.StringValue("root"),
                    "password" to ToolValue.StringValue("pass123"),
                )
            )
        )
        assertTrue((saveResult.values["ok"] as ToolValue.BooleanValue).value)
        assertEquals("vps1", (saveResult.values["name"] as ToolValue.StringValue).value)

        // 2. List hosts
        val listResult = toolMap["ssh_list_hosts"]!!.executor.invoke(
            dummyContext,
            ToolArguments()
        )
        assertTrue((listResult.values["ok"] as ToolValue.BooleanValue).value)
        val hosts = (listResult.values["hosts"] as ToolValue.ArrayValue).items
        assertEquals(1, hosts.size)

        // 3. Exec saved
        val execResult = toolMap["ssh_exec_saved"]!!.executor.invoke(
            dummyContext,
            ToolArguments(
                linkedMapOf(
                    "name" to ToolValue.StringValue("vps1"),
                    "command" to ToolValue.StringValue("uname -a"),
                )
            )
        )
        assertTrue((execResult.values["ok"] as ToolValue.BooleanValue).value)
        assertEquals("output of uname -a", (execResult.values["stdout"] as ToolValue.StringValue).value)
        assertEquals("uname -a", fakeService.lastCommand)

        // 4. Delete host
        val delResult = toolMap["ssh_delete_host"]!!.executor.invoke(
            dummyContext,
            ToolArguments(linkedMapOf("name" to ToolValue.StringValue("vps1")))
        )
        assertTrue((delResult.values["ok"] as ToolValue.BooleanValue).value)
        assertTrue((delResult.values["deleted"] as ToolValue.BooleanValue).value)

        val emptyListResult = toolMap["ssh_list_hosts"]!!.executor.invoke(dummyContext, ToolArguments())
        val emptyHosts = (emptyListResult.values["hosts"] as ToolValue.ArrayValue).items
        assertEquals(0, emptyHosts.size)
    }
}

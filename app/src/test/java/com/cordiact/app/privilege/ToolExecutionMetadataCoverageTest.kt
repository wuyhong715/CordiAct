package com.cordiact.app.privilege

import com.cordiact.app.ssh.SshHostStore
import com.cordiact.app.ssh.SshPlugin
import com.cordiact.app.ssh.sshTools
import com.cordiact.app.termux.TermuxBridge
import com.cordiact.app.termux.TermuxPlugin
import com.cordiact.app.termux.termuxTools
import com.cordiact.core.automation.PrivilegeLevel
import com.cordiact.core.automation.PrivilegeStatus
import com.cordiact.core.automation.PrivilegedChannel
import com.cordiact.core.automation.ShellResult
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolExecutionBackend
import com.cordiact.core.tool.ToolExecutionInputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ToolExecutionMetadataCoverageTest {

    @Test
    fun everyToolThatAcceptsShellInputDeclaresExecutionMetadata() {
        val definitions = allExecutionToolDefinitions()
        val shellInputNames = setOf("command", "input", "stdin")
        val offenders = definitions.filter { definition ->
            definition.descriptor.execution == null &&
                definition.descriptor.inputSchema.parameters.any { it.name in shellInputNames }
        }
        assertEquals(emptyList<ToolDefinition>(), offenders)
    }

    @Test
    fun everyDeclaredActionReferencesRealParametersAndRequiresApproval() {
        allExecutionToolDefinitions().forEach { definition ->
            val metadata = definition.descriptor.execution ?: return@forEach
            val parameterNames = definition.descriptor.inputSchema.parameters.map { it.name }.toSet()
            metadata.actions.forEach { action ->
                assertTrue(
                    "${definition.descriptor.id}/${action.action} input parameter missing from schema",
                    action.inputParameter in parameterNames,
                )
                (action.alternativeInputParameters + action.supplementalInputParameters).forEach { name ->
                    assertTrue(
                        "${definition.descriptor.id}/${action.action} references unknown parameter $name",
                        parameterNames.contains(name),
                    )
                }
                if (action.inputKind == ToolExecutionInputKind.COMMAND ||
                    action.inputKind == ToolExecutionInputKind.INTERACTIVE_INPUT
                ) {
                    assertFalse(
                        "${definition.descriptor.id}/${action.action} must not auto-approve arbitrary input",
                        action.autoApprovable,
                    )
                }
            }
        }
    }

    @Test
    fun privilegeSshTermuxExecutionToolsAreAllCovered() {
        val covered = allExecutionToolDefinitions()
            .filter { it.descriptor.execution != null }
            .map { it.descriptor.id }
            .toSet()
        val expected = setOf(
            "priv_exec", "priv_shell", "priv_root",
            "ssh_exec", "ssh_exec_saved", "ssh_upload",
            "termux_run_command", "termux_session",
        )
        assertEquals(expected, covered)
        assertEquals(
            setOf(
                ToolExecutionBackend.PRIVILEGED_SHELL,
                ToolExecutionBackend.PRIVILEGED_ROOT,
                ToolExecutionBackend.SSH,
                ToolExecutionBackend.TERMUX,
            ),
            allExecutionToolDefinitions().flatMap { it.descriptor.execution?.actions.orEmpty() }
                .map { it.backend }.toSet(),
        )
    }

    private fun allExecutionToolDefinitions(): List<ToolDefinition> {
        val context = RuntimeEnvironment.getApplication()
        val privilege = PrivilegeService(
            PrivilegeRouter(FakeChannel(PrivilegeLevel.SHELL), FakeChannel(PrivilegeLevel.ROOT)),
        )
        val ssh = SshPlugin(hostStore = SshHostStore(File(context.cacheDir, "ssh-hosts-test.json")))
        val termux = TermuxPlugin(TermuxBridge(context) { -1 to "unavailable in test" })
        return privilegeTools(privilege) + sshTools(ssh) + termuxTools(termux)
    }

    private class FakeChannel(private val level: PrivilegeLevel) : PrivilegedChannel {
        override fun status(): PrivilegeStatus = PrivilegeStatus(level)
        override fun exec(command: String, timeoutMs: Long): ShellResult = ShellResult(0, "", "")
    }
}

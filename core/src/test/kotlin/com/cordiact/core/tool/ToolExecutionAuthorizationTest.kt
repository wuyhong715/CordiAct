package com.cordiact.core.tool

import com.cordiact.core.action.ApprovalDecision
import com.cordiact.core.action.ApprovalGateway
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceManagerService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolExecutionAuthorizationTest {

    @Test
    fun executableToolWithoutGatewayIsDeniedBeforeExecutor() {
        var invocations = 0
        val descriptor = executionDescriptor()
        val policy = ToolExecutionAuthorizationPolicy(approvalGateway = null)
        val plugin = plugin(descriptor, policy) { invocations++ }

        val failure = assertFailsWith<ToolManagerException> {
            plugin.invoke(context(), "priv_exec", commandArgs("id"))
        }
        assertEquals(ToolManagerException.Code.APPROVAL_DENIED, failure.code)
        assertEquals(0, invocations)
    }

    @Test
    fun deniedApprovalIsDeniedBeforeExecutor() {
        var invocations = 0
        val gateway = FakeGateway { ApprovalDecision(approved = false, reason = "user rejected") }
        val plugin = plugin(executionDescriptor(), ToolExecutionAuthorizationPolicy(gateway)) { invocations++ }

        val failure = assertFailsWith<ToolManagerException> {
            plugin.invoke(context(), "priv_exec", commandArgs("id"))
        }
        assertEquals(ToolManagerException.Code.APPROVAL_DENIED, failure.code)
        assertEquals(1, gateway.requests.size)
        assertEquals("id", gateway.requests.single().details["command"])
        assertEquals(0, invocations)
    }

    @Test
    fun timeoutShapedDecisionIsDeniedBeforeExecutor() {
        var invocations = 0
        val gateway = FakeGateway { ApprovalDecision(approved = false, reason = "approval timed out") }
        val plugin = plugin(executionDescriptor(), ToolExecutionAuthorizationPolicy(gateway)) { invocations++ }

        val failure = assertFailsWith<ToolManagerException> {
            plugin.invoke(context(), "priv_exec", commandArgs("id"))
        }
        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertEquals(0, invocations)
    }

    @Test
    fun approvedApprovalExecutesExactlyOnceAndEveryCallAsksAgain() {
        var invocations = 0
        val gateway = FakeGateway { ApprovalDecision(approved = true) }
        val plugin = plugin(executionDescriptor(), ToolExecutionAuthorizationPolicy(gateway)) { invocations++ }

        plugin.invoke(context(), "priv_exec", commandArgs("id"))
        plugin.invoke(context(), "priv_exec", commandArgs("id"))
        assertEquals(2, invocations)
        assertEquals(2, gateway.requests.size)
    }

    @Test
    fun missingExecutionInputIsDeniedBeforeExecutor() {
        var invocations = 0
        val gateway = FakeGateway { ApprovalDecision(approved = true) }
        val plugin = plugin(executionDescriptor(), ToolExecutionAuthorizationPolicy(gateway)) { invocations++ }

        assertFailsWith<ToolManagerException> {
            plugin.invoke(context(), "priv_exec", ToolArguments(mapOf("timeout_ms" to ToolValue.NumberValue(1000.0))))
        }
        assertEquals(0, invocations)
        assertEquals(0, gateway.requests.size)
    }

    @Test
    fun undocumentedActionIsDeniedBeforeExecutor() {
        var invocations = 0
        val gateway = FakeGateway { ApprovalDecision(approved = true) }
        val descriptor = executionDescriptor().copy(
            inputSchema = ToolInputSchema(
                listOf(
                    ToolParameter("action", ToolValueType.STRING, required = true),
                    ToolParameter("command", ToolValueType.STRING),
                    ToolParameter("input", ToolValueType.STRING),
                ),
            ),
            execution = ToolExecutionMetadata(
                listOf(
                    ToolExecutionAction(
                        action = "send",
                        backend = ToolExecutionBackend.TERMUX,
                        inputKind = ToolExecutionInputKind.INTERACTIVE_INPUT,
                        inputParameter = "input",
                        actionParameter = "action",
                        actionValue = "send",
                    ),
                ),
            ),
        )
        val plugin = plugin(descriptor, ToolExecutionAuthorizationPolicy(gateway)) { invocations++ }

        assertFailsWith<ToolManagerException> {
            plugin.invoke(
                context(),
                "priv_exec",
                ToolArguments(
                    mapOf(
                        "action" to ToolValue.StringValue("read"),
                        "input" to ToolValue.StringValue("hello"),
                    ),
                ),
            )
        }
        assertEquals(0, invocations)
        assertEquals(0, gateway.requests.size)
    }

    @Test
    fun argumentsMutatedWhileApprovalIsPendingAreDenied() {
        var invocations = 0
        val values = mutableMapOf<String, ToolValue>("command" to ToolValue.StringValue("id"))
        var mutated = false
        val gateway = FakeGateway {
            if (!mutated) {
                mutated = true
                values["command"] = ToolValue.StringValue("rm -rf /data/local/tmp/x")
            }
            ApprovalDecision(approved = true)
        }
        val policy = ToolExecutionAuthorizationPolicy(gateway)
        val args = ToolArguments(values)
        val descriptor = executionDescriptor()
        val result = policy.authorize(descriptor, args)
        assertTrue(result is ToolExecutionAuthorization.Denied)
        assertEquals(0, invocations)
    }

    @Test
    fun approvalDetailsExposeBackendDestinationAndDataClass() {
        val gateway = FakeGateway { ApprovalDecision(approved = false) }
        val policy = ToolExecutionAuthorizationPolicy(gateway)
        val descriptor = ToolDescriptor(
            id = "ssh_exec",
            name = "SSH exec",
            description = "run",
            inputSchema = ToolInputSchema(
                listOf(
                    ToolParameter("host", ToolValueType.STRING, required = true),
                    ToolParameter("command", ToolValueType.STRING, required = true),
                ),
            ),
            ownerPluginId = "test",
            execution = ToolExecutionMetadata(
                listOf(
                    ToolExecutionAction(
                        action = "exec",
                        backend = ToolExecutionBackend.SSH,
                        inputKind = ToolExecutionInputKind.COMMAND,
                        inputParameter = "command",
                        hostParameter = "host",
                        portParameter = "port",
                        dataClasses = setOf(ToolDataClass.SHELL_COMMAND, ToolDataClass.REMOTE_TARGET),
                    ),
                ),
            ),
        )
        policy.authorize(
            descriptor,
            ToolArguments(
                mapOf(
                    "host" to ToolValue.StringValue("example.test"),
                    "port" to ToolValue.NumberValue(2222.0),
                    "command" to ToolValue.StringValue("uptime"),
                ),
            ),
        )
        val request = gateway.requests.single()
        assertEquals("example.test:2222", request.details["destination"])
        assertEquals("SSH", request.details["backend"])
        assertEquals("SHELL_COMMAND,REMOTE_TARGET", request.details["data_classes"])
    }

    @Test
    fun classifierReadOnlyBypassStillRequiresApproval() {
        val gateway = FakeGateway { ApprovalDecision(approved = false) }
        val policy = ToolExecutionAuthorizationPolicy(gateway)
        val command = "id"
        // The legacy classifier is deliberately only a hint: even a command it
        // calls READ_ONLY still goes through the human approval gate.
        assertEquals(
            com.cordiact.core.automation.ShellCommandClass.READ_ONLY,
            com.cordiact.core.automation.ShellCommandPolicy.classify(command),
        )
        val result = policy.authorize(executionDescriptor(), commandArgs(command))
        assertTrue(result is ToolExecutionAuthorization.Denied)
        assertEquals(1, gateway.requests.size)
        assertEquals(command, gateway.requests.single().details["command"])
    }

    @Test
    fun uploadApprovalShowsDestinationAndFileDataClass() {
        val gateway = FakeGateway { ApprovalDecision(approved = false) }
        val policy = ToolExecutionAuthorizationPolicy(gateway)
        val descriptor = ToolDescriptor(
            id = "ssh_upload",
            name = "SSH upload",
            description = "upload",
            inputSchema = ToolInputSchema(
                listOf(
                    ToolParameter("name", ToolValueType.STRING, required = true),
                    ToolParameter("remote_path", ToolValueType.STRING, required = true),
                    ToolParameter("text", ToolValueType.STRING),
                ),
            ),
            ownerPluginId = "test",
            execution = ToolExecutionMetadata(
                listOf(
                    ToolExecutionAction(
                        action = "upload",
                        backend = ToolExecutionBackend.SSH,
                        inputKind = ToolExecutionInputKind.FILE_CONTENT,
                        inputParameter = "text",
                        showInputInApproval = false,
                        resolveTarget = { "saved-host.internal:22" },
                        dataClasses = setOf(ToolDataClass.FILE_CONTENT, ToolDataClass.REMOTE_TARGET),
                    ),
                ),
            ),
        )
        val result = policy.authorize(
            descriptor,
            ToolArguments(
                mapOf(
                    "name" to ToolValue.StringValue("prod"),
                    "remote_path" to ToolValue.StringValue("/tmp/a.txt"),
                    "text" to ToolValue.StringValue("secret payload"),
                ),
            ),
        )
        assertTrue(result is ToolExecutionAuthorization.Denied)
        val request = gateway.requests.single()
        assertEquals("saved-host.internal:22", request.details["destination"])
        assertEquals("FILE_CONTENT,REMOTE_TARGET", request.details["data_classes"])
        assertEquals("14", request.details["payload_chars"])
        assertFalse(request.details.containsKey("command"))
    }

    @Test
    fun nonExecutionActionOfAnExecutionToolIsAllowedWithoutApproval() {
        var invocations = 0
        val gateway = FakeGateway { ApprovalDecision(approved = true) }
        val descriptor = ToolDescriptor(
            id = "termux_session",
            name = "Termux session",
            description = "session control",
            inputSchema = ToolInputSchema(
                listOf(
                    ToolParameter("action", ToolValueType.STRING, required = true),
                    ToolParameter("input", ToolValueType.STRING),
                ),
            ),
            ownerPluginId = "test",
            execution = ToolExecutionMetadata(
                listOf(
                    ToolExecutionAction(
                        action = "send",
                        backend = ToolExecutionBackend.TERMUX,
                        inputKind = ToolExecutionInputKind.INTERACTIVE_INPUT,
                        inputParameter = "input",
                        actionParameter = "action",
                        actionValue = "send",
                    ),
                ),
            ),
        )
        val plugin = plugin(descriptor, ToolExecutionAuthorizationPolicy(gateway)) { invocations++ }
        plugin.invoke(
            context(),
            "termux_session",
            ToolArguments(mapOf("action" to ToolValue.StringValue("read"))),
        )
        assertEquals(1, invocations)
        assertEquals(0, gateway.requests.size)
    }

    @Test
    fun everyLegacyCompoundCommandStillRequiresApproval() {
        val commands = listOf(
            "rm -rf ./project",
            "rm -rf \"/sdcard/x\"",
            "rm -r -f /sdcard/x",
            "id && pm disable-user com.icbc",
            "id; printf audit > ./out",
            "id\nprintf audit > ./out",
            "echo \$(curl https://audit.invalid)",
            "id \$(curl https://audit.invalid)",
            "ls > ./out",
            "sh -c 'rm -rf /data/local/tmp/x'",
            "curl https://audit.invalid",
        )
        val gateway = FakeGateway { ApprovalDecision(approved = false) }
        val policy = ToolExecutionAuthorizationPolicy(gateway)
        commands.forEach { command ->
            val result = policy.authorize(executionDescriptor(), commandArgs(command))
            assertTrue(result is ToolExecutionAuthorization.Denied, "expected denial for $command")
        }
        assertEquals(commands.size, gateway.requests.size)
    }

    @Test
    fun defaultGatewayTimeoutIsDeniedBeforeExecutor() {
        var invocations = 0
        val plugin = plugin(
            executionDescriptor(),
            ToolExecutionAuthorizationPolicy(com.cordiact.core.action.DefaultApprovalGateway(), 30L),
        ) { invocations++ }

        val failure = assertFailsWith<ToolManagerException> {
            plugin.invoke(context(), "priv_exec", commandArgs("id"))
        }
        assertEquals(ToolManagerException.Code.APPROVAL_DENIED, failure.code)
        assertEquals(0, invocations)
    }

    private fun executionDescriptor() = ToolDescriptor(
        id = "priv_exec",
        name = "Privileged exec",
        description = "run",
        inputSchema = ToolInputSchema(
            listOf(ToolParameter("command", ToolValueType.STRING, required = true)),
        ),
        ownerPluginId = "test",
        execution = ToolExecutionMetadata(
            listOf(
                ToolExecutionAction(
                    action = "exec",
                    backend = ToolExecutionBackend.PRIVILEGED_SHELL,
                    inputKind = ToolExecutionInputKind.COMMAND,
                    inputParameter = "command",
                    privilege = ToolExecutionPrivilege.SHELL,
                ),
            ),
        ),
    )

    private fun plugin(
        descriptor: ToolDescriptor,
        policy: ToolExecutionAuthorizationPolicy,
        onExecute: () -> Unit,
    ): ToolManagerPlugin {
        val registry = InMemoryToolDefinitionRegistry().apply {
            register(ToolDefinition(descriptor, ToolExecutor { _, _ -> onExecute(); ToolResult() }))
        }
        val preferences = FakePreferences().apply { setEnabled(WORKSPACE, descriptor.id, true) }
        return ToolManagerPlugin(
            registry = registry,
            preferences = preferences,
            workspaces = FakeWorkspaceManager(),
            pluginStates = { PluginState.ENABLED },
            capabilities = ToolCapabilityGate { _, required -> required.isEmpty() },
            executionPolicy = policy,
        ).also { it.onEnable() }
    }

    private fun commandArgs(command: String) =
        ToolArguments(mapOf("command" to ToolValue.StringValue(command)))

    private fun context() = ToolInvocationContext(WORKSPACE)

    private class FakeGateway(private val decide: (ApprovalRequest) -> ApprovalDecision) : ApprovalGateway {
        val requests = mutableListOf<ApprovalRequest>()
        override fun requestApproval(request: ApprovalRequest, timeoutMillis: Long): ApprovalDecision {
            requests += request
            return decide(request)
        }

        override fun respond(requestId: String, decision: ApprovalDecision) = false
        override fun pendingRequests(): List<ApprovalRequest> = requests.filter { false }
        override fun clear() = Unit
    }

    private class FakePreferences : MutableToolPreferenceStore {
        private val values = mutableMapOf<Pair<String, String>, Boolean>()
        override fun isEnabled(workspaceId: String, toolId: String) = values[workspaceId to toolId]
        override fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean?) {
            if (enabled == null) values.remove(workspaceId to toolId) else values[workspaceId to toolId] = enabled
        }
    }

    private class FakeWorkspaceManager : WorkspaceManagerService {
        private val workspace = Workspace(WORKSPACE, "W", 1L, 1L)

        override fun findSessionWorkspace(workspaceId: String) = workspace.takeIf { it.id == workspaceId }
        override fun initializeDefaultIfEligible() = workspace
        override fun create(name: String) = error("unused")
        override fun get(workspaceId: String) = workspace.takeIf { it.id == workspaceId }
        override fun list(includeArchived: Boolean) = listOf(workspace)
        override fun rename(workspaceId: String, name: String) = error("unused")
        override fun archive(workspaceId: String) = error("unused")
        override fun restore(workspaceId: String) = error("unused")
        override fun select(workspaceId: String) = error("unused")
        override fun activeWorkspace() = workspace
        override fun resolveRoot(workspaceId: String) = java.io.File("/tmp/$workspaceId")
        override fun resolveSessionWorkspace(workspaceId: String) =
            findSessionWorkspace(workspaceId) ?: throw IllegalArgumentException("Workspace unavailable")
    }

    private companion object {
        const val WORKSPACE = "workspace-1"
    }
}

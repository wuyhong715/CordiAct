package com.cordiact.app.ssh

import android.util.Base64
import com.cordiact.core.automation.AutomationCapabilities
import com.cordiact.core.automation.AutomationPlugins
import com.cordiact.core.automation.ShellCommandPolicy
import com.cordiact.core.execution.SshExecParams
import com.cordiact.core.execution.SshHostConfig
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDefinitionRegistry
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolExecutionAction
import com.cordiact.core.tool.ToolExecutionBackend
import com.cordiact.core.tool.ToolExecutionInputKind
import com.cordiact.core.tool.ToolExecutionMetadata
import com.cordiact.core.tool.ToolExecutionPrivilege
import com.cordiact.core.tool.ToolDataClass
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

private const val TIMEOUT_HOST_OPS = 5_000L
private const val TIMEOUT_DEFAULT_EXEC = 60_000L
private const val TIMEOUT_SFTP = 120_000L

fun sshTools(
    plugin: SshPlugin,
    jobRegistry: com.cordiact.core.jobs.JobRegistry? = null,
    probe: SshJobProbe? = null,
): List<ToolDefinition> = listOf(
    saveHostTool(plugin),
    listHostsTool(plugin),
    deleteHostTool(plugin),
    probeTool(plugin),
    execTool(plugin, jobRegistry, probe),
    execSavedTool(plugin, jobRegistry, probe),
    uploadTool(plugin),
    downloadTool(plugin),
    openTunnelTool(plugin),
    closeTunnelTool(plugin),
    listTunnelsTool(plugin),
).map { definition ->
    definition.copy(executor = { context, args ->
        try { definition.executor.invoke(context, args) }
        catch (failure: Exception) {
            if (failure is java.util.concurrent.CancellationException) throw failure
            error("ssh_operation_failed", "SSH operation failed; verify host storage, credentials and pinned fingerprint in settings")
        }
    })
}

fun registerSshTools(
    registry: ToolDefinitionRegistry,
    plugin: SshPlugin,
    jobRegistry: com.cordiact.core.jobs.JobRegistry? = null,
    probe: SshJobProbe? = null,
) {
    sshTools(plugin, jobRegistry, probe).forEach { def ->
        registry.unregister(def.descriptor.id)
        registry.register(def)
    }
}

private fun saveHostTool(plugin: SshPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_save_host",
        name = "ssh_save_host",
        description = "Persist SSH host credentials under a short name for later use with ssh_exec_saved, ssh_upload, ssh_download.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("name", ToolValueType.STRING, "Unique short name for this host.", required = true, maxLength = 64),
                param("host", ToolValueType.STRING, "Hostname or IP address.", required = true, maxLength = 256),
                param("port", ToolValueType.INTEGER, "SSH port (default: 22).", minimum = 1.0, maximum = 65535.0),
                param("user", ToolValueType.STRING, "SSH username.", required = true, maxLength = 64),
                param("password", ToolValueType.STRING, "SSH password (use if no private_key).", maxLength = 512),
                param("private_key", ToolValueType.STRING, "Full OpenSSH/PEM private key content."),
                param("passphrase", ToolValueType.STRING, "Passphrase for the private key, if encrypted."),
                param("pinned_fingerprint", ToolValueType.STRING, "Expected SHA256 host key fingerprint for key pinning."),
            ),
        ),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_EXEC),
        timeoutMs = TIMEOUT_HOST_OPS,
        concurrencySafe = true,
    ),
    executor = { _, args ->
        val name = args.string("name") ?: return@ToolDefinition error("missing_arg", "name is required")
        val host = args.string("host") ?: return@ToolDefinition error("missing_arg", "host is required")
        val port = args.int("port") ?: 22
        val user = args.string("user") ?: return@ToolDefinition error("missing_arg", "user is required")
        val password = args.string("password")
        val privateKey = args.string("private_key")
        val passphrase = args.string("passphrase")
        val pinnedFingerprint = args.string("pinned_fingerprint")

        if (pinnedFingerprint.isNullOrBlank()) return@ToolDefinition error("untrusted_host_key", "Probe the host and explicitly confirm its SHA256 fingerprint before saving")
        if (password.isNullOrBlank() && privateKey.isNullOrBlank()) {
            return@ToolDefinition error("missing_auth", "Either password or private_key must be provided")
        }

        val config = SshHostConfig(
            name = name,
            host = host,
            port = port,
            user = user,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
            pinnedFingerprint = pinnedFingerprint,
        )
        plugin.hostStore.save(config)

        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(true),
                "name" to ToolValue.StringValue(name),
                "host" to ToolValue.StringValue(host),
                "port" to ToolValue.NumberValue(port.toDouble()),
                "user" to ToolValue.StringValue(user),
                "auth_type" to ToolValue.StringValue(if (privateKey != null) "key" else "password"),
            ),
        )
    },
)

private fun listHostsTool(plugin: SshPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_list_hosts",
        name = "ssh_list_hosts",
        description = "List all configured SSH hosts with safe desensitized summaries.",
        inputSchema = ToolInputSchema(),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_EXEC),
        timeoutMs = TIMEOUT_HOST_OPS,
        concurrencySafe = true,
    ),
    executor = { _, _ ->
        val hosts = plugin.hostStore.list()
        val items = hosts.map { h ->
            ToolValue.ObjectValue(
                linkedMapOf(
                    "name" to ToolValue.StringValue(h.name),
                    "host" to ToolValue.StringValue(h.host),
                    "port" to ToolValue.NumberValue(h.port.toDouble()),
                    "user" to ToolValue.StringValue(h.user),
                    "auth_type" to ToolValue.StringValue(h.authType),
                    "pinned_fingerprint" to (h.pinnedFingerprint?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
                ),
            )
        }
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(true),
                "hosts" to ToolValue.ArrayValue(items),
            ),
        )
    },
)

private fun deleteHostTool(plugin: SshPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_delete_host",
        name = "ssh_delete_host",
        description = "Remove a saved SSH host configuration by name.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("name", ToolValueType.STRING, "Name of the host to remove.", required = true),
            ),
        ),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_EXEC),
        timeoutMs = TIMEOUT_HOST_OPS,
        concurrencySafe = true,
    ),
    executor = { _, args ->
        val name = args.string("name") ?: return@ToolDefinition error("missing_arg", "name is required")
        val deleted = plugin.hostStore.delete(name)
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(true),
                "name" to ToolValue.StringValue(name),
                "deleted" to ToolValue.BooleanValue(deleted),
            ),
        )
    },
)

private fun probeTool(plugin: SshPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_probe",
        name = "ssh_probe",
        description = "Probe an SSH endpoint for reachability, presented key fingerprint, and round-trip handshake latency without credentials.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("host", ToolValueType.STRING, "Remote host or IP address.", required = true),
                param("port", ToolValueType.INTEGER, "SSH port (default: 22).", minimum = 1.0, maximum = 65535.0),
                param("timeout_ms", ToolValueType.INTEGER, "Probe timeout in milliseconds (default: 5000).", minimum = 500.0, maximum = 30000.0),
            ),
        ),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_EXEC),
        timeoutMs = TIMEOUT_HOST_OPS,
        concurrencySafe = true,
    ),
    executor = { _, args ->
        val host = args.string("host") ?: return@ToolDefinition error("missing_arg", "host is required")
        val port = args.int("port") ?: 22
        val timeoutMs = (args.int("timeout_ms")?.toLong() ?: 5_000L).coerceIn(500L, 30_000L)

        val probe = plugin.service.probe(host, port, timeoutMs)
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(probe.reachable),
                "host" to ToolValue.StringValue(host),
                "port" to ToolValue.NumberValue(port.toDouble()),
                "reachable" to ToolValue.BooleanValue(probe.reachable),
                "latency_ms" to ToolValue.NumberValue(probe.latencyMs.toDouble()),
                "host_key_type" to (probe.hostKeyType?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
                "fingerprint_sha256" to (probe.fingerprintSha256?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
                "error" to (probe.error?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
            ),
        )
    },
)

private fun execTool(
    plugin: SshPlugin,
    jobRegistry: com.cordiact.core.jobs.JobRegistry? = null,
    probe: SshJobProbe? = null,
) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_exec",
        name = "ssh_exec",
        description = "Run a shell command on an ad-hoc remote SSH host without saving credentials. Pass background=true to run detached.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("host", ToolValueType.STRING, "Hostname or IP address.", required = true),
                param("port", ToolValueType.INTEGER, "SSH port (default: 22).", minimum = 1.0, maximum = 65535.0),
                param("user", ToolValueType.STRING, "SSH username.", required = true),
                param("password", ToolValueType.STRING, "SSH password."),
                param("private_key", ToolValueType.STRING, "Private key content."),
                param("passphrase", ToolValueType.STRING, "Passphrase for private key."),
                param("command", ToolValueType.STRING, "Shell command to run.", required = true, maxLength = 8_192),
                param("stdin", ToolValueType.STRING, "Optional stdin data to pipe to the command."),
                param("timeout_ms", ToolValueType.INTEGER, "Timeout in milliseconds (default: 30000).", minimum = 1_000.0, maximum = 300_000.0),
                param("background", ToolValueType.BOOLEAN, "Run detached via nohup. Returns job_id when background job runtime is available."),
            ),
        ),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_EXEC),
        timeoutMs = TIMEOUT_DEFAULT_EXEC,
        concurrencySafe = false,
        execution = ToolExecutionMetadata(
            listOf(
                ToolExecutionAction(
                    action = "exec",
                    backend = ToolExecutionBackend.SSH,
                    inputKind = ToolExecutionInputKind.COMMAND,
                    inputParameter = "command",
                    supplementalInputParameters = listOf("stdin"),
                    hostParameter = "host",
                    portParameter = "port",
                    privilege = ToolExecutionPrivilege.STANDARD,
                    dataClasses = setOf(ToolDataClass.SHELL_COMMAND, ToolDataClass.REMOTE_TARGET),
                ),
            ),
        ),
    ),
    executor = { context, args ->
        val host = args.string("host") ?: return@ToolDefinition error("missing_arg", "host is required")
        val port = args.int("port") ?: 22
        val user = args.string("user") ?: return@ToolDefinition error("missing_arg", "user is required")
        val password = args.string("password")
        val privateKey = args.string("private_key")
        val passphrase = args.string("passphrase")
        val command = args.string("command") ?: return@ToolDefinition error("missing_arg", "command is required")
        val stdin = args.string("stdin")
        val timeoutMs = (args.int("timeout_ms")?.toLong() ?: 30_000L).coerceIn(1000L, 300_000L)
        val background = args.boolean("background") ?: false

        if (password.isNullOrBlank() && privateKey.isNullOrBlank()) {
            return@ToolDefinition error("missing_auth", "Either password or private_key is required")
        }

        val config = SshHostConfig(
            name = "ad-hoc@$host",
            host = host,
            port = port,
            user = user,
            password = password,
            privateKey = privateKey,
            passphrase = passphrase,
        )

        val params = SshExecParams(
            command = command,
            stdin = stdin,
            timeoutMs = timeoutMs,
            background = background,
        )

        val targetSessionId = context.sessionId ?: "ephemeral"
        if (background && jobRegistry != null) {
            val handle = jobRegistry.start(
                com.cordiact.core.jobs.JobSpec(
                    kind = com.cordiact.core.jobs.JobKind.SSH,
                    survival = com.cordiact.core.jobs.JobSurvival.REMOTE_DETACHED,
                    label = "ssh: $command",
                    ownerSessionId = targetSessionId,
                    // Ad-hoc credentials never reach the database, so the probe keeps them in
                    // memory for as long as this process lives and gives up after a restart.
                    remoteHostRef = null,
                ),
            )
            probe?.rememberAdHocHost(handle.id, config)
            // Execute nohup asynchronously
            kotlin.concurrent.thread {
                com.cordiact.core.jobs.JobHeartbeats.start(handle).use {
                    val execResult = plugin.service.execute(config, params)
                    val pid = execResult.stdout.trim().toLongOrNull()
                    if (pid != null) {
                        handle.updateRemotePid(pid)
                        handle.append("Detached remote PID: $pid\n")
                    } else if (execResult.stdout.isNotBlank()) {
                        handle.append(execResult.stdout)
                    }
                    if (execResult.stderr.isNotBlank()) {
                        handle.append("\n[stderr]: ${execResult.stderr}")
                    }
                    if (execResult.isSuccess) {
                        handle.settle(
                            com.cordiact.core.jobs.JobState.DETACHED_UNKNOWN,
                            diagnostic = "Dispatched to $host; remote PID ${pid ?: "unknown"} may still be running. " +
                                "Use job_probe to check before assuming an outcome.",
                        )
                    } else {
                        handle.settle(com.cordiact.core.jobs.JobState.FAILED, exitCode = execResult.exitCode, diagnostic = execResult.stderr)
                    }
                }
            }
            return@ToolDefinition ToolResult(
                values = linkedMapOf(
                    "ok" to ToolValue.BooleanValue(true),
                    "job_id" to ToolValue.StringValue(handle.id),
                    "state" to ToolValue.StringValue("running"),
                    "content" to ToolValue.StringValue("SSH command dispatched in background as job '${handle.id}'. Use job_output(job_id=\"${handle.id}\") to monitor."),
                ),
            )
        }

        val result = plugin.service.execute(config, params)
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(result.isSuccess),
                "exit_code" to ToolValue.NumberValue(result.exitCode.toDouble()),
                "stdout" to ToolValue.StringValue(result.stdout),
                "stderr" to ToolValue.StringValue(result.stderr),
                "timed_out" to ToolValue.BooleanValue(result.timedOut),
                "truncated" to ToolValue.BooleanValue(result.truncated),
                "safety_class" to ToolValue.StringValue(ShellCommandPolicy.classify(command).name),
                "untrusted" to ToolValue.BooleanValue(true),
            ),
        )
    },
)

private fun execSavedTool(
    plugin: SshPlugin,
    jobRegistry: com.cordiact.core.jobs.JobRegistry? = null,
    probe: SshJobProbe? = null,
) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_exec_saved",
        name = "ssh_exec_saved",
        description = "Run a shell command on a previously-saved SSH host. Pass background=true to run detached.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("name", ToolValueType.STRING, "Saved host name.", required = true),
                param("command", ToolValueType.STRING, "Shell command to run.", required = true, maxLength = 8_192),
                param("stdin", ToolValueType.STRING, "Optional stdin data to pipe to the command."),
                param("timeout_ms", ToolValueType.INTEGER, "Timeout in milliseconds (default: 30000).", minimum = 1_000.0, maximum = 300_000.0),
                param("background", ToolValueType.BOOLEAN, "Run detached via nohup. Returns job_id when background job runtime is available."),
            ),
        ),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_EXEC),
        timeoutMs = TIMEOUT_DEFAULT_EXEC,
        concurrencySafe = false,
        execution = ToolExecutionMetadata(
            listOf(
                ToolExecutionAction(
                    action = "exec_saved",
                    backend = ToolExecutionBackend.SSH,
                    inputKind = ToolExecutionInputKind.COMMAND,
                    inputParameter = "command",
                    supplementalInputParameters = listOf("stdin"),
                    savedHostParameter = "name",
                    resolveTarget = { args ->
                        args.string("name")?.let { name ->
                            plugin.hostStore.get(name)?.let { "${it.host}:${it.port}" } ?: "saved:$name"
                        }
                    },
                    privilege = ToolExecutionPrivilege.STANDARD,
                    dataClasses = setOf(ToolDataClass.SHELL_COMMAND, ToolDataClass.REMOTE_TARGET),
                ),
            ),
        ),
    ),
    executor = { context, args ->
        val name = args.string("name") ?: return@ToolDefinition error("missing_arg", "name is required")
        val command = args.string("command") ?: return@ToolDefinition error("missing_arg", "command is required")
        val stdin = args.string("stdin")
        val timeoutMs = (args.int("timeout_ms")?.toLong() ?: 30_000L).coerceIn(1000L, 300_000L)
        val background = args.boolean("background") ?: false

        val config = plugin.hostStore.get(name)
            ?: return@ToolDefinition error("host_not_found", "No saved host named '$name'")

        val params = SshExecParams(
            command = command,
            stdin = stdin,
            timeoutMs = timeoutMs,
            background = background,
        )

        val targetSessionId = context.sessionId ?: "ephemeral"
        if (background && jobRegistry != null) {
            val handle = jobRegistry.start(
                com.cordiact.core.jobs.JobSpec(
                    kind = com.cordiact.core.jobs.JobKind.SSH,
                    survival = com.cordiact.core.jobs.JobSurvival.REMOTE_DETACHED,
                    label = "ssh ($name): $command",
                    ownerSessionId = targetSessionId,
                    // A saved host can be probed after a restart: its credentials are on disk.
                    remoteHostRef = name,
                ),
            )
            kotlin.concurrent.thread {
                com.cordiact.core.jobs.JobHeartbeats.start(handle).use {
                    val execResult = plugin.service.execute(config, params)
                    val pid = execResult.stdout.trim().toLongOrNull()
                    if (pid != null) {
                        handle.updateRemotePid(pid)
                        handle.append("Detached remote PID on $name: $pid\n")
                    } else if (execResult.stdout.isNotBlank()) {
                        handle.append(execResult.stdout)
                    }
                    if (execResult.stderr.isNotBlank()) {
                        handle.append("\n[stderr]: ${execResult.stderr}")
                    }
                    if (execResult.isSuccess) {
                        handle.settle(
                            com.cordiact.core.jobs.JobState.DETACHED_UNKNOWN,
                            diagnostic = "Dispatched to saved host '$name'; remote PID ${pid ?: "unknown"} may still " +
                                "be running. Use job_probe to check before assuming an outcome.",
                        )
                    } else {
                        handle.settle(com.cordiact.core.jobs.JobState.FAILED, exitCode = execResult.exitCode, diagnostic = execResult.stderr)
                    }
                }
            }
            return@ToolDefinition ToolResult(
                values = linkedMapOf(
                    "ok" to ToolValue.BooleanValue(true),
                    "job_id" to ToolValue.StringValue(handle.id),
                    "state" to ToolValue.StringValue("running"),
                    "content" to ToolValue.StringValue("SSH command dispatched in background as job '${handle.id}'. Use job_output(job_id=\"${handle.id}\") to monitor."),
                ),
            )
        }

        val result = plugin.service.execute(config, params)
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(result.isSuccess),
                "exit_code" to ToolValue.NumberValue(result.exitCode.toDouble()),
                "stdout" to ToolValue.StringValue(result.stdout),
                "stderr" to ToolValue.StringValue(result.stderr),
                "timed_out" to ToolValue.BooleanValue(result.timedOut),
                "truncated" to ToolValue.BooleanValue(result.truncated),
                "safety_class" to ToolValue.StringValue(ShellCommandPolicy.classify(command).name),
                "untrusted" to ToolValue.BooleanValue(true),
            ),
        )
    },
)

private fun uploadTool(plugin: SshPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_upload",
        name = "ssh_upload",
        description = "Upload text or base64 file data to a remote path on a saved SSH host via SFTP.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("name", ToolValueType.STRING, "Saved host name.", required = true),
                param("remote_path", ToolValueType.STRING, "Absolute remote destination file path.", required = true),
                param("text", ToolValueType.STRING, "UTF-8 text content to upload."),
                param("base64", ToolValueType.STRING, "Base64 encoded binary content to upload."),
            ),
        ),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_SFTP),
        timeoutMs = TIMEOUT_SFTP,
        concurrencySafe = false,
        execution = ToolExecutionMetadata(
            listOf(
                ToolExecutionAction(
                    action = "upload",
                    backend = ToolExecutionBackend.SSH,
                    inputKind = ToolExecutionInputKind.FILE_CONTENT,
                    inputParameter = "text",
                    alternativeInputParameters = listOf("base64"),
                    showInputInApproval = false,
                    savedHostParameter = "name",
                    resolveTarget = { args ->
                        args.string("name")?.let { name ->
                            plugin.hostStore.get(name)?.let { "${it.host}:${it.port}" } ?: "saved:$name"
                        }
                    },
                    privilege = ToolExecutionPrivilege.STANDARD,
                    dataClasses = setOf(ToolDataClass.FILE_CONTENT, ToolDataClass.REMOTE_TARGET),
                ),
            ),
        ),
    ),
    executor = { _, args ->
        val name = args.string("name") ?: return@ToolDefinition error("missing_arg", "name is required")
        val remotePath = args.string("remote_path") ?: return@ToolDefinition error("missing_arg", "remote_path is required")
        val text = args.string("text")
        val base64 = args.string("base64")

        val config = plugin.hostStore.get(name)
            ?: return@ToolDefinition error("host_not_found", "Saved SSH host '$name' not found")

        val bytes = when {
            text != null -> text.toByteArray(Charsets.UTF_8)
            base64 != null -> try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: Exception) {
                return@ToolDefinition error("invalid_base64", "Failed to decode base64 content: ${e.message}")
            }
            else -> return@ToolDefinition error("missing_payload", "Either text or base64 must be provided")
        }

        val res = plugin.service.upload(config, bytes, remotePath)
        if (!res.success) {
            return@ToolDefinition error("upload_failed", res.error ?: "SFTP upload failed")
        }

        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(true),
                "remote_path" to ToolValue.StringValue(res.path),
                "bytes_transferred" to ToolValue.NumberValue(res.bytesTransferred.toDouble()),
            ),
        )
    },
)

private fun downloadTool(plugin: SshPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_download",
        name = "ssh_download",
        description = "Download a file from a saved SSH host via SFTP.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("name", ToolValueType.STRING, "Saved host name.", required = true),
                param("remote_path", ToolValueType.STRING, "Absolute remote file path to download.", required = true),
                param("as_text", ToolValueType.BOOLEAN, "If true, return content as UTF-8 text; otherwise base64 (default: true)."),
            ),
        ),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_SFTP),
        timeoutMs = TIMEOUT_SFTP,
        concurrencySafe = false,
    ),
    executor = { _, args ->
        val name = args.string("name") ?: return@ToolDefinition error("missing_arg", "name is required")
        val remotePath = args.string("remote_path") ?: return@ToolDefinition error("missing_arg", "remote_path is required")
        val asText = args.boolean("as_text") ?: true

        val config = plugin.hostStore.get(name)
            ?: return@ToolDefinition error("host_not_found", "Saved SSH host '$name' not found")

        val (bytes, downloadError) = plugin.service.download(config, remotePath)
        if (downloadError != null || bytes == null) {
            return@ToolDefinition error("download_failed", downloadError ?: "Unknown SFTP download error")
        }

        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(true),
                "remote_path" to ToolValue.StringValue(remotePath),
                "bytes_read" to ToolValue.NumberValue(bytes.size.toDouble()),
                "content" to if (asText) ToolValue.StringValue(String(bytes, Charsets.UTF_8)) else ToolValue.StringValue(Base64.encodeToString(bytes, Base64.NO_WRAP)),
                "is_binary" to ToolValue.BooleanValue(!asText),
                "untrusted" to ToolValue.BooleanValue(true),
            ),
        )
    },
)

private fun openTunnelTool(plugin: SshPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_open_tunnel",
        name = "ssh_open_tunnel",
        description = "Open a local port forwarding tunnel via SSH (binds 127.0.0.1:local_port on phone -> remote_host:remote_port).",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("name", ToolValueType.STRING, "Saved host name.", required = true),
                param("local_port", ToolValueType.INTEGER, "Local port to listen on Android device.", required = true, minimum = 1024.0, maximum = 65535.0),
                param("remote_host", ToolValueType.STRING, "Destination host reachable from the SSH server (default: 127.0.0.1)."),
                param("remote_port", ToolValueType.INTEGER, "Destination port on the remote target.", required = true, minimum = 1.0, maximum = 65535.0),
            ),
        ),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_TUNNEL),
        timeoutMs = TIMEOUT_HOST_OPS,
        concurrencySafe = false,
    ),
    executor = { _, args ->
        val name = args.string("name") ?: return@ToolDefinition error("missing_arg", "name is required")
        val localPort = args.int("local_port") ?: return@ToolDefinition error("missing_arg", "local_port is required")
        val remoteHost = args.string("remote_host") ?: "127.0.0.1"
        val remotePort = args.int("remote_port") ?: return@ToolDefinition error("missing_arg", "remote_port is required")

        val config = plugin.hostStore.get(name)
            ?: return@ToolDefinition error("host_not_found", "Saved SSH host '$name' not found")

        val (tunnelId, err) = plugin.service.openTunnel(config, localPort, remoteHost, remotePort)
        if (err != null || tunnelId == null) {
            return@ToolDefinition error("tunnel_failed", err ?: "Unknown tunnel error")
        }

        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(true),
                "tunnel_id" to ToolValue.StringValue(tunnelId),
                "local_endpoint" to ToolValue.StringValue("127.0.0.1:$localPort"),
                "remote_target" to ToolValue.StringValue("$remoteHost:$remotePort"),
            ),
        )
    },
)

private fun closeTunnelTool(plugin: SshPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_close_tunnel",
        name = "ssh_close_tunnel",
        description = "Close an active SSH port forwarding tunnel by its tunnel_id.",
        inputSchema = ToolInputSchema(
            parameters = listOf(
                param("tunnel_id", ToolValueType.STRING, "Tunnel ID returned by ssh_open_tunnel.", required = true),
            ),
        ),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_TUNNEL),
        timeoutMs = TIMEOUT_HOST_OPS,
        concurrencySafe = true,
    ),
    executor = { _, args ->
        val tunnelId = args.string("tunnel_id") ?: return@ToolDefinition error("missing_arg", "tunnel_id is required")
        val closed = plugin.service.closeTunnel(tunnelId)
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(true),
                "tunnel_id" to ToolValue.StringValue(tunnelId),
                "closed" to ToolValue.BooleanValue(closed),
            ),
        )
    },
)

private fun listTunnelsTool(plugin: SshPlugin) = ToolDefinition(
    descriptor = ToolDescriptor(
        id = "ssh_list_tunnels",
        name = "ssh_list_tunnels",
        description = "List all active SSH port forwarding tunnels currently established on this device.",
        inputSchema = ToolInputSchema(),
        ownerPluginId = AutomationPlugins.SSH,
        defaultEnabled = false,
        requiredCapabilities = setOf(AutomationCapabilities.SSH_TUNNEL),
        timeoutMs = TIMEOUT_HOST_OPS,
        concurrencySafe = true,
    ),
    executor = { _, _ ->
        val tunnels = plugin.service.listTunnels()
        val items = tunnels.map { t ->
            ToolValue.ObjectValue(
                linkedMapOf(
                    "tunnel_id" to ToolValue.StringValue(t.tunnelId),
                    "host_name" to ToolValue.StringValue(t.hostName),
                    "local_port" to ToolValue.NumberValue(t.localPort.toDouble()),
                    "remote_host" to ToolValue.StringValue(t.remoteHost),
                    "remote_port" to ToolValue.NumberValue(t.remotePort.toDouble()),
                    "active" to ToolValue.BooleanValue(t.active),
                ),
            )
        }
        ToolResult(
            values = linkedMapOf(
                "ok" to ToolValue.BooleanValue(true),
                "tunnels" to ToolValue.ArrayValue(items),
            ),
        )
    },
)

private fun param(
    name: String,
    type: ToolValueType,
    description: String,
    required: Boolean = false,
    minimum: Double? = null,
    maximum: Double? = null,
    maxLength: Int? = null,
) = ToolParameter(
    name = name,
    type = type,
    required = required,
    description = description,
    minimum = minimum,
    maximum = maximum,
    maxLength = maxLength,
)

private fun error(code: String, message: String) = ToolResult(
    values = linkedMapOf(
        "ok" to ToolValue.BooleanValue(false),
        "error" to ToolValue.ObjectValue(
            linkedMapOf(
                "code" to ToolValue.StringValue(code),
                "message" to ToolValue.StringValue(message),
            ),
        ),
    ),
)

private fun ToolArguments.string(name: String): String? =
    (values[name] as? ToolValue.StringValue)?.value?.takeIf { it.isNotBlank() }

private fun ToolArguments.int(name: String): Int? = when (val value = values[name]) {
    is ToolValue.NumberValue -> value.value.toInt()
    is ToolValue.StringValue -> value.value.toIntOrNull()
    else -> null
}

private fun ToolArguments.boolean(name: String): Boolean? = when (val value = values[name]) {
    is ToolValue.BooleanValue -> value.value
    is ToolValue.StringValue -> value.value.toBooleanStrictOrNull()
    else -> null
}

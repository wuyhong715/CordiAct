package com.cordiact.core.execution

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Robust, production-grade SSH service backed by modern JSch (mwiede fork).
 */
class JSchSshService : SshExecutionService {

    private data class ActiveTunnel(
        val tunnelId: String,
        val hostName: String,
        val localPort: Int,
        val remoteHost: String,
        val remotePort: Int,
        val session: Session,
    )

    private val activeTunnels = ConcurrentHashMap<String, ActiveTunnel>()

    private fun createSession(config: SshHostConfig, timeoutMs: Long): Session {
        val jsch = JSch()
        if (!config.privateKey.isNullOrBlank()) {
            val keyBytes = config.privateKey.trim().toByteArray(Charsets.UTF_8)
            val passBytes = config.passphrase?.toByteArray(Charsets.UTF_8)
            jsch.addIdentity(config.name, keyBytes, null, passBytes)
        }

        val session = jsch.getSession(config.user, config.host, config.port)
        if (!config.password.isNullOrBlank()) {
            session.setPassword(config.password)
        }

        val prop = Properties()
        prop["StrictHostKeyChecking"] = "no"
        prop["PreferredAuthentications"] = if (!config.privateKey.isNullOrBlank()) {
            "publickey,password,keyboard-interactive"
        } else {
            "password,keyboard-interactive,publickey"
        }
        session.setConfig(prop)
        session.connect(timeoutMs.coerceAtLeast(1000).toInt())

        // Fingerprint verification if pinned
        if (!config.pinnedFingerprint.isNullOrBlank()) {
            val actualFingerprint = session.hostKey?.getFingerPrint(jsch)
            if (actualFingerprint != null && !actualFingerprint.equals(config.pinnedFingerprint, ignoreCase = true)) {
                session.disconnect()
                throw IllegalStateException(
                    "Host key mismatch for ${config.name}! Pinned: ${config.pinnedFingerprint}, Presented: $actualFingerprint"
                )
            }
        }

        return session
    }

    override fun testConnection(config: SshHostConfig, timeoutMs: Long): Pair<Boolean, String> {
        return try {
            val session = createSession(config, timeoutMs)
            val hostKey = session.hostKey
            val fp = hostKey?.getFingerPrint(JSch()) ?: "unknown"
            session.disconnect()
            true to "Connected successfully. Host fingerprint: $fp"
        } catch (e: Exception) {
            false to (e.message ?: e.javaClass.simpleName)
        }
    }

    override fun probe(host: String, port: Int, timeoutMs: Long): SshProbeResult {
        val startTime = System.currentTimeMillis()
        return try {
            val jsch = JSch()
            val session = jsch.getSession("probe", host, port)
            val prop = Properties()
            prop["StrictHostKeyChecking"] = "no"
            session.setConfig(prop)
            // Connect with timeout to perform key exchange
            try {
                session.connect(timeoutMs.coerceAtLeast(500).toInt())
            } catch (_: Exception) {
                // Expected to fail authentication after key exchange
            }
            val latency = System.currentTimeMillis() - startTime
            val hostKey = session.hostKey
            val fp = hostKey?.getFingerPrint(jsch)
            val keyType = hostKey?.type
            try {
                session.disconnect()
            } catch (_: Throwable) {}

            if (hostKey != null) {
                SshProbeResult(
                    reachable = true,
                    hostKeyType = keyType,
                    fingerprintSha256 = fp,
                    latencyMs = latency,
                )
            } else {
                SshProbeResult(
                    reachable = false,
                    error = "No host key presented within timeout",
                )
            }
        } catch (e: Exception) {
            SshProbeResult(
                reachable = false,
                latencyMs = System.currentTimeMillis() - startTime,
                error = e.message ?: e.javaClass.simpleName,
            )
        }
    }

    override fun execute(config: SshHostConfig, params: SshExecParams): ExecutionResult {
        var session: Session? = null
        var channel: ChannelExec? = null
        return try {
            session = createSession(config, params.timeoutMs)
            channel = session.openChannel("exec") as ChannelExec

            val finalCommand = if (params.background) {
                "nohup sh -c " + escapeShellArg(params.command) + " > /dev/null 2>&1 & echo $!"
            } else {
                params.command
            }

            channel.setCommand(finalCommand)

            val stdoutStream = ByteArrayOutputStream()
            val stderrStream = ByteArrayOutputStream()
            channel.outputStream = stdoutStream
            channel.setErrStream(stderrStream)

            if (!params.stdin.isNullOrEmpty()) {
                channel.inputStream = ByteArrayInputStream(params.stdin.toByteArray(Charsets.UTF_8))
            }

            channel.connect(params.timeoutMs.coerceAtLeast(1000).toInt())

            val startTime = System.currentTimeMillis()
            val pollIntervalMs = 50L
            while (!channel.isClosed) {
                if (System.currentTimeMillis() - startTime > params.timeoutMs) {
                    channel.disconnect()
                    session.disconnect()
                    return ExecutionResult.timedOut("Command timed out after ${params.timeoutMs} ms")
                }
                Thread.sleep(pollIntervalMs)
            }

            val exitCode = channel.exitStatus
            val rawStdout = stdoutStream.toString(Charsets.UTF_8)
            val rawStderr = stderrStream.toString(Charsets.UTF_8)

            val (stdout, stdoutTruncated) = ExecutionResult.truncateOutput(rawStdout)
            val (stderr, stderrTruncated) = ExecutionResult.truncateOutput(rawStderr)

            ExecutionResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                timedOut = false,
                truncated = stdoutTruncated || stderrTruncated,
            )
        } catch (e: Exception) {
            ExecutionResult.failure("SSH execution error: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try {
                channel?.disconnect()
            } catch (_: Throwable) {}
            try {
                session?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    override fun upload(config: SshHostConfig, localBytes: ByteArray, remotePath: String): SshFileResult {
        var session: Session? = null
        var sftp: ChannelSftp? = null
        return try {
            session = createSession(config, 30_000)
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(30_000)

            val inputStream = ByteArrayInputStream(localBytes)
            sftp.put(inputStream, remotePath)

            SshFileResult(
                success = true,
                path = remotePath,
                bytesTransferred = localBytes.size.toLong(),
            )
        } catch (e: Exception) {
            SshFileResult(
                success = false,
                path = remotePath,
                bytesTransferred = 0,
                error = e.message ?: e.javaClass.simpleName,
            )
        } finally {
            try {
                sftp?.disconnect()
            } catch (_: Throwable) {}
            try {
                session?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    override fun download(config: SshHostConfig, remotePath: String, maxBytes: Long): Pair<ByteArray?, String?> {
        var session: Session? = null
        var sftp: ChannelSftp? = null
        return try {
            session = createSession(config, 30_000)
            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(30_000)

            val out = ByteArrayOutputStream()
            sftp.get(remotePath, out)
            val bytes = out.toByteArray()
            if (bytes.size > maxBytes) {
                null to "File too large: ${bytes.size} bytes exceeds limit of $maxBytes bytes"
            } else {
                bytes to null
            }
        } catch (e: Exception) {
            null to (e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                sftp?.disconnect()
            } catch (_: Throwable) {}
            try {
                session?.disconnect()
            } catch (_: Throwable) {}
        }
    }

    override fun openTunnel(
        config: SshHostConfig,
        localPort: Int,
        remoteHost: String,
        remotePort: Int,
    ): Pair<String?, String?> {
        if (localPort !in 1..65535 || remotePort !in 1..65535) {
            return null to "Port numbers must be between 1 and 65535"
        }
        val existing = activeTunnels.values.firstOrNull { it.localPort == localPort }
        if (existing != null) {
            return null to "Local port $localPort is already used by tunnel ${existing.tunnelId}"
        }

        return try {
            val session = createSession(config, 15_000)
            val boundPort = session.setPortForwardingL("127.0.0.1", localPort, remoteHost, remotePort)
            val tunnelId = "tun_${UUID.randomUUID().toString().take(8)}"
            val tunnel = ActiveTunnel(
                tunnelId = tunnelId,
                hostName = config.name,
                localPort = boundPort,
                remoteHost = remoteHost,
                remotePort = remotePort,
                session = session,
            )
            activeTunnels[tunnelId] = tunnel
            tunnelId to null
        } catch (e: Exception) {
            null to "Failed to establish tunnel: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    override fun closeTunnel(tunnelId: String): Boolean {
        val tunnel = activeTunnels.remove(tunnelId) ?: return false
        return try {
            tunnel.session.delPortForwardingL("127.0.0.1", tunnel.localPort)
            tunnel.session.disconnect()
            true
        } catch (_: Exception) {
            try {
                tunnel.session.disconnect()
            } catch (_: Exception) {}
            true
        }
    }

    override fun listTunnels(): List<SshTunnelInfo> {
        return activeTunnels.values.map {
            SshTunnelInfo(
                tunnelId = it.tunnelId,
                hostName = it.hostName,
                localPort = it.localPort,
                remoteHost = it.remoteHost,
                remotePort = it.remotePort,
                active = it.session.isConnected,
            )
        }.sortedBy { it.localPort }
    }

    private fun escapeShellArg(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}

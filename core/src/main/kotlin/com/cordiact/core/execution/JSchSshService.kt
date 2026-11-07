package com.cordiact.core.execution

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Properties

/**
 * Robust, production-grade SSH service backed by modern JSch (mwiede fork).
 */
class JSchSshService : SshExecutionService {

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

    override fun execute(config: SshHostConfig, params: SshExecParams): ExecutionResult {
        var session: Session? = null
        var channel: ChannelExec? = null
        return try {
            session = createSession(config, params.timeoutMs)
            channel = session.openChannel("exec") as ChannelExec

            val finalCommand = if (params.background) {
                // Background execution detached with PID returned
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

    private fun escapeShellArg(arg: String): String {
        return "'" + arg.replace("'", "'\\''") + "'"
    }
}

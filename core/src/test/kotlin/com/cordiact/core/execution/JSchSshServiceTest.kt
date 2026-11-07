package com.cordiact.core.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JSchSshServiceTest {

    @Test
    fun `test connection failure on non-existent host returns failure safely`() {
        val service = JSchSshService()
        val config = SshHostConfig(
            name = "test-fake",
            host = "127.0.0.1",
            port = 54321, // Unbound port
            user = "user",
            password = "password",
        )

        val (ok, errorMsg) = service.testConnection(config, timeoutMs = 500)
        assertFalse(ok)
        assertTrue(errorMsg.isNotBlank())

        val execResult = service.execute(
            config,
            SshExecParams(command = "whoami", timeoutMs = 500)
        )
        assertFalse(execResult.isSuccess)
        assertTrue(execResult.stderr.contains("SSH execution error"))
    }

    @Test
    fun `test upload and download fail gracefully on disconnected host`() {
        val service = JSchSshService()
        val config = SshHostConfig(
            name = "test-fake-sftp",
            host = "127.0.0.1",
            port = 54322,
            user = "user",
            password = "password",
        )

        val uploadResult = service.upload(config, "test".toByteArray(), "/tmp/test.txt")
        assertFalse(uploadResult.success)
        assertEquals(0L, uploadResult.bytesTransferred)
        assertTrue(uploadResult.error != null)

        val (bytes, downloadError) = service.download(config, "/tmp/test.txt")
        assertEquals(null, bytes)
        assertTrue(downloadError != null)
    }

    @Test
    fun `test tunnel operations on disconnected host`() {
        val service = JSchSshService()
        val config = SshHostConfig(
            name = "test-fake-tun",
            host = "127.0.0.1",
            port = 54323,
            user = "user",
            password = "password",
        )

        // Invalid port validation
        val (tunIdInvalid, errInvalid) = service.openTunnel(config, localPort = -1, remoteHost = "127.0.0.1", remotePort = 8080)
        assertEquals(null, tunIdInvalid)
        assertTrue(errInvalid != null)

        // Unreachable host
        val (tunId, err) = service.openTunnel(config, localPort = 18080, remoteHost = "127.0.0.1", remotePort = 8080)
        assertEquals(null, tunId)
        assertTrue(err != null)

        assertEquals(0, service.listTunnels().size)
        assertFalse(service.closeTunnel("non-existent"))
    }
}

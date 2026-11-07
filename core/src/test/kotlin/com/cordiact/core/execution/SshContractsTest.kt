package com.cordiact.core.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SshContractsTest {

    @Test
    fun `test valid host config password auth`() {
        val config = SshHostConfig(
            name = "my-vps",
            host = "1.2.3.4",
            port = 22,
            user = "root",
            password = "secret-password",
        )
        val summary = config.toSummary()
        assertEquals("my-vps", summary.name)
        assertEquals("1.2.3.4", summary.host)
        assertEquals(22, summary.port)
        assertEquals("root", summary.user)
        assertEquals("password", summary.authType)
        assertNull(summary.pinnedFingerprint)
    }

    @Test
    fun `test valid host config key auth`() {
        val config = SshHostConfig(
            name = "my-key-vps",
            host = "example.com",
            port = 2222,
            user = "admin",
            privateKey = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake\n-----END OPENSSH PRIVATE KEY-----",
            pinnedFingerprint = "SHA256:abc12345",
        )
        val summary = config.toSummary()
        assertEquals("key", summary.authType)
        assertEquals("SHA256:abc12345", summary.pinnedFingerprint)
    }

    @Test
    fun `test invalid config without auth throws`() {
        assertFailsWith<IllegalArgumentException> {
            SshHostConfig(
                name = "invalid",
                host = "1.1.1.1",
                port = 22,
                user = "user",
            )
        }
    }

    @Test
    fun `test invalid port throws`() {
        assertFailsWith<IllegalArgumentException> {
            SshHostConfig(
                name = "invalid-port",
                host = "1.1.1.1",
                port = 70000,
                user = "user",
                password = "pwd",
            )
        }
    }
}

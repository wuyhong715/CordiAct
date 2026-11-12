package com.cordiact.core.execution

import com.jcraft.jsch.HostKeyRepository
import kotlin.test.*

class PinnedHostKeyRepositoryTest {
    @Test fun fingerprintIsOpenSshSha256WithoutPadding() {
        assertEquals("SHA256:ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0", PinnedHostKeyRepository.sha256("abc".toByteArray()))
    }
    @Test fun onlyExactPinnedKeyIsAccepted() {
        val key = "known-key".toByteArray()
        val repository = PinnedHostKeyRepository(PinnedHostKeyRepository.sha256(key))
        assertEquals(HostKeyRepository.OK, repository.check("host", key))
        assertEquals(HostKeyRepository.CHANGED, repository.check("host", "attacker-key".toByteArray()))
        assertEquals(HostKeyRepository.CHANGED, repository.check("host", null))
    }
    @Test fun legacyMd5FingerprintRequiresExplicitReprobe() {
        assertFailsWith<IllegalArgumentException> { PinnedHostKeyRepository("aa:bb:cc") }
        val key = "known-key".toByteArray(); val pin = PinnedHostKeyRepository.sha256(key)
        assertEquals(HostKeyRepository.CHANGED, PinnedHostKeyRepository(pin.lowercase().replace("sha256:", "SHA256:")).check("host", key))
    }
}

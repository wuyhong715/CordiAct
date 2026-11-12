package com.cordiact.core.execution

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

/** JSch checks this during key exchange, before password/public-key authentication. */
class PinnedHostKeyRepository(private val fingerprint: String) : HostKeyRepository {
    init { require(fingerprint.startsWith("SHA256:") && fingerprint.length > 7) { "SSH_SHA256_PIN_REQUIRED" } }
    override fun check(host: String?, key: ByteArray?): Int =
        if (key != null && MessageDigest.isEqual(sha256(key).toByteArray(), fingerprint.toByteArray())) HostKeyRepository.OK
        else HostKeyRepository.CHANGED
    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "cordiact-explicit-sha256-pin"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    companion object {
        fun sha256(key: ByteArray): String = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
    }
}

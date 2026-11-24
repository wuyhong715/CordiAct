package com.cordiact.core.plugin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest

internal data class CatalogMark(val revision: Long, val digest: String)
internal data class CatalogCheckpoint(
    val scope: String,
    val epochSecond: Long,
    val root: CatalogMark? = null,
    val sources: Map<String, CatalogMark> = emptyMap(),
)

/** Host-private anti-rollback state, not a signed-document cache. Checked under an OS file lock. */
internal class SignedCatalogCheckpoint(private val path: Path) {
    private val directory = requireNotNull(path.toAbsolutePath().normalize().parent)
    private val file = directory.resolve(path.fileName)
    private val lockFile = directory.resolve("${path.fileName}.lock")

    fun create(initial: CatalogCheckpoint) = locked(createLock = true) {
        check(Files.notExists(file, NOFOLLOW_LINKS)) { STATE_UNAVAILABLE }
        persist(initial)
    }

    fun open(): CatalogCheckpoint = try { locked { read() } } catch (_: Exception) { error(STATE_UNAVAILABLE) }

    /** Publish only from the exact checkpoint this instance has seen. A lost/unknown write never retries. */
    fun <T> advance(expected: CatalogCheckpoint, update: () -> CatalogCheckpoint, operation: (CatalogCheckpoint) -> T): T = locked {
        check(read() == expected) { STATE_CHANGED }
        val updated = update()
        if (updated != expected) persist(updated)
        operation(updated)
    }

    private fun read(): CatalogCheckpoint {
        check(Files.isRegularFile(file, NOFOLLOW_LINKS)) { STATE_UNAVAILABLE }
        val bytes = Files.newInputStream(file, READ, NOFOLLOW_LINKS).use { input ->
            val bounded = ByteArray(MAX_SIZE + 1)
            var size = 0
            while (size < bounded.size) {
                val count = input.read(bounded, size, bounded.size - size)
                if (count < 0) break
                check(count > 0) { STATE_UNAVAILABLE }
                size += count
            }
            bounded.copyOf(size)
        }
        check(bytes.size in 33..MAX_SIZE) { STATE_UNAVAILABLE }
        val body = bytes.copyOf(bytes.size - 32)
        check(MessageDigest.isEqual(hash(body), bytes.copyOfRange(body.size, bytes.size))) { STATE_UNAVAILABLE }
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            check(input.readInt() == MAGIC) { STATE_UNAVAILABLE }
            val scope = input.readUTF().also { check(hex.matches(it)) { STATE_UNAVAILABLE } }
            val seconds = input.readLong()
            val root = when (input.readUnsignedByte()) {
                0 -> null
                1 -> input.mark()
                else -> error(STATE_UNAVAILABLE)
            }
            val count = input.readUnsignedByte()
            check(count <= MAX_CATALOG_SOURCES && (root != null || count == 0)) { STATE_UNAVAILABLE }
            val marks = linkedMapOf<String, CatalogMark>()
            repeat(count) {
                val source = input.readUTF()
                check(sourceId.matches(source) && marks.put(source, input.mark()) == null) { STATE_UNAVAILABLE }
            }
            check(input.read() == -1) { STATE_UNAVAILABLE }
            CatalogCheckpoint(scope, seconds, root, marks)
        }
    }

    private fun persist(value: CatalogCheckpoint) {
        val body = ByteArrayOutputStream().apply {
            DataOutputStream(this).use { output ->
                output.writeInt(MAGIC)
                output.writeUTF(value.scope)
                output.writeLong(value.epochSecond)
                output.writeByte(if (value.root == null) 0 else 1)
                value.root?.let { output.mark(it) }
                output.writeByte(value.sources.size)
                value.sources.toSortedMap().forEach { (id, mark) -> output.writeUTF(id); output.mark(mark) }
            }
        }.toByteArray()
        val bytes = body + hash(body)
        check(bytes.size <= MAX_SIZE)
        val temp = Files.createTempFile(directory, "catalog-checkpoint-", ".tmp")
        try {
            FileChannel.open(temp, WRITE, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) check(output.write(buffer) > 0) { STATE_UNAVAILABLE }
                output.force(true)
            }
            Files.move(temp, file, ATOMIC_MOVE, REPLACE_EXISTING)
            // A failed force after rename makes the write outcome unknown. The caller must retire.
            FileChannel.open(directory, READ).use { it.force(true) }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun <T> locked(createLock: Boolean = false, operation: () -> T): T = synchronized(processLock) {
        check(Files.isDirectory(directory, NOFOLLOW_LINKS)) { STATE_UNAVAILABLE }
        val options = if (createLock) arrayOf<java.nio.file.OpenOption>(WRITE, CREATE_NEW, NOFOLLOW_LINKS)
        else arrayOf<java.nio.file.OpenOption>(WRITE, NOFOLLOW_LINKS)
        val channel = try { FileChannel.open(lockFile, *options) } catch (_: Exception) { error(STATE_UNAVAILABLE) }
        channel.use {
            it.lock().use { operation() }
        }
    }

    private fun DataInputStream.mark(): CatalogMark {
        val revision = readLong()
        val digest = readUTF()
        check(revision in 1..9_999_999_999_999_999L && hex.matches(digest)) { STATE_UNAVAILABLE }
        return CatalogMark(revision, digest)
    }

    private fun DataOutputStream.mark(mark: CatalogMark) { writeLong(mark.revision); writeUTF(mark.digest) }

    companion object {
        // java.nio throws OverlappingFileLockException for two concurrent locks in one JVM.
        private val processLock = Any()
        private const val MAGIC = 0x43414331 // CAC1
        private const val MAX_SIZE = 4096
        private val hex = Regex("[0-9a-f]{64}")
        private val sourceId = Regex("[a-z][a-z0-9-]{0,31}")
        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

internal const val MAX_CATALOG_SOURCES = 16
internal const val STATE_UNAVAILABLE = "CATALOG_STATE_UNAVAILABLE"
internal const val STATE_CHANGED = "CATALOG_STATE_CHANGED"

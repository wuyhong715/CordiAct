package com.cordiact.core.attachment

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Byte storage for attachments.
 *
 * Metadata lives in the conversation database; blobs live here. The split lets the database
 * transaction stay small and prevents base64 from ever entering a message row.
 */
interface AttachmentBlobStore {
    /** Starts a staged write that will only become visible on [StagedAttachmentBlob.commit]. */
    fun createStaged(workspaceId: String, hardLimitBytes: Long): StagedAttachmentBlob

    fun open(workspaceId: String, blobKey: String): InputStream

    fun length(workspaceId: String, blobKey: String): Long

    fun exists(workspaceId: String, blobKey: String): Boolean

    /** Deletes one committed blob. Returns true when a file was removed. */
    fun delete(workspaceId: String, blobKey: String): Boolean

    /** Removes abandoned `.part` files older than [olderThanMillis]; returns the count removed. */
    fun sweepStaging(workspaceId: String, olderThanMillis: Long, nowMillis: Long): Int
}

interface StagedAttachmentBlob {
    val token: String
    val bytesWritten: Long

    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Flushes, fsyncs and atomically publishes the object. Returns its opaque blob key. */
    fun commit(): String

    /** Discards the staged file. Safe to call more than once. */
    fun abort()
}

/**
 * App-private, per-Workspace blob store.
 *
 * Layout under [root]:
 * `ws-<hash>/v1/staging/<random>.part` and `ws-<hash>/v1/objects/<opaque-key>`.
 * The workspace directory is keyed by a digest, so a hostile workspace id cannot escape the root.
 * Blob keys are generated here and never derived from user input.
 */
class LocalAttachmentBlobStore(private val root: File) : AttachmentBlobStore {
    init {
        require(root.isAbsolute) { "The blob store root must be absolute" }
    }

    override fun createStaged(workspaceId: String, hardLimitBytes: Long): StagedAttachmentBlob {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank" }
        require(hardLimitBytes > 0) { "hardLimitBytes must be positive" }
        val workspaceDir = workspaceDirectory(workspaceId)
        val stagingDir = File(workspaceDir, "v1/staging").apply { mkdirsOrFail() }
        val file = File.createTempFile("import-", ".part", stagingDir)
        return LocalStagedBlob(file, workspaceDir, hardLimitBytes)
    }

    override fun open(workspaceId: String, blobKey: String): InputStream {
        val file = objectFile(workspaceId, blobKey)
        if (!file.isFile) {
            throw AttachmentException(AttachmentErrorCode.UNKNOWN_ATTACHMENT, "Attachment blob is missing")
        }
        return FileInputStream(file)
    }

    override fun length(workspaceId: String, blobKey: String): Long {
        val file = objectFile(workspaceId, blobKey)
        if (!file.isFile) {
            throw AttachmentException(AttachmentErrorCode.UNKNOWN_ATTACHMENT, "Attachment blob is missing")
        }
        return file.length()
    }

    override fun exists(workspaceId: String, blobKey: String): Boolean = objectFile(workspaceId, blobKey).isFile

    override fun delete(workspaceId: String, blobKey: String): Boolean {
        val file = objectFile(workspaceId, blobKey)
        return file.isFile && file.delete()
    }

    override fun sweepStaging(workspaceId: String, olderThanMillis: Long, nowMillis: Long): Int {
        val stagingDir = File(workspaceDirectory(workspaceId), "v1/staging")
        val candidates = stagingDir.listFiles { file -> file.isFile && file.name.endsWith(".part") } ?: return 0
        var removed = 0
        for (file in candidates) {
            if (nowMillis - file.lastModified() >= olderThanMillis && file.delete()) removed++
        }
        return removed
    }

    private fun objectFile(workspaceId: String, blobKey: String): File {
        require(blobKey.matches(OPAQUE_KEY)) { "Invalid blob key" }
        return File(File(workspaceDirectory(workspaceId), "v1/objects"), blobKey)
    }

    private fun workspaceDirectory(workspaceId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(workspaceId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(32)
        return File(root, "ws-$digest")
    }

    private class LocalStagedBlob(
        private val stagedFile: File,
        private val workspaceDir: File,
        private val hardLimitBytes: Long,
    ) : StagedAttachmentBlob {
        private val stream = FileOutputStream(stagedFile)
        private var written = 0L
        private var closed = false

        override val token: String = stagedFile.name
        override val bytesWritten: Long get() = written

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            check(!closed) { "The staged blob is already closed" }
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            if (written + length > hardLimitBytes) {
                throw AttachmentException(
                    AttachmentErrorCode.FILE_TOO_LARGE,
                    "The file exceeds the $hardLimitBytes byte import limit",
                )
            }
            stream.write(buffer, offset, length)
            written += length
        }

        override fun commit(): String {
            check(!closed) { "The staged blob is already closed" }
            stream.flush()
            stream.fd.sync()
            stream.close()
            closed = true
            val objectsDir = File(workspaceDir, "v1/objects").apply { mkdirsOrFail() }
            val blobKey = UUID.randomUUID().toString().replace("-", "")
            val target = File(objectsDir, blobKey)
            try {
                Files.move(stagedFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(stagedFile.toPath(), target.toPath())
            }
            return blobKey
        }

        override fun abort() {
            if (!closed) {
                runCatching { stream.close() }
                closed = true
            }
            stagedFile.delete()
        }
    }

    private companion object {
        val OPAQUE_KEY = Regex("[A-Za-z0-9]{8,64}")
    }
}

private fun File.mkdirsOrFail() {
    if (!isDirectory && !mkdirs() && !isDirectory) {
        throw AttachmentException(AttachmentErrorCode.STORAGE_FAILURE, "Cannot create $path", null)
    }
}

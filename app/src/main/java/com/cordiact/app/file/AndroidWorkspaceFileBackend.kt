package com.cordiact.app.file

import com.cordiact.core.file.MAX_DIRECTORY_PAGE
import com.cordiact.core.file.MAX_FILE_BYTES
import com.cordiact.core.file.MAX_READ_BYTES
import com.cordiact.core.file.MAX_RELATIVE_PATH_BYTES
import com.cordiact.core.file.MAX_RELATIVE_PATH_DEPTH
import com.cordiact.core.file.WorkspaceFileBackend
import com.cordiact.core.file.WorkspaceFileContent
import com.cordiact.core.file.WorkspaceFileEntry
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileMutation
import com.cordiact.core.file.WorkspaceFilePage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

/** App-private workspace filesystem implementation. All model paths remain workspace-relative. */
class AndroidWorkspaceFileBackend : WorkspaceFileBackend {
    override fun list(root: File, relativeDirectory: String, cursor: String?, limit: Int): WorkspaceFilePage = synchronized(lock) {
        require(limit in 1..MAX_DIRECTORY_PAGE)
        val directory = resolve(root, relativeDirectory, allowRoot = true)
        if (!directory.exists()) throw failure(WorkspaceFileException.Code.FILE_NOT_FOUND, "Directory does not exist")
        if (!directory.isDirectory) throw failure(WorkspaceFileException.Code.NOT_A_REGULAR_FILE, "Path is not a directory")
        val after = cursor?.let { decodeCursor(it, relativeDirectory) }
        val entries = directory.listFiles()?.asSequence()
            ?.filterNot { Files.isSymbolicLink(it.toPath()) }
            // The Workspace root is a git repository, so .git and other hidden entries must
            // appear in a listing, since .git contains objects for deleted content.
            ?.filterNot { it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?.filter { after == null || it.name > after }
            ?.take(limit + 1)
            ?.toList()
            ?: throw failure(WorkspaceFileException.Code.STORAGE_FAILURE, "Could not list directory")
        val hasMore = entries.size > limit
        val page = entries.take(limit).map { file ->
            resolve(root, join(relativeDirectory, file.name), allowRoot = false)
            val directoryEntry = file.isDirectory
            if (!directoryEntry && !file.isFile) {
                throw failure(WorkspaceFileException.Code.NOT_A_REGULAR_FILE, "Unsupported file type")
            }
            WorkspaceFileEntry(
                name = file.name,
                directory = directoryEntry,
                sizeBytes = if (directoryEntry) 0 else file.length(),
                revision = null,
            )
        }
        WorkspaceFilePage(page, if (hasMore && page.isNotEmpty()) encodeCursor(relativeDirectory, page.last().name) else null)
    }

    override fun read(root: File, relativePath: String, offset: Long, maxBytes: Int): WorkspaceFileContent = synchronized(lock) {
        if (maxBytes !in 1..MAX_READ_BYTES || offset < 0) {
            throw failure(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED, "Invalid read bounds")
        }
        val file = resolve(root, relativePath, allowRoot = false)
        if (!file.exists()) throw failure(WorkspaceFileException.Code.FILE_NOT_FOUND, "File does not exist")
        if (!file.isFile) throw failure(WorkspaceFileException.Code.NOT_A_REGULAR_FILE, "Path is not a regular file")
        if (file.length() > MAX_FILE_BYTES) throw failure(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED, "File size limit exceeded")
        if (offset > file.length()) throw failure(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED, "Read offset exceeds file size")
        val bytes = FileInputStream(file).use { input ->
            var remaining = offset
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped <= 0) throw failure(WorkspaceFileException.Code.STORAGE_FAILURE, "Could not seek file")
                remaining -= skipped
            }
            val buffer = ByteArray(minOf(maxBytes.toLong(), file.length() - offset).toInt())
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) continue
                count += read
            }
            buffer.copyOf(count)
        }
        WorkspaceFileContent(bytes, sha256(file.readBytes()))
    }

    override fun write(root: File, relativePath: String, bytes: ByteArray, expectedRevision: String?): WorkspaceFileMutation = synchronized(lock) {
        if (bytes.size > MAX_FILE_BYTES) throw failure(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED, "File size limit exceeded")
        val target = resolve(root, relativePath, allowRoot = false)
        ensureParent(root, target.parentFile ?: throw invalidPath())
        val exists = Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (exists) {
            if (!target.isFile) throw failure(WorkspaceFileException.Code.NOT_A_REGULAR_FILE, "Path is not a regular file")
            verifyRevision(target, expectedRevision)
        } else if (expectedRevision != null) {
            throw failure(WorkspaceFileException.Code.REVISION_CONFLICT, "File revision does not match")
        }
        val temp = try {
            Files.createTempFile(target.parentFile.toPath(), ".cordiact-", ".tmp").toFile()
        } catch (failure: Exception) {
            throw storageFailure(failure)
        }
        try {
            FileOutputStream(temp).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            resolve(root, relativePath, allowRoot = false)
            moveReplacing(temp, target)
            WorkspaceFileMutation(sha256(bytes))
        } catch (failure: WorkspaceFileException) {
            throw failure
        } catch (failure: Exception) {
            throw storageFailure(failure)
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    override fun delete(root: File, relativePath: String, expectedRevision: String): WorkspaceFileMutation = synchronized(lock) {
        val file = resolve(root, relativePath, allowRoot = false)
        if (!file.exists()) throw failure(WorkspaceFileException.Code.FILE_NOT_FOUND, "File does not exist")
        if (!file.isFile) throw failure(WorkspaceFileException.Code.NOT_A_REGULAR_FILE, "Path is not a regular file")
        verifyRevision(file, expectedRevision)
        if (!file.delete()) throw failure(WorkspaceFileException.Code.STORAGE_FAILURE, "Could not delete file")
        WorkspaceFileMutation()
    }

    override fun move(root: File, sourceRelativePath: String, destinationRelativePath: String, expectedRevision: String): WorkspaceFileMutation = synchronized(lock) {
        val source = resolve(root, sourceRelativePath, allowRoot = false)
        val destination = resolve(root, destinationRelativePath, allowRoot = false)
        if (!source.exists()) throw failure(WorkspaceFileException.Code.FILE_NOT_FOUND, "Source file does not exist")
        if (!source.isFile) throw failure(WorkspaceFileException.Code.NOT_A_REGULAR_FILE, "Source is not a regular file")
        if (destination.exists()) throw failure(WorkspaceFileException.Code.REVISION_CONFLICT, "Destination already exists")
        verifyRevision(source, expectedRevision)
        ensureParent(root, destination.parentFile ?: throw invalidPath())
        try {
            resolve(root, destinationRelativePath, allowRoot = false)
            Files.move(source.toPath(), destination.toPath())
        } catch (failure: WorkspaceFileException) {
            throw failure
        } catch (failure: Exception) {
            throw storageFailure(failure)
        }
        WorkspaceFileMutation(expectedRevision)
    }

    private val lock = Any()

    private fun resolve(root: File, relativePath: String, allowRoot: Boolean): File {
        validatePath(relativePath, allowRoot)
        val rootPath = root.canonicalFile.toPath().normalize()
        if (Files.isSymbolicLink(root.toPath()) || Files.isSymbolicLink(rootPath)) {
            throw failure(WorkspaceFileException.Code.SYMLINK_NOT_ALLOWED, "Workspace root is a symbolic link")
        }
        var current = rootPath.toFile()
        if (relativePath.isNotEmpty()) {
            relativePath.split('/').forEach { segment ->
                current = File(current, segment)
                if (Files.isSymbolicLink(current.toPath())) {
                    throw failure(WorkspaceFileException.Code.SYMLINK_NOT_ALLOWED, "Symbolic links are not allowed")
                }
                val canonical = current.canonicalFile.toPath().normalize()
                if (!canonical.startsWith(rootPath) || canonical == rootPath && !allowRoot) {
                    throw failure(WorkspaceFileException.Code.PATH_OUTSIDE_WORKSPACE, "Path is outside the workspace")
                }
            }
        }
        return current
    }

    private fun ensureParent(root: File, parent: File) {
        val rootPath = root.canonicalFile.toPath().normalize()
        val relative = rootPath.relativize(parent.toPath().normalize())
        var current = root
        for (segment in relative) {
            current = File(current, segment.toString())
            if (Files.isSymbolicLink(current.toPath())) throw failure(WorkspaceFileException.Code.SYMLINK_NOT_ALLOWED, "Symbolic links are not allowed")
            if (!current.exists() && !current.mkdir()) throw failure(WorkspaceFileException.Code.STORAGE_FAILURE, "Could not create parent directory")
            if (!current.isDirectory || !current.canonicalFile.toPath().normalize().startsWith(rootPath)) {
                throw failure(WorkspaceFileException.Code.PATH_OUTSIDE_WORKSPACE, "Parent directory is outside the workspace")
            }
        }
    }

    private fun verifyRevision(file: File, expectedRevision: String?) {
        if (file.length() > MAX_FILE_BYTES) {
            throw failure(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED, "File size limit exceeded")
        }
        if (expectedRevision == null || expectedRevision != sha256(file.readBytes())) {
            throw failure(WorkspaceFileException.Code.REVISION_CONFLICT, "File revision does not match")
        }
    }

    private fun validatePath(path: String, allowRoot: Boolean) {
        val segments = path.split('/')
        if (path.toByteArray(Charsets.UTF_8).size > MAX_RELATIVE_PATH_BYTES ||
            path.startsWith('/') || path.contains('\\') || path.contains('\u0000') ||
            (!allowRoot && path.isEmpty()) || segments.size > MAX_RELATIVE_PATH_DEPTH ||
            (path.isNotEmpty() && segments.any { it.isEmpty() || it == "." || it == ".." })) {
            throw invalidPath()
        }
        if (path.isNotEmpty() && segments.any { it.startsWith(".") }) {
            throw failure(WorkspaceFileException.Code.HIDDEN_PATH_NOT_ALLOWED, "Hidden paths are not accessible")
        }
    }

    private fun encodeCursor(directory: String, lastName: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$directory\u0000$lastName".toByteArray(Charsets.UTF_8))

    private fun decodeCursor(cursor: String, directory: String): String {
        return try {
            val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val separator = decoded.indexOf('\u0000')
            if (separator < 0 || decoded.substring(0, separator) != directory || separator == decoded.lastIndex) throw invalidCursor()
            decoded.substring(separator + 1)
        } catch (failure: WorkspaceFileException) {
            throw failure
        } catch (failure: Exception) {
            throw invalidCursor()
        }
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun join(directory: String, name: String): String = if (directory.isEmpty()) name else "$directory/$name"
    private fun invalidPath() = failure(WorkspaceFileException.Code.INVALID_PATH, "Invalid workspace-relative path")
    private fun invalidCursor() = failure(WorkspaceFileException.Code.CURSOR_INVALID, "Invalid directory cursor")
    private fun storageFailure(cause: Throwable) = WorkspaceFileException(WorkspaceFileException.Code.STORAGE_FAILURE, "Workspace storage operation failed").also { it.initCause(cause) }
    private fun failure(code: WorkspaceFileException.Code, message: String) = WorkspaceFileException(code, message)
}

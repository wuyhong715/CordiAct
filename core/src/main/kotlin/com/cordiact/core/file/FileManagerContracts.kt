package com.cordiact.core.file

import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginSpec
import com.cordiact.core.plugin.PluginTarget
import com.cordiact.core.workspace.WorkspaceManagerService

const val FILE_MANAGER_PLUGIN_ID = "core.file-manager"
const val FILE_MANAGER_VERSION = "0.1.0"

fun fileManagerPluginSpec() = PluginSpec(
    id = FILE_MANAGER_PLUGIN_ID,
    version = FILE_MANAGER_VERSION,
    target = PluginTarget.ANDROID_NATIVE,
    dependencies = setOf("core.workspace-manager"),
)

data class WorkspaceFileEntry(val name: String, val directory: Boolean, val sizeBytes: Long, val revision: String?)
data class WorkspaceFilePage(val entries: List<WorkspaceFileEntry>, val nextCursor: String?)
data class WorkspaceFileContent(val bytes: ByteArray, val revision: String) {
    override fun equals(other: Any?): Boolean = other is WorkspaceFileContent &&
        bytes.contentEquals(other.bytes) && revision == other.revision

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + revision.hashCode()
}
data class WorkspaceFileMutation(val revision: String? = null)

interface WorkspaceFileBackend {
    fun list(root: java.io.File, relativeDirectory: String, cursor: String?, limit: Int): WorkspaceFilePage
    fun read(root: java.io.File, relativePath: String, offset: Long, maxBytes: Int): WorkspaceFileContent
    fun write(root: java.io.File, relativePath: String, bytes: ByteArray, expectedRevision: String?): WorkspaceFileMutation
    fun delete(root: java.io.File, relativePath: String, expectedRevision: String): WorkspaceFileMutation
    fun move(root: java.io.File, sourceRelativePath: String, destinationRelativePath: String, expectedRevision: String): WorkspaceFileMutation
}

interface WorkspaceFileService {
    fun list(workspaceId: String, relativeDirectory: String = "", cursor: String? = null, limit: Int = 100): WorkspaceFilePage
    fun read(workspaceId: String, relativePath: String, offset: Long = 0, maxBytes: Int = MAX_READ_BYTES): WorkspaceFileContent
    fun write(workspaceId: String, relativePath: String, bytes: ByteArray, expectedRevision: String? = null): WorkspaceFileMutation
    fun delete(workspaceId: String, relativePath: String, expectedRevision: String): WorkspaceFileMutation
    fun move(workspaceId: String, sourceRelativePath: String, destinationRelativePath: String, expectedRevision: String): WorkspaceFileMutation
}

class WorkspaceFileException(val code: Code, message: String) : RuntimeException(message) {
    enum class Code {
        WORKSPACE_UNAVAILABLE,
        INVALID_PATH,
        PATH_OUTSIDE_WORKSPACE,
        SYMLINK_NOT_ALLOWED,
        FILE_NOT_FOUND,
        NOT_A_REGULAR_FILE,
        SIZE_LIMIT_EXCEEDED,
        REVISION_CONFLICT,
        CURSOR_INVALID,
        STORAGE_FAILURE,
        INVALID_TEXT_ENCODING,
    }
}

const val MAX_FILE_BYTES = 10 * 1024 * 1024
const val MAX_READ_BYTES = 1024 * 1024
const val MAX_DIRECTORY_PAGE = 500
const val MAX_RELATIVE_PATH_BYTES = 4096
const val MAX_RELATIVE_PATH_DEPTH = 64

/** Protected service plugin; file access remains scoped through the validated Workspace root. */
class FileManagerPlugin(
    private val workspaces: WorkspaceManagerService,
    private val backend: WorkspaceFileBackend,
) : ManagedPlugin, WorkspaceFileService {
    override val spec: PluginSpec = fileManagerPluginSpec()
    @Volatile private var enabled = false
    private val operationLock = Any()

    override fun onEnable() = synchronized(operationLock) { enabled = true }
    override fun onDisable() = synchronized(operationLock) { enabled = false }

    override fun list(workspaceId: String, relativeDirectory: String, cursor: String?, limit: Int): WorkspaceFilePage =
        withRoot(workspaceId) { root ->
            if (limit !in 1..MAX_DIRECTORY_PAGE) {
                throw fileFailure(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED, "Directory page limit exceeded")
            }
            validatePath(relativeDirectory, allowRoot = true)
            backend.list(root, relativeDirectory, cursor, limit)
        }

    override fun read(workspaceId: String, relativePath: String, offset: Long, maxBytes: Int): WorkspaceFileContent =
        withRoot(workspaceId) { root ->
            validatePath(relativePath, allowRoot = false)
            if (offset < 0 || maxBytes !in 1..MAX_READ_BYTES) {
                throw fileFailure(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED, "Invalid read bounds")
            }
            backend.read(root, relativePath, offset, maxBytes)
        }

    override fun write(workspaceId: String, relativePath: String, bytes: ByteArray, expectedRevision: String?): WorkspaceFileMutation =
        withRoot(workspaceId) { root ->
            validatePath(relativePath, allowRoot = false)
            if (bytes.size > MAX_FILE_BYTES) {
                throw fileFailure(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED, "File size limit exceeded")
            }
            backend.write(root, relativePath, bytes.copyOf(), expectedRevision)
        }

    override fun delete(workspaceId: String, relativePath: String, expectedRevision: String): WorkspaceFileMutation =
        withRoot(workspaceId) { root ->
            validatePath(relativePath, allowRoot = false)
            backend.delete(root, relativePath, expectedRevision)
        }

    override fun move(workspaceId: String, sourceRelativePath: String, destinationRelativePath: String, expectedRevision: String): WorkspaceFileMutation =
        withRoot(workspaceId) { root ->
            validatePath(sourceRelativePath, allowRoot = false)
            validatePath(destinationRelativePath, allowRoot = false)
            backend.move(root, sourceRelativePath, destinationRelativePath, expectedRevision)
        }

    private fun <T> withRoot(workspaceId: String, block: (java.io.File) -> T): T {
        synchronized(operationLock) {
            if (!enabled) throw fileFailure(WorkspaceFileException.Code.WORKSPACE_UNAVAILABLE, "File Manager is unavailable")
            val root = try {
                workspaces.resolveSessionWorkspace(workspaceId)
                workspaces.resolveRoot(workspaceId)
            } catch (failure: Exception) {
                throw WorkspaceFileException(WorkspaceFileException.Code.WORKSPACE_UNAVAILABLE, "Workspace is unavailable")
            }
            try {
                block(root)
            } catch (failure: WorkspaceFileException) {
                throw failure
            } catch (failure: Exception) {
                throw WorkspaceFileException(WorkspaceFileException.Code.STORAGE_FAILURE, "Workspace storage operation failed")
            }
        }
    }

    private fun validatePath(path: String, allowRoot: Boolean) {
        val bytes = path.toByteArray(Charsets.UTF_8).size
        val segments = path.split('/')
        if (bytes > MAX_RELATIVE_PATH_BYTES || (!allowRoot && path.isEmpty()) ||
            path.startsWith('/') || path.contains('\\') || path.contains('\u0000') ||
            segments.size > MAX_RELATIVE_PATH_DEPTH || segments.any { it == "." || it == ".." || it.isEmpty() } && path.isNotEmpty()) {
            throw fileFailure(WorkspaceFileException.Code.INVALID_PATH, "Invalid workspace-relative path")
        }
    }

    private fun fileFailure(code: WorkspaceFileException.Code, message: String) = WorkspaceFileException(code, message)
}

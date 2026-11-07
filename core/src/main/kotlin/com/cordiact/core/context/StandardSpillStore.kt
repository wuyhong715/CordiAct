package com.cordiact.core.context

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Standard filesystem-backed implementation of [SpillStore].
 *
 * Implements strict path traversal prevention via canonical path validation.
 */
class StandardSpillStore(
    private val rootDir: File,
    private val maxFileSizeBytes: Long = 8 * 1024 * 1024L, // 8MB limit
) : SpillStore {

    init {
        rootDir.mkdirs()
    }

    override fun saveSpill(sessionId: String, toolName: String, content: String): String {
        val safeSessionId = sanitizePathSegment(sessionId)
        val safeToolName = sanitizePathSegment(toolName)
        val sessionDir = File(rootDir, safeSessionId)
        sessionDir.mkdirs()

        val sha8 = hashPrefix(content)
        val fileName = "${System.currentTimeMillis()}-$safeToolName-$sha8.txt"
        val targetFile = File(sessionDir, fileName)

        // Prevent symlink or escape
        validateContained(targetFile)

        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxFileSizeBytes) {
            targetFile.outputStream().use { out ->
                out.write(bytes, 0, maxFileSizeBytes.toInt())
                out.write("\n...[file truncated at 8MB limit]...\n".toByteArray(Charsets.UTF_8))
            }
        } else {
            targetFile.writeBytes(bytes)
        }

        return "spill://$safeSessionId/$fileName"
    }

    override fun readSpill(spillUri: String, offset: Long, limitChars: Int): SpillReadResult? {
        val file = resolveFile(spillUri) ?: return null
        if (!file.exists() || !file.isFile) return null

        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limitChars.coerceIn(1, 65_536)

        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            if (safeOffset >= fileLength) {
                return SpillReadResult(
                    content = "",
                    offset = safeOffset,
                    bytesRead = 0,
                    totalChars = fileLength,
                    hasMore = false,
                )
            }

            raf.seek(safeOffset)
            val toRead = ((fileLength - safeOffset).coerceAtMost(safeLimit.toLong())).toInt()
            val buffer = ByteArray(toRead)
            val bytesRead = raf.read(buffer)
            if (bytesRead <= 0) {
                return SpillReadResult(
                    content = "",
                    offset = safeOffset,
                    bytesRead = 0,
                    totalChars = fileLength,
                    hasMore = false,
                )
            }

            val readContent = String(buffer, 0, bytesRead, Charsets.UTF_8)
            val hasMore = (safeOffset + bytesRead) < fileLength

            return SpillReadResult(
                content = readContent,
                offset = safeOffset,
                bytesRead = bytesRead,
                totalChars = fileLength,
                hasMore = hasMore,
            )
        }
    }

    fun cleanupSession(sessionId: String) {
        val safeSessionId = sanitizePathSegment(sessionId)
        val sessionDir = File(rootDir, safeSessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    private fun resolveFile(spillUri: String): File? {
        if (!spillUri.startsWith("spill://")) return null
        val path = spillUri.removePrefix("spill://")
        val parts = path.split('/', limit = 2)
        if (parts.size != 2) return null
        val safeSessionId = sanitizePathSegment(parts[0])
        val safeFileName = sanitizePathSegment(parts[1])

        val target = File(File(rootDir, safeSessionId), safeFileName)
        return try {
            validateContained(target)
            target
        } catch (_: SecurityException) {
            null
        }
    }

    private fun validateContained(file: File) {
        val canonicalRoot = rootDir.canonicalPath
        val canonicalFile = file.canonicalPath
        if (!canonicalFile.startsWith(canonicalRoot + File.separator) && canonicalFile != canonicalRoot) {
            throw SecurityException("Path traversal attempt detected: $file escapes root $rootDir")
        }
    }

    private fun sanitizePathSegment(segment: String): String {
        return segment.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(64).ifBlank { "unknown" }
    }

    private fun hashPrefix(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.take(4).joinToString("") { "%02x".format(it) }
    }
}

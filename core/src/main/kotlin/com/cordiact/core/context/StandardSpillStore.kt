package com.cordiact.core.context

import java.io.File
import java.io.PushbackReader
import java.io.RandomAccessFile
import java.util.UUID

/** Private UTF-8 spill files, with distinct character and byte paging contracts. */
class StandardSpillStore(
    private val rootDir: File,
    private val maxFileSizeBytes: Long = 8 * 1024 * 1024L,
) : SpillStore {
    init {
        require(maxFileSizeBytes in 128..Int.MAX_VALUE.toLong())
        rootDir.mkdirs()
    }

    @Synchronized
    override fun saveSpill(sessionId: String, toolName: String, content: String): String =
        appendSpill(sessionId, toolName, null, content).uri

    @Synchronized
    override fun appendSpill(sessionId: String, toolName: String, uri: String?, content: String): SpillAppend {
        val owner = sanitizePathSegment(sessionId)
        val output = uri ?: "spill://$owner/${sanitizePathSegment(toolName).take(23)}-${UUID.randomUUID()}.txt"
        require(output.startsWith("spill://$owner/"))
        val file = requireNotNull(resolveFile(output))
        file.parentFile?.mkdirs()
        val marker = "\n...[spill reached its size limit]...\n".toByteArray()
        val capacity = maxFileSizeBytes - marker.size
        val size = file.length()
        if (size >= capacity) return SpillAppend(output, size, true)
        val data = content.toByteArray(Charsets.UTF_8)
        val truncated = size + data.size >= capacity
        var count = minOf(data.size.toLong(), capacity - size).toInt()
        while (count in 1 until data.size && isContinuation(data[count].toInt())) count--
        file.outputStreamAppend().use {
            it.write(data, 0, count)
            if (truncated) it.write(marker)
        }
        return SpillAppend(output, file.length(), truncated)
    }

    @Synchronized
    override fun readSpill(spillUri: String, offset: Long, limitChars: Int): SpillReadResult? {
        val file = resolveFile(spillUri)?.takeIf(File::isFile) ?: return null
        val start = offset.coerceAtLeast(0)
        val limit = limitChars.coerceIn(1, 20_000)
        val page = StringBuilder()
        var total = 0L
        PushbackReader(file.reader(Charsets.UTF_8).buffered(), 1).use { reader ->
            while (true) {
                val first = reader.read().takeIf { it >= 0 }?.toChar() ?: break
                val include = total >= start && total - start < limit
                if (include) page.append(first)
                if (first.isHighSurrogate()) {
                    val next = reader.read()
                    if (next >= 0 && next.toChar().isLowSurrogate()) {
                        if (include) page.append(next.toChar())
                    } else if (next >= 0) reader.unread(next)
                }
                total++
            }
        }
        val text = page.toString()
        val chars = text.codePointCount(0, text.length)
        return SpillReadResult(text, start.coerceAtMost(total), text.toByteArray().size, total, chars,
            start < total && chars.toLong() < total - start)
    }

    @Synchronized
    override fun readSpillBytes(spillUri: String, offset: Long, limitBytes: Int): SpillByteRead? {
        val file = resolveFile(spillUri)?.takeIf(File::isFile) ?: return null
        return RandomAccessFile(file, "r").use { reader ->
            val end = reader.length()
            var start = offset.coerceIn(0, end)
            // Arbitrary legacy offsets may point into a UTF-8 sequence. Resume at the next boundary.
            while (start < end) {
                reader.seek(start)
                if (!isContinuation(reader.read())) break
                start++
            }
            var next = start + minOf(end - start, limitBytes.coerceIn(4, 64_000).toLong())
            while (next in (start + 1) until end) {
                reader.seek(next)
                if (!isContinuation(reader.read())) break
                next--
            }
            val bytes = ByteArray((next - start).toInt())
            reader.seek(start)
            reader.readFully(bytes)
            SpillByteRead(String(bytes, Charsets.UTF_8), next, next < end)
        }
    }

    @Synchronized
    fun cleanupSession(sessionId: String) {
        val dir = File(rootDir, sanitizePathSegment(sessionId))
        validateContained(dir)
        if (!java.nio.file.Files.isSymbolicLink(dir.toPath())) dir.deleteRecursively()
    }

    @Synchronized
    fun pruneOlderThan(cutoff: Long, preserve: Set<String> = emptySet()) {
        for (dir in rootDir.listFiles().orEmpty()) {
            if (!dir.isDirectory || java.nio.file.Files.isSymbolicLink(dir.toPath())) continue
            for (file in dir.listFiles().orEmpty()) {
                if (file.isFile && file.name.endsWith(".txt") && file.lastModified() < cutoff &&
                    !java.nio.file.Files.isSymbolicLink(file.toPath()) && "spill://${dir.name}/${file.name}" !in preserve) file.delete()
            }
            dir.delete() // only succeeds when empty
        }
    }

    private fun isContinuation(byte: Int) = byte and 0xc0 == 0x80
    private fun File.outputStreamAppend() = java.io.FileOutputStream(this, true)

    private fun resolveFile(spillUri: String): File? {
        if (!spillUri.startsWith("spill://")) return null
        val path = spillUri.removePrefix("spill://")
        val parts = path.split('/', limit = 2)
        if (parts.size != 2) return null
        if (parts.any { it.isBlank() || it == "." || it == ".." || it != sanitizePathSegment(it) }) return null
        val safeSessionId = parts[0]
        val safeFileName = parts[1]

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
        if (!canonicalFile.startsWith(canonicalRoot + File.separator)) {
            throw SecurityException("Path traversal attempt detected: $file escapes root $rootDir")
        }
    }

    private fun sanitizePathSegment(segment: String): String {
        return segment.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(64).takeUnless { it.isBlank() || it == "." || it == ".." } ?: "unknown"
    }

}

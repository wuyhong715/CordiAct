package com.cordiact.core.automation

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock

/** OS-held runtime ownership, automatically released on process death; no time-based theft.
 * All writers of the associated task store must use the same lease path. A lease does not
 * make already-constructed AutomationService instances reload their in-memory snapshots.
 */
class AutomationExecutionLease private constructor(
    private val handle: RandomAccessFile,
    private val lock: FileLock,
) : Closeable {
    @Synchronized override fun close() {
        try { if (lock.isValid) lock.release() } finally { handle.close() }
    }

    companion object {
        fun acquire(file: File): AutomationExecutionLease {
            val parent = requireNotNull(file.absoluteFile.parentFile)
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create lease directory" }
            val handle = RandomAccessFile(file, "rw")
            try {
                val lock = handle.channel.tryLock() ?: error("Automation runtime already owned by another process")
                return AutomationExecutionLease(handle, lock)
            } catch (error: Throwable) {
                handle.close()
                throw error
            }
        }
    }
}

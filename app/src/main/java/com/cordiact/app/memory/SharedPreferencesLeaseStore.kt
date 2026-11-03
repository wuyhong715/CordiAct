package com.cordiact.app.memory

import android.content.Context
import com.cordiact.core.memory.ExtractionLease
import com.cordiact.core.memory.ExtractionLeaseStore

/**
 * Durable lease storage on top of SharedPreferences.
 *
 * Leases must survive process death, otherwise a crash mid-extraction leaves no record and a
 * second run either duplicates the work or the session is never reclaimed.
 *
 * SharedPreferences rather than SQLite because a lease is a single small value with a
 * compare-and-set shape; a table would add a migration for no gain. Writes are synchronized
 * because [claim] must decide expiry and commit as one step, which is what the contract
 * requires.
 */
class SharedPreferencesLeaseStore(context: Context) : ExtractionLeaseStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    override fun active(workspaceId: String, sessionId: String): ExtractionLease? = synchronized(lock) {
        read(key(workspaceId, sessionId))
    }

    override fun claim(lease: ExtractionLease): Boolean = synchronized(lock) {
        val k = key(lease.workspaceId, lease.sessionId)
        val existing = read(k)
        // Refuse only when a different owner holds an unexpired lease. An expired lease is
        // always replaceable; rejecting whenever a row exists would let a crashed owner hold
        // the session permanently.
        if (existing != null && existing.owner != lease.owner && !existing.isExpired(lease.claimedAtMillis)) {
            return false
        }
        prefs.edit().putString(k, encode(lease)).commit()
        true
    }

    override fun release(workspaceId: String, sessionId: String, owner: String) {
        synchronized(lock) {
            val k = key(workspaceId, sessionId)
            val existing = read(k) ?: return
            // A stale run must not free a lease a live owner now holds.
            if (existing.owner != owner) return
            prefs.edit().remove(k).commit()
        }
    }

    private fun read(key: String): ExtractionLease? {
        val raw = prefs.getString(key, null) ?: return null
        val parts = raw.split('|')
        if (parts.size != 5) return null
        return try {
            ExtractionLease(
                workspaceId = parts[0],
                sessionId = parts[1],
                owner = parts[2],
                claimedAtMillis = parts[3].toLong(),
                expiresAtMillis = parts[4].toLong(),
            )
        } catch (e: Exception) {
            // A corrupt entry must not wedge the session forever.
            null
        }
    }

    private fun encode(lease: ExtractionLease): String = listOf(
        lease.workspaceId,
        lease.sessionId,
        lease.owner,
        lease.claimedAtMillis.toString(),
        lease.expiresAtMillis.toString(),
    ).joinToString("|")

    private fun key(workspaceId: String, sessionId: String) = "lease|$workspaceId|$sessionId"

    private companion object {
        const val PREFS_NAME = "cordiact.memory.leases"
    }
}

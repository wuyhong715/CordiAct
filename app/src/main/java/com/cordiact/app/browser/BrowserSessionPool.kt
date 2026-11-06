package com.cordiact.app.browser

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns browser sessions.
 *
 * Sessions are independent WebViews; they are closed explicitly by
 * `browser_close`, and the pool refuses to grow past [maxSessions] so a runaway
 * model cannot exhaust memory with WebViews.
 */
class BrowserSessionPool(private val context: Context) {

    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private val counter = AtomicInteger(0)
    val maxSessions: Int = 3

    fun open(
        url: String,
        width: Int = BrowserSession.DEFAULT_WIDTH,
        height: Int = BrowserSession.DEFAULT_HEIGHT,
    ): Result<BrowserSession> {
        if (sessions.size >= maxSessions) {
            return Result.failure(IllegalStateException("Too many browser sessions; close one first"))
        }
        val id = "w${counter.incrementAndGet()}"
        val session = BrowserSession(context, id)
        session.start(initialUrl = url, width = width, height = height)
        sessions[id] = session
        return Result.success(session)
    }

    fun get(id: String): BrowserSession? = sessions[id]

    fun close(id: String): Boolean {
        val session = sessions.remove(id) ?: return false
        session.destroy()
        return true
    }

    fun closeAll() {
        sessions.keys.toList().forEach(::close)
    }

    fun ids(): List<String> = sessions.keys.sortedBy { it.removePrefix("w").toIntOrNull() ?: 0 }
}

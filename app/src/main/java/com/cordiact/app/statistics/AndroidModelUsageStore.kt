package com.cordiact.app.statistics

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cordiact.core.conversation.ModelUsage
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class AndroidModelUsageStore(
    context: Context,
    private val workspaceOf: (sessionId: String) -> String? = { null },
) : SQLiteOpenHelper(context, "model-usage.db", null, 1) {
    data class Tokens(val total: Long, val reported: Long) {
        fun display(calls: Long): String = when {
            reported == 0L -> "未知"
            reported < calls -> "$total（部分缺失）"
            else -> total.toString()
        }
    }
    data class Totals(val calls: Long, val failed: Long, val cancelled: Long, val input: Tokens, val output: Tokens)
    data class Model(val provider: String, val model: String, val totals: Totals)
    data class Call(val provider: String, val model: String, val time: Long, val status: String, val input: Long?, val output: Long?)
    data class Report(val total: Totals, val models: List<Model>, val calls: List<Call>, val more: Boolean)
    data class Health(val revision: Long = 0, val writeFailed: Boolean = false)
    private val changes = MutableStateFlow(Health())
    val health = changes.asStateFlow()
    private val pending = Channel<ModelUsage>(256)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch {
            for (usage in pending) {
                val written = runCatching {
                    val workspace = usage.workspaceId?.takeUnless(String::isBlank)
                        ?: workspaceOf(usage.sessionId)?.takeUnless(String::isBlank)
                        ?: error("Could not resolve workspace for model usage")
                    writableDatabase.insertWithOnConflict("calls", null, ContentValues().apply {
                        put("id", usage.requestId)
                        put("workspace", workspace)
                        put("provider", usage.model.providerId)
                        put("model", usage.model.modelId)
                        put("started", usage.startedAt)
                        put("status", usage.status.name)
                        put("input", usage.inputTokens)
                        put("output", usage.outputTokens)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }.isSuccess
                changes.update { Health(it.revision + 1, it.writeFailed || !written) }
            }
        }
    }

    fun enqueue(usage: ModelUsage) {
        if (pending.trySend(usage).isFailure) changes.update { it.copy(writeFailed = true) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE calls(id TEXT PRIMARY KEY NOT NULL, workspace TEXT NOT NULL, provider TEXT NOT NULL, " +
            "model TEXT NOT NULL, started INTEGER NOT NULL, status TEXT NOT NULL, input INTEGER, output INTEGER)")
        db.execSQL("CREATE INDEX calls_scope ON calls(workspace, started DESC, id DESC)")
    }

    // Version 1 is the initial schema. A missing migration must never crash startup, so an
    // unknown older version falls back to recreating the table and reporting the loss as a gap.
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    fun report(workspace: String, from: Long, until: Long, limit: Int): Report {
        require(workspace.isNotBlank() && limit in 1..500)
        val db = readableDatabase
        val args = arrayOf(workspace, from.toString(), until.toString())
        val where = "FROM calls WHERE workspace = ? AND started >= ? AND started < ?"
        val summary = "COUNT(*), COUNT(CASE WHEN status = 'FAILED' THEN 1 END), " +
            "COUNT(CASE WHEN status = 'CANCELLED' THEN 1 END), COALESCE(SUM(input), 0), COUNT(input), COALESCE(SUM(output), 0), COUNT(output)"
        fun android.database.Cursor.totals(offset: Int) = Totals(getLong(offset), getLong(offset + 1), getLong(offset + 2),
            Tokens(getLong(offset + 3), getLong(offset + 4)), Tokens(getLong(offset + 5), getLong(offset + 6)))
        val models = db.rawQuery("SELECT provider, model, $summary $where GROUP BY provider, model ORDER BY COUNT(*) DESC, provider, model", args).use {
            buildList { while (it.moveToNext()) add(Model(it.getString(0), it.getString(1), it.totals(2))) }
        }
        val total = Totals(models.sumOf { it.totals.calls }, models.sumOf { it.totals.failed }, models.sumOf { it.totals.cancelled },
            Tokens(models.sumOf { it.totals.input.total }, models.sumOf { it.totals.input.reported }),
            Tokens(models.sumOf { it.totals.output.total }, models.sumOf { it.totals.output.reported }))
        val calls = db.rawQuery("SELECT provider, model, started, status, input, output $where ORDER BY started DESC, id DESC LIMIT ?",
            args + (limit + 1).toString()).use {
            buildList { while (it.moveToNext()) add(Call(it.getString(0), it.getString(1), it.getLong(2), it.getString(3),
                if (it.isNull(4)) null else it.getLong(4), if (it.isNull(5)) null else it.getLong(5))) }
        }
        return Report(total, models, calls.take(limit), calls.size > limit)
    }
}

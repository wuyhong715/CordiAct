package com.cordiact.app.experience.model

import com.cordiact.core.conversation.SessionKind

/** One commit on the tree: a single turn of one session. */
data class GraphNode(
    val sessionId: String,
    val turn: TranscriptTurn,
    val index: Int,
) {
    val key: String get() = "$sessionId/${turn.id}"
    val at: Long get() = turn.startedAt
    val failed: Int get() = turn.tools.count { it.phase == ToolPhase.FAILED }
}

/** One branch: a user conversation, or a sub-agent forked from [parentSessionId]. */
data class GraphLane(
    val sessionId: String,
    val title: String,
    val kind: SessionKind,
    val parentSessionId: String?,
    val createdAt: Long,
    val nodes: List<GraphNode>,
    val running: Boolean,
) {
    val isAgent: Boolean get() = kind != SessionKind.USER
    /** Fork point on the parent lane: the last parent node that started before this lane existed. */
    fun forkIndexIn(parent: GraphLane): Int = parent.nodes.indexOfLast { it.at <= createdAt }.coerceAtLeast(0)
    /** Merge point on the parent lane: first parent node after this lane finished; null while open. */
    fun mergeIndexIn(parent: GraphLane): Int? {
        if (running) return null
        val end = nodes.lastOrNull()?.turn?.let { it.finishedAt ?: it.startedAt } ?: return null
        return parent.nodes.indexOfFirst { it.at >= end }.takeIf { it >= 0 }
    }
}

/** Flattened chronological order, used by the thumb rail to step node by node across lanes. */
fun List<GraphLane>.chronological(): List<GraphNode> = flatMap { it.nodes }.sortedWith(compareBy({ it.at }, { it.sessionId }))

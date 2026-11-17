package com.cordiact.core.action

/** Observes blocking approval boundaries without reading requests or changing decisions.
 * Scoped to the invoking thread; no attribution is guessed across asynchronous hops.
 */
class ObservingApprovalGateway(private val delegate: ApprovalGateway) : ApprovalGateway by delegate {
    private val observer = ThreadLocal<((Boolean) -> Unit)?>()

    fun <T> observeWaiting(callback: (Boolean) -> Unit, block: () -> T): T {
        val previous = observer.get()
        observer.set(callback)
        return try { block() } finally {
            if (previous == null) observer.remove() else observer.set(previous)
        }
    }

    override fun requestApproval(request: ApprovalRequest, timeoutMillis: Long): ApprovalDecision {
        val callback = observer.get()
        // Presentation failures must never change approval semantics.
        fun report(waiting: Boolean) { runCatching { callback?.invoke(waiting) } }
        report(true)
        return try { delegate.requestApproval(request, timeoutMillis) } finally { report(false) }
    }
}

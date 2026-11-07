package com.cordiact.app.context

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.cordiact.app.CordiActApplication

/**
 * Prepares a compaction summary off the turn's critical path.
 *
 * Compaction has one expensive part — producing the summary — and the turn is exactly when the
 * user is waiting for it. This runs while the app is idle instead, and leaves a *candidate* that
 * the next request installs only if it still applies.
 *
 * Constraints are deliberately minimal, matching the memory worker's reasoning: charging is not
 * required (fast charging is a heat source, not a good time to schedule work), but a critically
 * low battery is.
 */
class ContextCompactionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(KEY_SESSION_ID)
            ?: return Result.failure(workDataOf(KEY_REASON to "missing session id"))

        val host = applicationContext as? CordiActApplication
            ?: return Result.failure(workDataOf(KEY_REASON to "host application unavailable"))

        return runCatching {
            host.prepareCompactionCandidate(sessionId)
            Result.success()
        }.getOrElse { failure ->
            Result.failure(workDataOf(KEY_REASON to (failure.message ?: "candidate preparation failed")))
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_REASON = "reason"
        private const val WORK_PREFIX = "context-compaction-"

        /**
         * Queues one preparation per Session, replacing any earlier one.
         *
         * Replacing rather than appending: a newer request supersedes the older one, and a queue
         * of stale preparations would each be discarded at install time anyway.
         */
        fun enqueue(context: Context, sessionId: String) {
            val request = OneTimeWorkRequestBuilder<ContextCompactionWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setInputData(workDataOf(KEY_SESSION_ID to sessionId))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_PREFIX + sessionId,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

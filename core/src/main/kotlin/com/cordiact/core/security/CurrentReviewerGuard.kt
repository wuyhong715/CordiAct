package com.cordiact.core.security

/** Host-local binding check. Neither endpoint nor credential material is exposed in errors. */
class CurrentReviewerGuard(
    private val current: (String) -> ReviewerConfig?,
    private val delegate: ReviewerClient,
) : ReviewerClient {
    override fun review(snapshot: ReviewSnapshot): ReviewerOutput {
        fun checkBinding() {
            val reviewer = current(snapshot.workspaceIdentity)
                ?: throw IllegalStateException("REVIEWER_UNAVAILABLE")
            check(reviewer.providerId == snapshot.reviewerProviderId &&
                reviewer.modelId == snapshot.reviewerModelId &&
                reviewer.endpointIdentity == snapshot.reviewerEndpointIdentity &&
                reviewer.revision == snapshot.reviewerConfigRevision &&
                reviewer.credentialRevision == snapshot.reviewerCredentialRevision) { "REVIEWER_CONFIGURATION_CHANGED" }
        }
        checkBinding()
        val result = delegate.review(snapshot)
        checkBinding()
        return result
    }
}

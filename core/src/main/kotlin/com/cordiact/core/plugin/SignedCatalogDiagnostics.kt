package com.cordiact.core.plugin

/** A passive observation, never proof of current trust, checkpoint health or network availability. */
enum class SignedCatalogDiagnosticState { NOT_CONFIGURED, INVALID_POLICY, NOT_OPENED, RETIRED, NOT_REFRESHED, EXPIRED, OBSERVED }

enum class SignedReleaseDiagnostic {
    CATALOG_UNAVAILABLE, ROOT_NOT_REFRESHED, ROOT_EXPIRED, PUBLISHER_NOT_GRANTED,
    INDEX_NOT_REFRESHED, INDEX_EXPIRED, RELEASE_NOT_LISTED, RELEASE_MATCHES,
}

class SignedCatalogObservation internal constructor(
    val state: SignedCatalogDiagnosticState,
    val rootRevision: Long? = null,
    private val releaseState: (ExtensionPackageRecord) -> SignedReleaseDiagnostic = { SignedReleaseDiagnostic.CATALOG_UNAVAILABLE },
) {
    internal fun release(record: ExtensionPackageRecord): SignedReleaseDiagnostic? = record.sourceReceipt?.let { releaseState(record) }

    companion object {
        fun notConfigured() = SignedCatalogObservation(SignedCatalogDiagnosticState.NOT_CONFIGURED)
        fun invalidPolicy() = SignedCatalogObservation(SignedCatalogDiagnosticState.INVALID_POLICY)
        fun notOpened() = SignedCatalogObservation(SignedCatalogDiagnosticState.NOT_OPENED)
    }
}

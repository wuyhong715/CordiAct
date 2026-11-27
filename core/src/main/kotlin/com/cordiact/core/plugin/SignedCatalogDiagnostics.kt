package com.cordiact.core.plugin

/** A passive observation, never proof of current trust, checkpoint health or network availability. */
enum class SignedCatalogDiagnosticState { NOT_CONFIGURED, INVALID_POLICY, NOT_OPENED, RETIRED, NOT_REFRESHED, EXPIRED, OBSERVED, MULTIPLE }

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

        fun combine(catalogs: List<Pair<Set<String>, SignedCatalogObservation>>): SignedCatalogObservation {
            if (catalogs.isEmpty()) return notConfigured()
            val single = catalogs.singleOrNull()?.second
            val bySource = catalogs.flatMap { (ids, observation) -> ids.map { it to observation } }.toMap()
            return SignedCatalogObservation(single?.state ?: SignedCatalogDiagnosticState.MULTIPLE, single?.rootRevision) { record ->
                bySource[record.sourceReceipt?.sourceId]?.release(record) ?: SignedReleaseDiagnostic.CATALOG_UNAVAILABLE
            }
        }
    }
}

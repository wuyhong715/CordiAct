package com.cordiact.core.plugin

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.StringReader
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** Host-owned source; no endpoint or trust root is taken from a package or an index. */
data class CatalogSource(val id: String, val base: URI, val packagePrefix: String) {
    init {
        require(identity.matches(id) && prefix.matches(packagePrefix))
        require(base.scheme == "https" && base.host != null && base.port == -1 && base.rawUserInfo == null &&
            base.rawQuery == null && base.rawFragment == null && basePath.matches(base.rawPath) && base.normalize() == base)
    }

    internal fun artifactUri(id: String, version: String): URI = base.resolve("packages/$id/$version.json")
    internal fun trustUri(): URI = base.resolve("catalog/trust.cdx")
    internal fun indexUri(keyId: String): URI = base.resolve("catalog/indexes/$keyId.cdx")
}

/** Offline verifier. Does not fetch, install or enable anything. */
class SignedPackageCatalog private constructor(
    rootKey: PublicKey,
    sources: List<CatalogSource>,
    private val checkpoint: SignedCatalogCheckpoint?,
    private val clock: () -> Instant,
) {
    constructor(rootKey: PublicKey, sources: List<CatalogSource>, clock: () -> Instant) : this(rootKey, sources, null, clock)

    companion object {
        /** Owner provisioning only. Never a recovery fallback for missing/corrupt state. */
        fun provision(rootKey: PublicKey, sources: List<CatalogSource>, path: Path, clock: () -> Instant) {
            val catalog = SignedPackageCatalog(rootKey, sources, clock)
            SignedCatalogCheckpoint(path).create(CatalogCheckpoint(catalog.scopeId, catalog.clock().epochSecond))
        }

        /** First explicit provisioning for a trusted app build, pinned to its shipped minimum root. */
        fun provisionWithRootFloor(rootKey: PublicKey, sources: List<CatalogSource>, path: Path,
            revision: Long, digest: String, clock: () -> Instant,
        ) {
            require(natural.matches(revision.toString()) && sha256.matches(digest))
            val catalog = SignedPackageCatalog(rootKey, sources, clock)
            SignedCatalogCheckpoint(path).create(CatalogCheckpoint(catalog.scopeId, clock().epochSecond,
                root = CatalogMark(revision, digest)))
        }

        /** Refuse an existing checkpoint older than the trust floor shipped by this app build. */
        fun openWithRootFloor(rootKey: PublicKey, sources: List<CatalogSource>, path: Path,
            revision: Long, digest: String, clock: () -> Instant,
        ): SignedPackageCatalog {
            require(natural.matches(revision.toString()) && sha256.matches(digest))
            return open(rootKey, sources, path, clock).also { catalog ->
                val root = checkNotNull(catalog.saved?.root) { STATE_UNAVAILABLE }
                check(root.revision > revision || (root.revision == revision && root.digest == digest)) { STATE_UNAVAILABLE }
            }
        }

        /** Loads only rejection floors. The caller must resupply current signed documents after each restart. */
        fun open(rootKey: PublicKey, sources: List<CatalogSource>, path: Path, clock: () -> Instant): SignedPackageCatalog =
            SignedPackageCatalog(rootKey, sources, SignedCatalogCheckpoint(path), clock)
    }
    private val rootKey = requireP256(rootKey)
    private val sources = sources.associateBy(CatalogSource::id).also { byId ->
        require(byId.size == sources.size && sources.size in 1..MAX_CATALOG_SOURCES)
        require(sources.none { a -> sources.any { b -> a != b && a.packagePrefix.startsWith(b.packagePrefix) } })
    }
    private val scopeId = sha(ByteArrayOutputStream().apply {
        DataOutputStream(this).use { output ->
            output.writeUTF("CordiAct catalog checkpoint v1")
            output.writeUTF(sha(this@SignedPackageCatalog.rootKey.encoded))
            this@SignedPackageCatalog.sources.toSortedMap().values.forEach {
                output.writeUTF(it.id)
                output.writeUTF(it.base.toASCIIString())
                output.writeUTF(it.packagePrefix)
            }
        }
    }.toByteArray())
    private data class Grant(val source: String, val publisher: String, val key: PublicKey)
    private class Retirement {
        @Volatile var closed = false
    }
    private class Trust(val revision: Long, val digest: String, val expires: Instant, val grants: Map<String, Grant>) {
        val retirement = Retirement()
    }
    private class Index(
        val revision: Long, val digest: String, val expires: Instant, val scope: Trust,
        val publisherId: String, val keyId: String, val rows: List<Release>,
    ) {
        val retirement = Retirement()
    }
    private data class Release(val packageId: String, val version: String, val sha256: String)

    /** An opaque selection: copying metadata cannot mint a selected release. */
    class Selection internal constructor(
        val sourceId: String, val publisherId: String, val packageId: String, val version: String, val sha256: String,
        val artifactUri: URI, internal val generation: Any, val keyId: String,
    )
    class Verified internal constructor(
        internal val selection: Selection,
        internal val artifact: ExtensionPackageArtifact,
        val sourceReceipt: ExtensionPackageSourceReceipt,
    ) {
        val manifest get() = artifact.manifest
        val sourceId get() = selection.sourceId
        val publisherId get() = selection.publisherId
    }

    private var trust: Trust? = null
    private val indexes = mutableMapOf<String, Index>()
    private var saved: CatalogCheckpoint? = checkpoint?.open()?.also {
        check(it.scope == scopeId && it.sources.keys.all { id -> id in this.sources }) { STATE_CHANGED }
        Instant.ofEpochSecond(it.epochSecond) // Reject a malformed time bound immediately.
    }
    @Volatile private var retired = false

    /** Retires in-process evidence while preserving the durable rollback checkpoint. */
    @Synchronized
    fun retire() { retired = true }
    private val observedTime = java.util.concurrent.atomic.AtomicReference(saved?.let { Instant.ofEpochSecond(it.epochSecond) } ?: Instant.MIN)

    private fun now(): Instant {
        val wall = clock()
        return observedTime.updateAndGet { maxOf(it, wall) }
    }

    private fun <T> advance(
        update: (CatalogCheckpoint) -> CatalogCheckpoint = { it },
        validate: (Instant) -> Unit = {},
        action: (Instant) -> T,
    ): T {
        check(!retired) { STATE_UNAVAILABLE }
        val old = saved ?: return now().also(validate).let(action)
        var time: Instant = Instant.MIN
        var entered = false
        var rejected: Exception? = null
        try {
            return requireNotNull(checkpoint).advance(old, update = {
                time = now()
                val next = try {
                    validate(time)
                    update(old)
                } catch (failure: Exception) {
                    rejected = failure
                    old
                }
                next.copy(epochSecond = time.epochSecond)
            }) { committed ->
                saved = committed
                entered = true
                rejected?.let { throw it }
                action(time)
            }
        } catch (error: Exception) {
            if (!entered) {
                // Even if rename happened, do not grant using this object's old evidence.
                retired = true
                error(STATE_UNAVAILABLE)
            }
            throw error
        }
    }

    private fun freshTime(): Instant = advance { it }

    private fun requireFresh(expires: Instant, maxAge: Duration, time: Instant) {
        require(expires > time && Duration.between(time, expires) <= maxAge) { EXPIRED }
    }

    /** Missing grants are revoked in this full replacement, not implicitly inherited. */
    @Synchronized
    fun acceptTrust(bytes: ByteArray, signature: ByteArray) = acceptTrust(bytes, signature, null)

    @Synchronized
    internal fun acceptTrust(bytes: ByteArray, signature: ByteArray, refresh: RefreshTarget?) {
        if (refresh != null) checkRefresh(refresh, requireTrust = true)
        val raw = verifiedBytes(bytes, signature, rootKey, TRUST_DOMAIN)
        val doc = parse(raw) {
            var revision: Long? = null; var expires: Instant? = null; var schema = false; var grants: List<Pair<String, Grant>>? = null
            fields { name -> when (name) {
                "schema" -> { require(number() == 1L); schema = true }
                "revision" -> revision = number()
                "expires" -> expires = Instant.ofEpochSecond(number())
                "grants" -> grants = entries(64) {
                    var keyId: String? = null; var source: String? = null; var publisher: String? = null; var spki: String? = null
                    fields { field -> when (field) {
                        "keyId" -> keyId = text(identity)
                        "source" -> source = text(identity)
                        "publisher" -> publisher = text(identity)
                        "spki" -> spki = text(keyText)
                        else -> error(INVALID)
                    } }
                    val owner = need(source)
                    require(owner in sources)
                    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(need(spki))))
                    need(keyId) to Grant(owner, need(publisher), requireP256(key))
                }
                else -> error(INVALID)
            } }
            require(schema)
            val pairs = need(grants)
            val map = pairs.toMap()
            require(map.size == pairs.size && pairs.map { sha(it.second.key.encoded) }.distinct().size == pairs.size)
            require(pairs.none { sha(it.second.key.encoded) == sha(rootKey.encoded) })
            Trust(need(revision), sha(raw), need(expires), map)
        }
        val old = trust
        val floor = saved?.root ?: old?.let { CatalogMark(it.revision, it.digest) }
        val advanced = isNew(floor?.revision, floor?.digest, doc.revision, doc.digest)
        advance({ it.copy(root = CatalogMark(doc.revision, doc.digest)) },
            validate = { requireFresh(doc.expires, Duration.ofDays(30), it) }) {
            if (advanced || old == null) {
                old?.retirement?.closed = true
                trust = doc
            }
        }
    }

    /** Caller chooses the statically known source. The signature cannot choose a different endpoint. */
    @Synchronized
    fun acceptIndex(sourceId: String, keyId: String, bytes: ByteArray, signature: ByteArray) =
        acceptIndex(sourceId, keyId, bytes, signature, null)

    @Synchronized
    internal fun acceptIndex(sourceId: String, keyId: String, bytes: ByteArray, signature: ByteArray, refresh: RefreshTarget?) {
        if (refresh != null) {
            checkRefresh(refresh, requireTrust = false)
            check(refresh.sourceId == sourceId && refresh.keyId == keyId) { UNTRUSTED }
        }
        val scope = currentTrust()
        val grant = scope.grants[keyId]?.takeIf { it.source == sourceId } ?: error(UNTRUSTED)
        val source = sources.getValue(sourceId)
        val raw = verifiedBytes(bytes, signature, grant.key, INDEX_DOMAIN)
        val doc = parse(raw) {
            var revision: Long? = null; var expires: Instant? = null; var schema = false
            var indexSource: String? = null; var publisher: String? = null; var publisherKey: String? = null
            var trustRevision: Long? = null; var trustDigest: String? = null
            var releases: List<Release>? = null
            fields { name -> when (name) {
                "schema" -> { require(number() == 1L); schema = true }
                "revision" -> revision = number()
                "expires" -> expires = Instant.ofEpochSecond(number())
                "source" -> indexSource = text(identity)
                "publisher" -> publisher = text(identity)
                "keyId" -> publisherKey = text(identity)
                "trustRevision" -> trustRevision = number()
                "trustSha256" -> trustDigest = text(sha256)
                "releases" -> releases = entries(128) {
                    var id: String? = null; var version: String? = null; var digest: String? = null
                    fields { field -> when (field) {
                        "id" -> id = text(packageId).also { require(it.startsWith(source.packagePrefix)) }
                        "version" -> version = text(exactVersion)
                        "sha256" -> digest = text(sha256)
                        else -> error(INVALID)
                    } }
                    Release(need(id), need(version), need(digest))
                }
                else -> error(INVALID)
            } }
            require(schema && trustRevision == scope.revision && trustDigest == scope.digest && indexSource == sourceId &&
                publisher == grant.publisher && publisherKey == keyId)
            val rows = need(releases)
            require(rows.distinctBy { it.packageId to it.version }.size == rows.size)
            Index(need(revision), sha(raw), need(expires), scope, grant.publisher, keyId, rows)
        }
        require(doc.expires <= scope.expires) { EXPIRED }
        val old = indexes[sourceId]
        val floor = saved?.sources?.get(sourceId) ?: old?.let { CatalogMark(it.revision, it.digest) }
        val advanced = isNew(floor?.revision, floor?.digest, doc.revision, doc.digest)
        advance({ it.copy(sources = it.sources + (sourceId to CatalogMark(doc.revision, doc.digest))) },
            validate = { requireFresh(doc.expires, Duration.ofDays(7), it) }) {
            if (advanced || old?.scope !== scope) {
                old?.retirement?.closed = true
                indexes[sourceId] = doc
            }
        }
    }

    internal class RefreshTarget internal constructor(
        internal val owner: SignedPackageCatalog,
        val uri: URI,
        val sourceId: String?,
        val keyId: String?,
        internal val rootGeneration: Any?,
        internal val indexGeneration: Any?,
    )

    @Synchronized
    internal fun trustRefreshTarget(trustSourceId: String): RefreshTarget {
        val endpoint = sources[trustSourceId] ?: error(UNTRUSTED)
        freshTime() // An expired or absent root can be replaced; a retired checkpoint cannot.
        return RefreshTarget(this, endpoint.trustUri(), null, null, trust, null)
    }

    @Synchronized
    internal fun indexRefreshTarget(sourceId: String, keyId: String): RefreshTarget {
        val root = currentTrust()
        check(root.grants[keyId]?.source == sourceId) { UNTRUSTED }
        return RefreshTarget(this, sources.getValue(sourceId).indexUri(keyId), sourceId, keyId, root, indexes[sourceId])
    }

    @Synchronized
    internal fun checkRefresh(target: RefreshTarget, requireTrust: Boolean = target.sourceId == null) {
        val time = freshTime()
        check(target.owner === this && (target.sourceId == null) == requireTrust) { UNTRUSTED }
        if (!requireTrust) {
            check(target.rootGeneration === trust) { UNTRUSTED }
            val source = requireNotNull(target.sourceId)
            val root = currentTrust(time)
            check(indexes[source] === target.indexGeneration && root.grants[target.keyId]?.source == source) { UNTRUSTED }
        }
    }

    data class PublisherSummary(val sourceId: String, val publisherId: String, val keyId: String)

    @Synchronized
    fun publishers(): List<PublisherSummary> = currentTrust().grants.map { (keyId, grant) ->
        PublisherSummary(grant.source, grant.publisher, keyId)
    }.sortedWith(compareBy(PublisherSummary::sourceId, PublisherSummary::publisherId, PublisherSummary::keyId))

    data class VersionSummary(val sourceId: String, val publisherId: String, val packageId: String, val version: String,
        val sha256: String, val keyId: String)

    /** Explicit browsing, not a diagnostics read; may advance the durable time watermark. */
    @Synchronized
    fun versions(sourceId: String): List<VersionSummary> = currentIndex(sourceId).let { index ->
        index.rows.map { VersionSummary(sourceId, index.publisherId, it.packageId, it.version, it.sha256, index.keyId) }
    }

    @Synchronized
    fun select(sourceId: String, id: String, version: String): Selection {
        require(exactVersion.matches(version)) { INVALID }
        val index = currentIndex(sourceId)
        val row = index.rows.singleOrNull { it.packageId == id && it.version == version } ?: error(NOT_FOUND)
        return Selection(sourceId, index.publisherId, id, version, row.sha256,
            sources.getValue(sourceId).artifactUri(id, version), index, index.keyId)
    }

    /** Recheck the exact selected generation immediately before a host-owned download. */
    @Synchronized
    internal fun checkDownload(selection: Selection) { checkSelection(selection) }

    /** A host-reserved prefix remains reserved even when its publisher is revoked or unavailable. */
    internal fun controlsPackageId(id: String): Boolean = sources.values.any { id.startsWith(it.packagePrefix) }

    fun verify(selection: Selection, bytes: ByteArray): Verified {
        synchronized(this) { checkSelection(selection) }
        require(bytes.size in 1..ExtensionPackageSource.MAX_SOURCE_BYTES) { INVALID }
        val snapshot = bytes.copyOf()
        require(sha(snapshot) == selection.sha256) { DIGEST_MISMATCH }
        val artifact = ExtensionPackageArtifact.decode(snapshot)
        require(artifact.manifest.id == selection.packageId && artifact.manifest.version == selection.version) { INVALID }
        return synchronized(this) {
            val index = checkSelection(selection)
            Verified(selection, artifact, ExtensionPackageSourceReceipt(selection.sourceId, index.publisherId,
                index.keyId, index.scope.revision, index.revision))
        }
    }

    /** Hot path is lock-free. A lease is deliberately not transferable to an equivalent new index. */
    internal class RuntimeLease(private val current: () -> Boolean) {
        private val retired = java.util.concurrent.atomic.AtomicBoolean()
        fun isCurrent(): Boolean {
            if (retired.get()) return false
            val valid = try { current() } catch (_: Exception) { false }
            if (!valid) retired.set(true)
            return !retired.get()
        }
    }

    @Synchronized
    internal fun runtimeLease(artifact: ExtensionPackageArtifact, receipt: ExtensionPackageSourceReceipt): RuntimeLease =
        advance { time ->
            val index = installedIndex(artifact, receipt, time)
            RuntimeLease {
                !retired && !index.scope.retirement.closed && !index.retirement.closed &&
                    now() < minOf(index.expires, index.scope.expires) &&
                    !retired && !index.scope.retirement.closed && !index.retirement.closed
            }
        }

    /** Off the tool/UI hot paths, validate the durable checkpoint and persist the observed clock. */
    @Synchronized
    internal fun pollRuntimeTrust() { freshTime() }

    /** A historical receipt is insufficient: require the same owner and exact bytes in the current index. */
    @Synchronized
    internal fun <T> withInstalled(artifact: ExtensionPackageArtifact, receipt: ExtensionPackageSourceReceipt, publish: () -> T): T =
        advance { time -> installedIndex(artifact, receipt, time); publish() }

    private fun installedIndex(artifact: ExtensionPackageArtifact, receipt: ExtensionPackageSourceReceipt, time: Instant): Index {
        val index = currentIndex(receipt.sourceId, time)
        val manifest = artifact.manifest
        check(index.publisherId == receipt.publisherId && index.scope.revision >= receipt.trustRevision &&
            index.revision >= receipt.indexRevision && index.rows.any {
                it.packageId == manifest.id && it.version == manifest.version && it.sha256 == manifest.sha256
            }) { UNTRUSTED }
        return index
    }

    /** No I/O in the callback except the final local commit. */
    @Synchronized
    internal fun <T> withCurrent(candidate: Verified, publish: () -> T): T {
        return advance { time ->
            val selected = candidate.selection
            val index = checkSelection(selected, time)
            val manifest = candidate.manifest
            check(candidate.artifact.format == ExtensionPackageArtifact.Format.DECLARATIVE && manifest.id == selected.packageId &&
                manifest.version == selected.version && manifest.sha256 == selected.sha256 &&
                candidate.sourceReceipt == ExtensionPackageSourceReceipt(selected.sourceId, index.publisherId,
                    index.keyId, index.scope.revision, index.revision)) { UNTRUSTED }
            publish()
        }
    }

    private fun currentTrust(time: Instant = freshTime()): Trust =
        (trust ?: error(UNTRUSTED)).also { check(it.expires > time) { EXPIRED } }

    /** This reads only published process state. It never opens disk or advances the trusted clock watermark. */
    @Synchronized
    fun diagnosticObservation(): SignedCatalogObservation {
        if (retired) return SignedCatalogObservation(SignedCatalogDiagnosticState.RETIRED)
        val root = trust ?: return SignedCatalogObservation(SignedCatalogDiagnosticState.NOT_REFRESHED,
            releaseState = { SignedReleaseDiagnostic.ROOT_NOT_REFRESHED })
        val time = maxOf(observedTime.get(), clock())
        if (root.expires <= time) return SignedCatalogObservation(SignedCatalogDiagnosticState.EXPIRED, root.revision) {
            SignedReleaseDiagnostic.ROOT_EXPIRED
        }
        val publishers = root.grants.values.map { it.source to it.publisher }.toSet()
        val current = indexes.filterValues { it.scope === root }
        return SignedCatalogObservation(SignedCatalogDiagnosticState.OBSERVED, root.revision) { record ->
            val receipt = requireNotNull(record.sourceReceipt)
            val index = current[receipt.sourceId]
            val manifest = record.manifest
            when {
                (receipt.sourceId to receipt.publisherId) !in publishers -> SignedReleaseDiagnostic.PUBLISHER_NOT_GRANTED
                index == null || index.publisherId != receipt.publisherId -> SignedReleaseDiagnostic.INDEX_NOT_REFRESHED
                index.expires <= time -> SignedReleaseDiagnostic.INDEX_EXPIRED
                root.revision < receipt.trustRevision || index.revision < receipt.indexRevision || !index.rows.any {
                    it.packageId == manifest.id && it.version == manifest.version && it.sha256 == manifest.sha256
                } -> SignedReleaseDiagnostic.RELEASE_NOT_LISTED
                else -> SignedReleaseDiagnostic.RELEASE_MATCHES
            }
        }
    }

    private fun currentIndex(id: String, time: Instant = freshTime()): Index {
        val scope = currentTrust(time)
        val index = indexes[id] ?: error(NOT_FOUND)
        check(index.scope === scope) { UNTRUSTED }
        check(index.expires > time) { EXPIRED }
        return index
    }

    private fun checkSelection(selection: Selection, time: Instant = freshTime()): Index {
        val index = currentIndex(selection.sourceId, time)
        check(index === selection.generation && index.publisherId == selection.publisherId && index.keyId == selection.keyId &&
            index.rows.any { it.packageId == selection.packageId && it.version == selection.version && it.sha256 == selection.sha256 } &&
            sources.getValue(selection.sourceId).artifactUri(selection.packageId, selection.version) == selection.artifactUri) { UNTRUSTED }
        return index
    }

}

private const val INVALID = "CATALOG_INVALID"
private const val UNTRUSTED = "CATALOG_UNTRUSTED"
private const val EXPIRED = "CATALOG_EXPIRED"
private const val NOT_FOUND = "CATALOG_VERSION_NOT_FOUND"
private const val DIGEST_MISMATCH = "CATALOG_DIGEST_MISMATCH"
private const val MAX_INDEX_BYTES = 64 * 1024
internal const val TRUST_DOMAIN = "CordiAct trust catalog v1\u0000"
internal const val INDEX_DOMAIN = "CordiAct release index v1\u0000"
private val identity = Regex("[a-z][a-z0-9-]{0,31}")
private val prefix = Regex("[a-z][a-z0-9-]{0,19}-")
private val packageId = Regex("[a-z][a-z0-9-]{1,23}")
private val exactVersion = Regex("(?:0|[1-9][0-9]{0,5})(?:\\.(?:0|[1-9][0-9]{0,5})){2}")
private val sha256 = Regex("[0-9a-f]{64}")
private val keyText = Regex("[A-Za-z0-9+/]{40,252}={0,2}")
private val basePath = Regex("/(?:[a-zA-Z0-9_-]+/)*")
private val natural = Regex("[1-9][0-9]{0,15}")

private fun isNew(old: Long?, oldDigest: String?, next: Long, nextDigest: String): Boolean {
    if (old == null || next > old) return true
    check(next == old && nextDigest == oldDigest) { "CATALOG_ROLLBACK_OR_EQUIVOCATION" }
    return false
}

private fun requireP256(key: PublicKey): PublicKey {
    val ec = key as? ECPublicKey ?: error(INVALID)
    val reference = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
        .getParameterSpec(ECParameterSpec::class.java)
    require(ec.params.curve == reference.curve && ec.params.generator == reference.generator &&
        ec.params.order == reference.order && ec.params.cofactor == reference.cofactor) { INVALID }
    return key
}

private fun verifiedBytes(data: ByteArray, sig: ByteArray, key: PublicKey, domain: String): ByteArray {
    require(data.size in 1..MAX_INDEX_BYTES && sig.size in 8..72) { INVALID }
    val raw = data.copyOf()
    val valid = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(domain.toByteArray(Charsets.US_ASCII)); update(raw); verify(sig.copyOf())
        }
    } catch (_: java.security.GeneralSecurityException) { false }
    require(valid) { UNTRUSTED }
    return raw
}

private fun sha(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
private fun <T : Any> need(value: T?): T = requireNotNull(value) { INVALID }
private fun <T> parse(bytes: ByteArray, read: JsonReader.() -> T): T = try {
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    require(!text.startsWith('\uFEFF'))
    JsonReader(StringReader(text)).use { reader ->
        reader.strictness = Strictness.STRICT
        reader.read().also { require(reader.peek() == JsonToken.END_DOCUMENT) }
    }
} catch (_: Exception) { throw IllegalArgumentException(INVALID) }

private inline fun JsonReader.fields(read: (String) -> Unit) {
    beginObject()
    val names = mutableSetOf<String>()
    while (hasNext()) {
        val name = nextName()
        require(names.add(name))
        read(name)
    }
    endObject()
}
private inline fun <T> JsonReader.entries(max: Int, read: () -> T): List<T> = buildList {
    beginArray()
    while (hasNext()) { require(size < max); add(read()) }
    endArray()
}
private fun JsonReader.text(regex: Regex): String {
    require(peek() == JsonToken.STRING)
    return nextString().also { require(regex.matches(it)) }
}
private fun JsonReader.number(): Long {
    require(peek() == JsonToken.NUMBER)
    val literal = nextString()
    require(natural.matches(literal))
    return literal.toLong()
}

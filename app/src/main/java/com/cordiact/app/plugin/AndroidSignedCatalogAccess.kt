package com.cordiact.app.plugin

import android.content.Context
import com.cordiact.core.plugin.CatalogSource
import com.cordiact.core.plugin.SignedCatalogObservation
import com.cordiact.core.plugin.SignedPackageCatalog
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64

/** A configured catalog's live access and independent rollback checkpoint. */
internal class AndroidSignedCatalogAccess private constructor(
    private val policy: CatalogBuildPolicy,
    private val privateRoot: () -> Path,
    private val clock: () -> Instant,
    private val directoryName: String = "cordiact-signed-catalog",
) {
    val trustSourceId get() = policy.trustSourceId
    val sources get() = policy.sources
    @Volatile private var opened: SignedPackageCatalog? = null
    @Volatile private var retired = false
    fun catalog(): SignedPackageCatalog? = opened

    /** The settings snapshot must not open or initialize the private catalog. */
    fun diagnosticObservation(): SignedCatalogObservation =
        opened?.diagnosticObservation() ?: SignedCatalogObservation.notOpened()

    fun reserves(id: String): Boolean = policy.sources.any { id.startsWith(it.packagePrefix) }

    fun ownsSource(id: String): Boolean = policy.sources.any { it.id == id }

    @Synchronized
    fun retire() {
        retired = true
        opened?.retire()
        opened = null
    }

    fun prepareTrust(page: WorkspaceExtensionPackages.Page): WorkspaceExtensionPackages.SignedRefresh =
        page.prepareTrustRefresh(trustSourceId) { openForExplicitRefresh() }

    @Synchronized
    private fun openForExplicitRefresh() {
        check(!retired) { "CATALOG_UNTRUSTED" }
        if (opened != null) return
        val requested = privateRoot().toAbsolutePath().normalize()
        val root = requested.toRealPath()
        check(root == requested && Files.isDirectory(root, NOFOLLOW_LINKS)) { "CATALOG_STATE_UNAVAILABLE" }
        val directory = root.resolve(directoryName)
        val state = directory.resolve("catalog.state")
        // Creating the directory is the one initialization attempt. A prior partial initialization,
        // missing state within an existing directory, or corruption must not be repaired here.
        if (Files.notExists(directory, NOFOLLOW_LINKS)) {
            Files.createDirectory(directory)
            SignedPackageCatalog.provisionWithRootFloor(policy.rootKey, policy.sources, state,
                policy.minimumRootRevision, policy.minimumRootDigest, clock)
        }
        check(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "CATALOG_STATE_UNAVAILABLE" }
        opened = SignedPackageCatalog.openWithRootFloor(policy.rootKey, policy.sources, state,
            policy.minimumRootRevision, policy.minimumRootDigest, clock)
    }

    companion object {
        internal const val POLICY_ASSET = "cordiact-signed-catalog-v1.txt"
        fun fromApk(context: Context): AndroidSignedCatalogAccess? {
            val app = context.applicationContext
            if (POLICY_ASSET !in app.assets.list("").orEmpty()) return null
            val policy = try { app.assets.open(POLICY_ASSET).use(CatalogBuildPolicy::read) }
            catch (_: Exception) { throw IllegalStateException("CATALOG_BUILD_POLICY_INVALID") }
            return AndroidSignedCatalogAccess(policy, { app.noBackupFilesDir.canonicalFile.toPath() }, Instant::now)
        }

        internal fun forTest(policy: CatalogBuildPolicy, directory: Path, clock: () -> Instant): AndroidSignedCatalogAccess =
            AndroidSignedCatalogAccess(policy, { directory }, clock)

        fun forUser(policy: CatalogBuildPolicy, directory: Path, id: String): AndroidSignedCatalogAccess =
            AndroidSignedCatalogAccess(policy, { directory }, Instant::now, "cordiact-user-catalog-$id")
    }
}

internal class CatalogBuildPolicy private constructor(
    val rootKey: PublicKey,
    val minimumRootRevision: Long,
    val minimumRootDigest: String,
    val trustSourceId: String,
    val sources: List<CatalogSource>,
) {
    companion object {
        internal const val MAX_POLICY_BYTES = 4096
        private val key = Regex("[A-Za-z0-9+/]{40,252}={0,2}")
        private val revision = Regex("[1-9][0-9]{0,15}")
        private val digest = Regex("[0-9a-f]{64}")

        /** LF-separated ASCII, header then root key, minimum root revision/digest, trust source, and source rows. */
        fun read(input: InputStream): CatalogBuildPolicy {
            val buffer = ByteArray(MAX_POLICY_BYTES + 1)
            var count = 0
            while (count < buffer.size) {
                val next = input.read(buffer, count, buffer.size - count)
                if (next < 0) break
                require(next > 0)
                count += next
            }
            require(count in 1..MAX_POLICY_BYTES && (0 until count).all { buffer[it] == 10.toByte() || buffer[it] in 32..126 })
            val lines = String(buffer, 0, count, Charsets.US_ASCII).removeSuffix("\n").split('\n')
            require(lines.size in 6..21 && lines[0] == "CordiAct catalog policy v1")
            require(key.matches(lines[1]) && revision.matches(lines[2]) && digest.matches(lines[3]))
            val rootKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(lines[1])))
            val sources = lines.drop(5).map { line ->
                val fields = line.split(' ', limit = 4)
                require(fields.size == 3)
                CatalogSource(fields[0], URI(fields[1]), fields[2])
            }
            require(sources.any { it.id == lines[4] })
            SignedPackageCatalog(rootKey, sources, Instant::now) // Validate root curve and all namespace boundaries without I/O.
            return CatalogBuildPolicy(rootKey, lines[2].toLong(), lines[3], lines[4], java.util.Collections.unmodifiableList(sources))
        }
    }
}

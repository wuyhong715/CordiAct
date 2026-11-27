package com.cordiact.core.plugin

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class SignedCatalogPublisherInteropTest {
    @Test
    fun `accepts SDK release indexes with exact bytes across upgrade and revocation`() {
        val fixture = System.getenv("CORDIACT_CATALOG_FIXTURE")
        assumeTrue(!fixture.isNullOrBlank(), "Generate the SDK catalog fixture before running this integration test")
        val output = Path.of(requireNotNull(fixture))
        val policy = Files.readAllLines(output.resolve("root/cordiact-signed-catalog-v1.txt"))
        assertEquals("CordiAct catalog policy v1", policy.first())
        val rootKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(policy[1])))
        val sources = policy.drop(5).map { row ->
            val (id, base, prefix) = row.split(' ')
            CatalogSource(id, URI(base), prefix)
        }
        val catalog = SignedPackageCatalog(rootKey, sources) { Instant.ofEpochSecond(1_795_824_000L) }
        val userCatalog = SignedPackageCatalog(rootKey, sources) { Instant.ofEpochSecond(1_795_824_000L) }
        fun document(path: String) = CatalogSignedDocument.decode(Files.readAllBytes(output.resolve(path)))
        fun trust(name: String) = document("$name/catalog/trust.cdx").let { catalog.acceptTrust(it.bytes, it.signature) }
        fun index(source: String, directory: String = source) = document("$directory/catalog/indexes/$source-key.cdx")
            .let { catalog.acceptIndex(source, "$source-key", it.bytes, it.signature) }

        trust("root")
        assertEquals(listOf("alpha", "beta"), catalog.publishers().map { it.sourceId })
        index("alpha")
        index("beta")
        for (source in listOf("alpha", "beta")) {
            val selection = catalog.select(source, "$source-tools", "1.0.0")
            val bytes = Files.readAllBytes(output.resolve("$source/packages/$source-tools/1.0.0.json"))
            val verified = catalog.verify(selection, bytes)
            assertEquals("$source-tools", verified.artifact.manifest.id)
            assertEquals(source, verified.sourceReceipt.sourceId)
            assertFails { catalog.verify(selection, bytes + byteArrayOf(10)) }
        }

        val stale = catalog.select("alpha", "alpha-tools", "1.0.0")
        index("alpha", "alpha-upgrade")
        assertEquals(setOf("1.0.0", "1.0.1"), catalog.versions("alpha").map { it.version }.toSet())
        assertFails { catalog.checkDownload(stale) }
        val upgrade = catalog.select("alpha", "alpha-tools", "1.0.1")
        val upgradedBytes = Files.readAllBytes(output.resolve("alpha-upgrade/packages/alpha-tools/1.0.1.json"))
        assertEquals("1.0.1", catalog.verify(upgrade, upgradedBytes).artifact.manifest.version)

        trust("revoked")
        assertEquals(listOf("beta"), catalog.publishers().map { it.sourceId })
        assertFails { catalog.verify(upgrade, upgradedBytes) }
        assertFails { index("alpha", "alpha-upgrade") }
        assertTrue(catalog.controlsPackageId("alpha-tools"))

        val root = document("root/catalog/trust.cdx")
        userCatalog.acceptTrust(root.bytes, root.signature)
        val beta = document("beta/catalog/indexes/beta-key.cdx")
        userCatalog.acceptIndex("beta", "beta-key", beta.bytes, beta.signature)
        val store = InMemoryExtensionPackageStore()
        val installer = ExtensionPackageInstaller(store,
            signedCatalogProvider = { catalog },
            signedCatalogBySource = { if (it == "beta") userCatalog else catalog },
            openedSignedCatalogs = { listOf(catalog, userCatalog) })
        val betaChoice = installer.selectSignedVersion("beta", "beta-tools", "1.0.0")
        val betaBytes = Files.readAllBytes(output.resolve("beta/packages/beta-tools/1.0.0.json"))
        val candidate = userCatalog.verify(betaChoice, betaBytes)
        assertFails { catalog.withCurrent(candidate) {} }
        val installed = installer.installVerified("workspace", candidate, null, candidate.manifest.capabilities) { it() }
        val record = (installed as ExtensionPackageChange.Applied).record
        val lease = requireNotNull(installer.lease(record))
        catalog.retire()
        assertTrue(lease.isCurrent())
        userCatalog.retire()
        assertTrue(!lease.isCurrent())
        assertFails { installer.requireCurrent(candidate) }
    }
}

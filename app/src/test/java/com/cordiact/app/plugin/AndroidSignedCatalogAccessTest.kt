package com.cordiact.app.plugin

import android.app.Application
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.InMemoryExtensionPackageStore
import com.cordiact.core.plugin.SignedCatalogDiagnosticState
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import com.cordiact.core.security.EffectiveSecurityProfile
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AndroidSignedCatalogAccessTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun defaultApkHasNoTrustedSourceConfiguration() =
        assertNull(AndroidSignedCatalogAccess.fromApk(RuntimeEnvironment.getApplication()))

    @Test fun explicitPreparationOpensPrivateStateWithoutNetworkAndMissingStateIsNotRecreated() {
        val root = folder.root.toPath()
        val policy = policy()
        val access = AndroidSignedCatalogAccess.forTest(policy, root) { Instant.ofEpochSecond(1_000) }
        assertTrue(access.reserves("alpha-example"))
        assertFalse(access.reserves("other-example"))
        assertNull(access.catalog())
        val directory = root.resolve("cordiact-signed-catalog")
        assertFalse(Files.exists(directory))
        assertEquals(SignedCatalogDiagnosticState.NOT_OPENED, access.diagnosticObservation().state)
        assertFalse(Files.exists(directory))
        val service = service(access)
        val page = service.openPage("test")
        assertThrows(IllegalStateException::class.java) { access.prepareTrust(page) } // Not foreground.
        assertFalse(Files.exists(directory))
        service.resume(Any())
        access.prepareTrust(page).cancel() // Only prepare, never dispatch the GET.
        assertNotNull(access.catalog())
        assertEquals(SignedCatalogDiagnosticState.NOT_REFRESHED, access.diagnosticObservation().state)
        assertTrue(Files.isRegularFile(directory.resolve("catalog.state")))
        val state = directory.resolve("catalog.state")
        Files.delete(state)
        // Diagnosis does not re-open or even check the missing private file.
        assertEquals(SignedCatalogDiagnosticState.NOT_REFRESHED, access.diagnosticObservation().state)
        assertFalse(Files.exists(state))
        val reopened = AndroidSignedCatalogAccess.forTest(policy, root) { Instant.ofEpochSecond(1_000) }
        val fresh = service(reopened).also { it.resume(Any()) }
        assertThrows(IllegalStateException::class.java) { reopened.prepareTrust(fresh.openPage("test")) }
        assertFalse(Files.exists(state))
    }

    @Test fun policyRejectsInvalidUrlsUnknownTrustSourceAndUnboundedInput() {
        val original = policyText()
        for (bad in listOf(original.replace("https://", "http://"), original.replace("\nalpha\n", "\nmissing\n"),
            original.replace("beta-", "alpha-more-"), original + "x".repeat(4097))) {
            assertThrows(IllegalArgumentException::class.java) { CatalogBuildPolicy.read(bad.byteInputStream()) }
        }
    }

    @Test fun userCatalogIsOptInAndRetirementPreservesReservationsAcrossRestart() {
        val privateRoot = folder.root
        val custom = AndroidUserCatalogs.preview(policyText().byteInputStream())
        val store = AndroidUserCatalogs(privateRoot, emptyList())
        assertTrue(store.directories.value.isEmpty())
        assertFalse(store.reserves("alpha-tools"))
        store.add(custom)
        val added = store.directories.value.single()
        assertEquals(custom.rootFingerprint, added.rootFingerprint)
        assertEquals("alpha", added.access.trustSourceId)
        assertTrue(store.reserves("alpha-tools"))
        assertNull(store.catalogForSource("alpha"))
        val state = privateRoot.toPath().resolve("cordiact-user-catalog-${custom.id}/catalog.state")
        assertFalse(Files.exists(state))

        val app = service(added.access)
        val owner = Any()
        app.resume(owner)
        val page = app.openPage("test")
        added.access.prepareTrust(page).cancel()
        val opened = requireNotNull(added.access.catalog())
        assertTrue(Files.exists(state))
        val oldRemoval = page.approveCatalogConfiguration { store.remove(added) }
        app.pause(owner)
        app.resume(owner)
        assertThrows(IllegalStateException::class.java) { oldRemoval.commit() }
        assertSame(added, store.directories.value.single())
        page.approveCatalogConfiguration { store.remove(added) }.commit()
        assertEquals(SignedCatalogDiagnosticState.RETIRED, opened.diagnosticObservation().state)
        assertThrows(IllegalStateException::class.java) { added.access.prepareTrust(page) }
        assertTrue(Files.exists(state))

        val restarted = AndroidUserCatalogs(privateRoot, emptyList())
        assertTrue(restarted.directories.value.isEmpty())
        assertTrue(restarted.reserves("alpha-tools"))
        assertThrows(IllegalArgumentException::class.java) { restarted.add(AndroidUserCatalogs.preview(policyText().byteInputStream())) }
        restarted.add(custom)
        assertEquals(custom.id, restarted.directories.value.single().id)
        assertTrue(Files.exists(state))
    }

    @Test fun userCatalogCannotClaimAnApkSourceOrNamespace() {
        val bundled = policy()
        val store = AndroidUserCatalogs(folder.root, bundled.sources)
        assertThrows(IllegalArgumentException::class.java) { store.add(AndroidUserCatalogs.preview(policyText().byteInputStream())) }
        assertTrue(store.directories.value.isEmpty())
    }

    private fun service(access: AndroidSignedCatalogAccess): WorkspaceExtensionPackages = WorkspaceExtensionPackages(
        ExtensionPackageInstaller(InMemoryExtensionPackageStore(), reservedSourceId = access::reserves,
            signedCatalogProvider = access::catalog), { EffectiveSecurityProfile.fullAccess(it) },
    )

    private fun policy() = CatalogBuildPolicy.read(policyText().byteInputStream())
    private fun policyText(): String {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public
        return listOf("CordiAct catalog policy v1", Base64.getEncoder().encodeToString(key.encoded), "3", "0".repeat(64), "alpha",
            "alpha https://alpha.example/plugins/ alpha-", "beta https://beta.example/ beta-").joinToString("\n")
    }
}

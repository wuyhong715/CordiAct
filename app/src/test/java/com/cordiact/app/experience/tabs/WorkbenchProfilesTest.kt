package com.cordiact.app.experience.tabs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cordiact.core.workbench.PanelPlacement
import com.cordiact.core.workbench.WorkbenchAccent
import com.cordiact.core.workbench.WorkbenchPanel
import com.cordiact.core.workbench.WorkbenchProfile
import com.cordiact.core.workbench.WorkbenchTheme
import com.cordiact.core.plugin.ExtensionPackageArtifact
import com.cordiact.core.plugin.ExtensionPackageRecord
import com.cordiact.core.plugin.ExtensionPackageSourceReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WorkbenchProfilesTest {
    private val profile = WorkbenchProfile(
        "布局一", WorkbenchTheme.SYSTEM, WorkbenchAccent.TEAL,
        listOf(WorkbenchPanel("pkg.checklist", PanelPlacement.DOCK)),
    )

    @Test fun profileFileRoundTripsAndRejectsUnknownOrDuplicateFields() {
        val encoded = WorkbenchProfileFile.encode(profile)
        assertEquals(profile, encoded.byteInputStream().use(WorkbenchProfileFile::read))
        assertThrows(IllegalStateException::class.java) {
            WorkbenchProfileFile.decode(encoded.replace("\"version\": 1,", "\"enabledPlugin\": true, \"version\": 1,"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkbenchProfileFile.decode(encoded.replace("\"version\": 1,", "\"version\": 1, \"version\": 1,"))
        }
    }

    @Test fun savesByWorkspaceAndRejectsStaleUpdates() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val store = WorkbenchProfileStore(context)
        val first = store.load("first")
        val second = store.load("second")
        val changed = first.apply(null, profile)

        store.save("first", first, changed)

        val reopened = WorkbenchProfileStore(context)
        assertEquals(changed, reopened.load("first"))
        assertEquals(second, reopened.load("second"))
        assertThrows(IllegalStateException::class.java) { reopened.save("first", first, first) }
        reopened.restoreDefault("first")
        assertEquals(first, reopened.load("first"))
    }

    @Test fun portableProfileKeepsExactRequirementsAndReadsLegacyLayout() {
        val required = WorkbenchPackageRequirement("checklist", "1.0.0", "a".repeat(64), "catalog", "publisher")
        val portable = PortableWorkbenchProfile(profile, listOf(required))
        val encoded = WorkbenchProfileFile.encodePortable(portable)
        assertEquals(portable, encoded.byteInputStream().use(WorkbenchProfileFile::readPortable))
        assertEquals(PortableWorkbenchProfile(profile),
            WorkbenchProfileFile.encode(profile).byteInputStream().use(WorkbenchProfileFile::readPortable))
        assertThrows(IllegalArgumentException::class.java) {
            WorkbenchProfileFile.decodePortable(encoded.replace("\"version\": 2", "\"version\": 1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkbenchProfileFile.decodePortable(encoded.replace("\"publisher\": \"publisher\"", "\"enabled\": true"))
        }
    }

    @Test fun reloadedRecordsMatchByInstalledGenerationAndPublisher() {
        val bytes = """
            {"schemaVersion":1,"id":"checklist","version":"1.0.0","title":"Checklist","description":"Release tasks",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["command.publish"],"tools":[],
            "commands":[{"name":"run-check","aliases":[],"description":"Read checklist","text":"check"}]}
        """.trimIndent().encodeToByteArray()
        val record = ExtensionPackageRecord("workspace", ExtensionPackageArtifact.decode(bytes), "generation", 0,
            sourceReceipt = ExtensionPackageSourceReceipt("catalog", "publisher", "key", 1, 1))
        val reloaded = record.copy(artifact = ExtensionPackageArtifact.decode(bytes))
        org.junit.Assert.assertTrue(reloaded.matchesWorkbenchPreview(record))
        org.junit.Assert.assertFalse(reloaded.copy(installationId = "changed").matchesWorkbenchPreview(record))
        org.junit.Assert.assertFalse(reloaded.copy(sourceReceipt = null).matchesWorkbenchPreview(record))
    }
}

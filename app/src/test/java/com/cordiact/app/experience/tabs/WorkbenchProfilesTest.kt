package com.cordiact.app.experience.tabs

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.cordiact.core.workbench.PanelPlacement
import com.cordiact.core.workbench.WorkbenchAccent
import com.cordiact.core.workbench.WorkbenchPanel
import com.cordiact.core.workbench.WorkbenchProfile
import com.cordiact.core.workbench.WorkbenchTheme
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
}

package com.cordiact.app.security

import android.app.Application
import android.content.Context
import com.cordiact.core.security.PermissionPreset
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class ReviewerReferenceStoreTest {
    @Test fun oldSnapshotsAreRemovedAndAutoFallsBackWithRevisionBump() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("security_profiles", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("w.preset", "AUTO_APPROVAL")
            .putString("w.reviewer_endpoint", "https://old.example")
            .putString("w.reviewer_provider", "old")
            .putLong("w.profile_revision", 7).putLong("w.policy_epoch", 9).commit()
        val store = SecurityProfileStore(context)
        val profile = store.profileFor("w")
        assertEquals(PermissionPreset.WORKSPACE_ONLY, profile.preset)
        assertEquals(8L, profile.profileRevision)
        assertEquals(10L, profile.policyEpoch)
        assertTrue(store.reviewerNeedsReselection("w"))
        assertFalse(prefs.contains("w.reviewer_endpoint"))
        assertEquals(1, store.pendingMigrationAuditCount())
        val failingAudit = object : com.cordiact.core.security.AuditLog by com.cordiact.core.security.InMemoryAuditLog(
            com.cordiact.core.security.AuditSigner { _, _ -> "test" }) {
            override fun append(draft: com.cordiact.core.security.AuditEventDraft): com.cordiact.core.security.AuditEvent =
                throw java.io.IOException("unavailable")
        }
        try { store.flushMigrationAudit(failingAudit); fail("audit failure expected") }
        catch (_: java.io.IOException) { }
        assertEquals(1, store.pendingMigrationAuditCount())
        assertEquals(PermissionPreset.WORKSPACE_ONLY, store.profileFor("w").preset)
        val audit = com.cordiact.core.security.InMemoryAuditLog(com.cordiact.core.security.AuditSigner { _, _ -> "test" })
        assertEquals(1, store.flushMigrationAudit(audit))
        assertEquals(0, store.pendingMigrationAuditCount())
        assertEquals("REVIEWER_SNAPSHOT_DISCARDED", audit.query().single().draft.reasonCode)
        assertEquals(0, SecurityProfileStore(context).flushMigrationAudit(audit))
        SecurityProfileStore(context)
        assertEquals(8L, store.profileFor("w").profileRevision)
    }

    @Test fun permissionWritesBumpRevisionAndRejectStaleRevisionAcrossStoreInstances() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("security_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val first = SecurityProfileStore(context)
        val second = SecurityProfileStore(context)
        val revision = first.profileFor("w").profileRevision
        assertEquals(revision + 1, first.setPreset("w", PermissionPreset.READ_ONLY, expectedRevision = revision))
        assertEquals(PermissionPreset.READ_ONLY, second.profileFor("w").preset)
        try {
            second.setPreset("w", PermissionPreset.FULL_ACCESS, expectedRevision = revision)
            fail("stale settings must not overwrite newer values")
        } catch (expected: IllegalStateException) {
            assertEquals("SETTINGS_REVISION_CONFLICT", expected.message)
        }
        assertEquals(PermissionPreset.READ_ONLY, first.profileFor("w").preset)
        assertEquals(revision + 1, first.profileFor("w").profileRevision)
    }

    @Test fun missingReviewerCannotEnableAutoApproval() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("security_profiles", Context.MODE_PRIVATE).edit().clear().commit()
        val store = SecurityProfileStore(context)
        val before = store.profileFor("w")
        try {
            store.setPreset("w", PermissionPreset.AUTO_APPROVAL, expectedRevision = before.profileRevision)
            fail("reviewer is required")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, store.profileFor("w"))
    }
}

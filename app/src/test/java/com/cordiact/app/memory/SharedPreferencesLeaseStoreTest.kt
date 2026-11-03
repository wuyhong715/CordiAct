package com.cordiact.app.memory

import android.content.Context
import com.cordiact.core.memory.ExtractionLease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The claim/release semantics are the part that can silently corrupt scheduling: a store that
 * refuses to replace an expired lease makes a crashed owner hold its session forever.
 */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesLeaseStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private fun store(): SharedPreferencesLeaseStore {
        // Robolectric shares one SharedPreferences across tests in a process, so each test must
        // start from a known-empty state. Clearing through the backing file is the only reliable
        // way: release() refuses when the recorded owner differs, which is the behaviour under
        // test here.
        context.getSharedPreferences("cordiact.memory.leases", Context.MODE_PRIVATE)
            .edit().clear().commit()
        return SharedPreferencesLeaseStore(context)
    }

    private fun lease(owner: String, claimed: Long, expires: Long) =
        ExtractionLease(WS, "s1", owner, claimed, expires)

    @Test
    fun grantsWhenFree() {
        val store = store()
        assertTrue(store.claim(lease("a", 1000, 2000)))
        assertEquals("a", store.active(WS, "s1")?.owner)
    }

    @Test
    fun refusesWhileAnotherOwnerHoldsALiveLease() {
        val store = store()
        store.claim(lease("a", 1000, 5000))

        assertFalse(store.claim(lease("b", 2000, 6000)))
        assertEquals("a", store.active(WS, "s1")?.owner)
    }

    @Test
    fun replacesAnExpiredLease() {
        // The whole point of expiry: a crashed owner must not block the session.
        val store = store()
        store.claim(lease("dead", 1000, 2000))

        assertTrue(store.claim(lease("fresh", 3000, 4000)))
        assertEquals("fresh", store.active(WS, "s1")?.owner)
    }

    @Test
    fun aStaleOwnerCannotReleaseTheLiveLease() {
        val store = store()
        // The dead owner's lease expired, so the fresh owner legitimately takes over.
        store.claim(lease("dead", 1000, 2000))
        val tookOver = store.claim(lease("fresh", 3000, 4000))

        val before = store.active(WS, "s1")?.owner
        store.release(WS, "s1", "dead")
        val after = store.active(WS, "s1")?.owner

        assertTrue("an expired lease must be replaceable, got before=$before", tookOver)
        assertEquals("precondition: the fresh owner holds the lease", "fresh", before)
        assertEquals("the live owner keeps its lease", "fresh", after)
    }

    @Test
    fun releaseByTheHolderClearsTheLease() {
        val store = store()
        store.claim(lease("a", 1000, 5000))

        store.release(WS, "s1", "a")

        assertNull(store.active(WS, "s1"))
    }

    @Test
    fun leasesAreScopedByWorkspace() {
        val store = store()
        store.claim(lease("a", 1000, 5000))

        assertNull(store.active("ws-other", "s1"))
    }

    private companion object {
        const val WS = "ws-1"
    }
}

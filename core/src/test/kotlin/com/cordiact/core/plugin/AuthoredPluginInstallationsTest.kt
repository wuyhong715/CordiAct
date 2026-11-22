package com.cordiact.core.plugin

import com.cordiact.core.action.ApprovalDecision
import com.cordiact.core.action.ApprovalGateway
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.file.WorkspaceFileContent
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.plugin.AuthoredPluginInstallationException.Code
import com.cordiact.core.plugin.AuthoredPluginInstallationStatus.CORRUPT
import com.cordiact.core.plugin.AuthoredPluginInstallationStatus.READY
import com.cordiact.core.plugin.AuthoredPluginInstallationStatus.UNSUPPORTED
import com.cordiact.core.plugin.AuthoredPluginInstallationStoreException.Code as StoreCode
import com.cordiact.core.plugin.AuthoredPluginException.Code as RuntimeCode
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.MutableToolPreferenceStore
import com.cordiact.core.tool.ToolActivationContext
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolManagerException
import com.cordiact.core.tool.ToolManagerPlugin
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceManagerService
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthoredPluginInstallationsTest {
    @Test
    fun `saved bytes survive draft edits deletion and restart but activation needs fresh confirmation`() {
        val f = Fixture()
        val raw = f.files.put(bytes = "\n ${source(title = "你好😀").decodeToString()}\r\n".encodeToByteArray())
        val saveConfirmation = f.confirmation()
        val saved = f.service.saveConfirmed(saveConfirmation, ID, digest(raw)) { true }
        assertEquals(AuthoredPluginInstalledRecord(
            A, ID, digest(raw), 1, AuthoredPluginInstallations.FORMAT_VERSION, raw.size, FIRST_TIME,
            "你好😀", listOf(TEXT_TOOL), saved.installationId,
        ), saved)
        assertCanonicalInstallationId(saved.installationId)
        assertContentEquals(raw, f.store.snapshot().sourceBytes)
        assertEquals(listOf(Read(A, "plugins/$ID.json", 0, AuthoredPluginSource.MAX_SOURCE_BYTES + 1)), f.files.reads)
        f.files.put(bytes = source(title = "Edited", text = "edited draft output"))

        // Reopen the Store and build a fresh runtime; no registration comes from save or preview.
        val reopened = Store(f.disk)
        val installations = AuthoredPluginInstallations(f.files, reopened, clock(FIRST_TIME + 1))
        val firstProcess = RuntimeFixture(f.files)
        val oldTurn = firstProcess.context().copy(authoredPluginTurn = requireNotNull(f.foreground.openTurn(A, SESSION)))
        assertEquals(listOf(AuthoredPluginInstallationEntry(ID, READY, saved)), installations.list(A))
        assertTrue(installations.list(B).isEmpty())
        val reviewed = installations.previewSaved(A, ID, saved.sha256)
        assertEquals(AuthoredPluginSource.compile(ID, raw), reviewed)
        assertFailsWith<UnsupportedOperationException> { (reviewed.tools as MutableList<AuthoredToolDefinition>).clear() }
        assertTrue(oldTurn.toolRegistrations.isEmpty())
        assertTrue(firstProcess.runtime.listForSession(A, SESSION).isEmpty())
        assertTrue(firstProcess.kernel.statuses().isEmpty())
        f.files.drafts.clear()
        runtimeError(RuntimeCode.CALLER_UNAVAILABLE) { firstProcess.activate(installations, saveConfirmation, saved) }
        val confirmation = f.confirmation()
        val receipt = firstProcess.activate(installations, confirmation, saved)
        assertFalse(confirmation.isOpen)
        assertEquals(AuthoredPluginReceipt(ID, saved.sha256, saved.toolIds), receipt)
        assertTrue(requireNotNull(oldTurn.authoredPluginTurn).isOpen)
        assertFailsWith<ToolManagerException> { firstProcess.manager.invoke(oldTurn, TEXT_TOOL, ToolArguments()) }
        assertEquals(mapOf("text" to ToolValue.StringValue("固定内容"), "untrusted" to ToolValue.BooleanValue(true)),
            firstProcess.manager.invoke(firstProcess.context(), TEXT_TOOL, ToolArguments()).values)
        assertTrue(firstProcess.context(A, OTHER_SESSION).toolRegistrations.isEmpty())
        assertTrue(firstProcess.context(B, B_SESSION).toolRegistrations.isEmpty())
        runtimeError(RuntimeCode.ALREADY_ACTIVE) { firstProcess.activate(installations, f.confirmation(), saved) }
        assertSame(receipt, firstProcess.runtime.listForSession(A, SESSION).single())

        // Restart while still active: only the saved snapshot survives. New activation still needs a new permit.
        val restartedFiles = Files()
        val restartedStore = Store(f.disk)
        val restartedInstallations = AuthoredPluginInstallations(restartedFiles, restartedStore)
        val nextProcess = RuntimeFixture(restartedFiles)
        assertTrue(nextProcess.context().toolRegistrations.isEmpty())
        assertTrue(nextProcess.runtime.listForSession(A, SESSION).isEmpty())
        assertTrue(nextProcess.kernel.statuses().isEmpty())
        assertEquals(reviewed, restartedInstallations.previewSaved(A, ID, saved.sha256))
        runtimeError(RuntimeCode.CALLER_UNAVAILABLE) { nextProcess.activate(restartedInstallations, confirmation, saved) }
        val restartedReceipt = nextProcess.activate(restartedInstallations, f.confirmation(), saved)
        assertSame(restartedReceipt, nextProcess.runtime.revokeForSession(A, SESSION, restartedReceipt))
        assertTrue(nextProcess.context().toolRegistrations.isEmpty())
        assertTrue(nextProcess.kernel.statuses().isEmpty())
        assertEquals(saved, restartedInstallations.list(A).single().record)
        assertContentEquals(raw, restartedStore.snapshot().sourceBytes)
        assertEquals(0, reopened.insertCalls)
        assertEquals(0, restartedStore.insertCalls)
        assertEquals(1, f.files.reads.size)
        assertTrue(restartedFiles.reads.isEmpty())
    }

    @Test
    fun `saved activation checks live authority and an exclusive pending ticket before publication`() {
        val f = Fixture()
        val saved = f.save(f.files.put(bytes = transform()))
        val r = RuntimeFixture(f.files) { it.allowsPluginAuthoring }
        val agent = r.context().copy(allowsPluginAuthoring = true,
            authoredPluginTurn = requireNotNull(f.foreground.openTurn(A, SESSION)))
        runtimeError(RuntimeCode.CALLER_UNAVAILABLE) { r.activate(f.service, agent.authoredPluginTurn!!, saved) }
        val denied = f.confirmation()
        runtimeError(RuntimeCode.CALLER_UNAVAILABLE) { r.activate(f.service, denied, saved) { false } }
        assertFalse(denied.isOpen)
        assertTrue(agent.authoredPluginTurn!!.isOpen)
        assertTrue(f.store.listRequests.isEmpty())

        var allowed = true
        f.store.onList = { allowed = false }
        val revoked = f.confirmation()
        runtimeError(RuntimeCode.CALLER_UNAVAILABLE) { r.activate(f.service, revoked, saved) { allowed } }
        assertFalse(revoked.isOpen)

        val switched = f.confirmation()
        f.store.onList = { f.foreground.select(B, B_SESSION); f.foreground.select(A, SESSION) }
        runtimeError(RuntimeCode.CALLER_UNAVAILABLE) { r.activate(f.service, switched, saved) }
        assertFalse(switched.isOpen)
        val requests = f.store.listRequests.size
        runtimeError(RuntimeCode.CALLER_UNAVAILABLE) { r.activate(f.service, switched, saved) }
        assertEquals(requests, f.store.listRequests.size)

        val freshAgent = agent.copy(authoredPluginTurn = requireNotNull(f.foreground.openTurn(A, SESSION)))
        val inFlight = f.confirmation()
        f.store.onList = {
            runtimeError(RuntimeCode.BUSY) { r.runtime.activate(freshAgent, ID, saved.sha256) }
            runtimeError(RuntimeCode.CALLER_UNAVAILABLE) { r.activate(f.service, inFlight, saved) }
            assertTrue(inFlight.isOpen)
            r.runtime.invalidateSession(A, SESSION)
        }
        runtimeError(RuntimeCode.CALLER_UNAVAILABLE) { r.activate(f.service, inFlight, saved) }
        assertFalse(inFlight.isOpen)
        assertTrue(r.kernel.statuses().isEmpty())
        assertTrue(r.context().toolRegistrations.isEmpty())

        f.store.onList = {}
        assertEquals(AuthoredTextOperation.TRIM, f.service.previewSaved(A, ID, saved.sha256).tools.single().operation)
        val receipt = r.activate(f.service, f.confirmation(), saved)
        val result = r.manager.invoke(r.context(), receipt.toolIds.single(),
            ToolArguments(mapOf("text" to ToolValue.StringValue("  原文😀\t"))))
        assertEquals(ToolValue.StringValue("原文😀"), result.values["text"])
        assertEquals(1, f.files.reads.size)
        assertEquals(1, f.store.insertCalls)
    }

    @Test
    fun `saved activation revalidates the exact reviewed workspace id and digest`() {
        val f = Fixture()
        val raw = f.files.put()
        val saved = f.save(raw)
        val r = RuntimeFixture(f.files)
        val preview = f.service.previewSaved(A, ID, saved.sha256)
        f.foreground.select(B, B_SESSION)
        val wrongWorkspace = requireNotNull(f.foreground.openConfirmation(B, B_SESSION))
        installationError(Code.NOT_FOUND) { r.activate(f.service, wrongWorkspace, saved) }
        assertEquals(listOf(A, B), f.store.listRequests)
        installationError(Code.NOT_FOUND) { f.service.previewSaved(A, "checklist-other", saved.sha256) }
        installationError(Code.INVALID_SOURCE) { f.service.previewSaved(A, "../escape", saved.sha256) }
        installationError(Code.VERSION_CHANGED) { f.service.previewSaved(A, ID, "not-a-digest") }

        f.foreground.select(A, SESSION)
        val confirm = f.confirmation()
        val original = f.store.snapshot()
        val otherBytes = source(title = "Different stored bytes")
        // Inject a different valid manifest and bytes without going through a trusted confirmation.
        f.disk.rows[A to ID] = original.copy(record = original.record.copy(
            title = "Different stored bytes", sizeBytes = otherBytes.size, sha256 = digest(otherBytes),
        ), sourceBytes = otherBytes)
        installationError(Code.VERSION_CHANGED) { r.activate(f.service, confirm, saved) }
        assertFalse(confirm.isOpen)
        assertEquals("Different stored bytes", f.service.previewSaved(A, ID, digest(otherBytes)).title)
        assertEquals("Checklist", preview.title)
        f.disk.rows.remove(A to ID)
        installationError(Code.NOT_FOUND) { r.activate(f.service, f.confirmation(), saved) }
        assertTrue(r.kernel.statuses().isEmpty())
        assertEquals(1, f.files.reads.size)
        assertEquals(1, f.store.insertCalls)
    }

    @Test
    fun `draft and saved versions share activation admission without replacing either version`() {
        val f = Fixture()
        val saved = f.save(f.files.put())
        val r = RuntimeFixture(f.files)
        val edited = f.files.put(bytes = source(text = "draft trials only"))
        val activeDraft = r.runtime.activateForSession(f.confirmation(), ID, digest(edited)) { true }
        runtimeError(RuntimeCode.ALREADY_ACTIVE) { r.activate(f.service, f.confirmation(), saved) }
        assertSame(activeDraft, r.runtime.listForSession(A, SESSION).single())
        assertEquals(ToolValue.StringValue("draft trials only"),
            r.manager.invoke(r.context(), TEXT_TOOL, ToolArguments()).values["text"])
        for (id in listOf("first", "second", "third")) {
            val other = f.save(f.files.put(id = id), id)
            r.activate(f.service, f.confirmation(), other)
        }
        val excess = f.save(f.files.put(id = "fourth"), "fourth")
        runtimeError(RuntimeCode.QUOTA) { r.activate(f.service, f.confirmation(), excess) }
        assertEquals(setOf(ID, "first", "second", "third"), r.runtime.listForSession(A, SESSION).map { it.id }.toSet())
        assertEquals(5, f.store.insertCalls)
        assertEquals(saved, f.store.snapshot().record)
    }

    @Test
    fun `same digest is idempotent across sessions and restarts only after reread and validation`() {
        val f = Fixture()
        val raw = f.files.put(bytes = transform())
        val first = f.save(raw)
        f.foreground.select(A, "another-session")
        val reopenedStore = Store(f.disk)
        val next = AuthoredPluginInstallations(f.files, reopenedStore, clock(FIRST_TIME + 999))
        val confirmation = requireNotNull(f.foreground.openConfirmation(A, "another-session"))
        assertEquals(first, next.saveConfirmed(confirmation, ID, digest(raw)) { true })
        assertEquals(2, first.schemaVersion)
        assertEquals(2, f.files.reads.size)
        assertEquals(0, reopenedStore.publications)
        assertFalse(confirmation.isOpen)
        installationError(Code.CALLER_UNAVAILABLE) { next.saveConfirmed(confirmation, ID, digest(raw)) { true } }

        val changed = f.files.put(bytes = source(title = "New"))
        val newConfirmation = requireNotNull(f.foreground.openConfirmation(A, "another-session"))
        installationError(Code.CONFLICT) { next.saveConfirmed(newConfirmation, ID, digest(changed)) { true } }
        assertEquals(first, next.list(A).single().record)
        assertEquals(0, reopenedStore.publications)
    }

    @Test
    fun `uses certificate owner and rejects normal expired and wrong-workspace approvals`() {
        val f = Fixture()
        val rawA = f.files.put()
        val rawB = f.files.put(workspace = B, bytes = source(title = "different workspace"))
        val agent = requireNotNull(f.foreground.openTurn(A, SESSION))
        installationError(Code.CALLER_UNAVAILABLE) { f.service.saveConfirmed(agent, ID, digest(rawA)) { true } }
        assertTrue(agent.isOpen)
        val expired = f.confirmation()
        f.foreground.pause(f.workbench)
        f.foreground.resume(f.workbench)
        installationError(Code.CALLER_UNAVAILABLE) { f.service.saveConfirmed(expired, ID, digest(rawA)) { true } }
        assertTrue(f.files.reads.isEmpty())

        f.foreground.select(B, SESSION)
        val b = requireNotNull(f.foreground.openConfirmation(B, SESSION))
        installationError(Code.VERSION_CHANGED) { f.service.saveConfirmed(b, ID, digest(rawA)) { true } }
        assertEquals(listOf(B), f.files.reads.map { it.workspace })
        assertTrue(f.service.list(A).isEmpty())
        assertTrue(f.service.list(B).isEmpty())
        val b2 = requireNotNull(f.foreground.openConfirmation(B, SESSION))
        assertEquals(B, f.service.saveConfirmed(b2, ID, digest(rawB)) { true }.workspaceId)
        assertTrue(f.service.list(A).isEmpty())
    }

    @Test
    fun `rejects a source edited after preview, unsafe IDs, unknown schemas, and oversized valid prefixes`() {
        val f = Fixture()
        val reviewed = f.files.put()
        f.files.put(bytes = "not JSON".encodeToByteArray())
        installationError(Code.VERSION_CHANGED) { f.save(reviewed) }
        assertEquals(1, f.files.reads.size)
        installationError(Code.INVALID_SOURCE) { f.save(reviewed, id = "../escape") }
        installationError(Code.VERSION_CHANGED) { f.service.saveConfirmed(f.confirmation(), ID, "not-a-digest") { true } }
        assertEquals(1, f.files.reads.size)

        val bad = listOf(
            reviewed.decodeToString().replace("\"schemaVersion\":1", "\"schemaVersion\":99").encodeToByteArray(),
            reviewed.decodeToString().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1").encodeToByteArray(),
            source(id = "other"), byteArrayOf(0xff.toByte()), byteArrayOf(),
        )
        bad.forEach { raw ->
            f.files.put(bytes = raw)
            installationError(Code.INVALID_SOURCE) { f.save(raw) }
        }
        val padded = reviewed + ByteArray(AuthoredPluginSource.MAX_SOURCE_BYTES - reviewed.size) { ' '.code.toByte() }
        val tooLong = padded + byteArrayOf(' '.code.toByte())
        f.files.put(bytes = tooLong)
        assertEquals(WorkspaceFileException.Code.SIZE_LIMIT_EXCEEDED,
            assertFailsWith<WorkspaceFileException> { f.save(padded) }.code)
        assertEquals(0, f.store.insertCalls)
        f.files.put(bytes = padded)
        assertEquals(padded.size, f.save(padded).sizeBytes)
    }

    @Test
    fun `checks authorization before reading and again at durable publication`() {
        val f = Fixture()
        val raw = f.files.put()
        val denied = f.confirmation()
        installationError(Code.DENIED) { f.service.saveConfirmed(denied, ID, digest(raw)) { false } }
        assertFalse(denied.isOpen)
        assertTrue(f.files.reads.isEmpty())
        installationError(Code.CALLER_UNAVAILABLE) { f.service.saveConfirmed(denied, ID, digest(raw)) { true } }

        var allowed = true
        f.store.beforePublication = { allowed = false }
        installationError(Code.DENIED) { f.save(raw, maySave = { allowed }) }
        assertEquals(1, f.files.reads.size)
        assertEquals(0, f.store.publications)
        assertTrue(f.service.list(A).isEmpty())

        f.store.beforePublication = { f.foreground.select(B, SESSION); f.foreground.select(A, SESSION) }
        installationError(Code.CALLER_UNAVAILABLE) { f.save(raw) }
        assertTrue(f.service.list(A).isEmpty())
        f.store.beforePublication = {}
        assertEquals(digest(raw), f.save(raw).sha256)
    }

    @Test
    fun `distinguishes empty permission and failure and never retries an uncertain publication`() {
        val f = Fixture()
        assertTrue(f.service.list(A).isEmpty())
        val raw = f.files.put()
        f.files.failReads = WorkspaceFileException(WorkspaceFileException.Code.SYMLINK_NOT_ALLOWED, PRIVATE)
        assertEquals(WorkspaceFileException.Code.SYMLINK_NOT_ALLOWED,
            assertFailsWith<WorkspaceFileException> { f.save(raw) }.also { assertFalse(PRIVATE in it.message.orEmpty()) }.code)
        f.files.failReads = null
        assertEquals(0, f.store.insertCalls)

        for (code in listOf(StoreCode.ACCESS_DENIED, StoreCode.WORKSPACE_UNAVAILABLE, StoreCode.STORAGE_FAILURE)) {
            f.store.failList = code
            storeError(code) { f.service.list(A) }
        }
        f.store.failList = null
        f.store.beforePublication = { throw AuthoredPluginInstallationStoreException(StoreCode.STORAGE_FAILURE, PRIVATE) }
        storeError(StoreCode.STORAGE_FAILURE) { f.save(raw) }
        assertTrue(f.service.list(A).isEmpty())
        f.store.beforePublication = {}
        f.store.afterPublication = { throw AuthoredPluginInstallationStoreException(StoreCode.COMMIT_UNKNOWN, PRIVATE) }
        val before = f.store.insertCalls
        storeError(StoreCode.COMMIT_UNKNOWN) { f.save(raw) }
        assertEquals(before + 1, f.store.insertCalls)
        assertEquals(digest(raw), f.service.list(A).single().record?.sha256)
    }

    @Test
    fun `bad and unknown persisted rows remain visible and cannot self-repair through idempotency`() {
        val f = Fixture()
        val ready = f.files.put(id = "ready")
        f.save(ready, "ready")
        val variants = listOf<Pair<String, (AuthoredPluginStoredInstallation.Snapshot) -> AuthoredPluginStoredInstallation>>(
            "bad-digest" to { it.copy(record = it.record.copy(sha256 = "0".repeat(64))) },
            "bad-bytes" to { it.copy(sourceBytes = it.sourceBytes.copyOf().also { bytes -> bytes[0] = '['.code.toByte() }) },
            "bad-manifest" to { it.copy(record = it.record.copy(title = PRIVATE)) },
            "bad-tools" to { it.copy(record = it.record.copy(toolIds = listOf("spoof"))) },
            "bad-schema" to { it.copy(record = it.record.copy(schemaVersion = 2)) },
            "bad-schema-marker" to { it.copy(record = it.record.copy(schemaVersion = 99)) },
            "bad-size" to { it.copy(record = it.record.copy(sizeBytes = it.record.sizeBytes + 1)) },
            "bad-source" to {
                val bytes = "malformed JSON".encodeToByteArray()
                it.copy(record = it.record.copy(sizeBytes = bytes.size, sha256 = digest(bytes)), sourceBytes = bytes)
            },
            "unknown-envelope" to { it.copy(record = it.record.copy(formatVersion = 99)) },
            "unknown-schema" to {
                val bytes = it.sourceBytes.decodeToString().replace("\"schemaVersion\":1", "\"schemaVersion\":99").encodeToByteArray()
                it.copy(record = it.record.copy(schemaVersion = 99, sizeBytes = bytes.size, sha256 = digest(bytes)), sourceBytes = bytes)
            },
            "missing-bytes" to { AuthoredPluginStoredInstallation.Broken(it.id) },
            "unreadable-version" to { AuthoredPluginStoredInstallation.Unsupported(it.id) },
        )
        val originals = variants.associate { (id, change) ->
            val raw = f.files.put(id = id)
            f.save(raw, id)
            f.disk.rows[A to id] = change(f.disk.rows.getValue(A to id) as AuthoredPluginStoredInstallation.Snapshot)
            id to raw
        }
        val reopenedStore = Store(f.disk)
        val reopened = AuthoredPluginInstallations(f.files, reopenedStore)
        val runtime = RuntimeFixture(f.files)
        val listing = reopened.list(A).associateBy { it.id }
        assertEquals(READY, listing.getValue("ready").status)
        assertEquals("ready", reopened.previewSaved(A, "ready", digest(ready)).id)
        for ((id, raw) in originals) {
            val unknown = id.startsWith("unknown-") || id == "unreadable-version"
            val code = if (unknown) Code.UNSUPPORTED else Code.CORRUPT
            assertEquals(if (unknown) UNSUPPORTED else CORRUPT, listing.getValue(id).status)
            assertNull(listing.getValue(id).record)
            installationError(code) { f.save(raw, id) }
            val reads = f.files.reads.size
            installationError(code) { reopened.previewSaved(A, id, digest(raw)) }
            val confirmation = f.confirmation()
            installationError(code) { runtime.runtime.activateSavedForSession(reopened, confirmation, id, digest(raw)) { true } }
            assertFalse(confirmation.isOpen)
            assertEquals(reads, f.files.reads.size)
        }
        assertTrue(runtime.kernel.statuses().isEmpty())
        assertEquals(0, reopenedStore.insertCalls)
        assertEquals(variants.size + 1, f.store.publications)
    }

    @Test
    fun `rejects cross-workspace store results without leaking the rows`() {
        val f = Fixture()
        val raw = f.files.put()
        f.save(raw)
        val row = f.disk.rows.getValue(A to ID) as AuthoredPluginStoredInstallation.Snapshot
        f.disk.rows[A to ID] = row.copy(record = row.record.copy(workspaceId = B, title = PRIVATE))
        storeError(StoreCode.STORAGE_FAILURE) { f.service.list(A) }
        storeError(StoreCode.STORAGE_FAILURE) { f.service.previewSaved(A, ID, digest(raw)) }
        val runtime = RuntimeFixture(f.files)
        storeError(StoreCode.STORAGE_FAILURE) {
            runtime.runtime.activateSavedForSession(f.service, f.confirmation(), ID, digest(raw)) { true }
        }
        storeError(StoreCode.STORAGE_FAILURE) { f.save(raw) }
        assertTrue(f.service.list(B).isEmpty())
        assertTrue(runtime.kernel.statuses().isEmpty())
    }

    @Test
    fun `limits per workspace including corrupt slots but checks the existing key first`() {
        val f = Fixture()
        val max = AuthoredPluginInstallations.MAX_PER_WORKSPACE
        repeat(max - 1) { index ->
            val id = "draft-$index"
            f.disk.rows[A to id] = AuthoredPluginStoredInstallation.Broken(id)
        }
        val raw = f.files.put()
        val record = f.save(raw)
        assertEquals(max, f.service.list(A).size)
        assertEquals(record, f.save(raw))
        val overflow = f.files.put(id = "extra")
        installationError(Code.QUOTA) { f.save(overflow, "extra") }
        f.foreground.select(B, SESSION)
        f.files.put(workspace = B, id = "extra", bytes = overflow)
        assertEquals(B, f.service.saveConfirmed(requireNotNull(f.foreground.openConfirmation(B, SESSION)), "extra", digest(overflow)) { true }.workspaceId)
        assertEquals(max, f.service.list(A).size)
        assertEquals(1, f.service.list(B).size)
    }

    @Test
    fun `concurrent distinct approvals sharing the durable key do not replace its first committed version`() {
        val disk = Disk()
        val first = Fixture(disk)
        val second = Fixture(disk)
        val firstRaw = first.files.put()
        val secondRaw = second.files.put(bytes = source(title = "Later"))
        val firstStaged = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        first.store.beforePublication = {
            firstStaged.countDown()
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val winner = workers.submit<AuthoredPluginInstalledRecord> { first.save(firstRaw) }
            val loser = workers.submit<Code> {
                assertTrue(firstStaged.await(5, TimeUnit.SECONDS))
                secondStarted.countDown()
                assertFailsWith<AuthoredPluginInstallationException> { second.save(secondRaw) }.code
            }
            val approved = winner.get(10, TimeUnit.SECONDS)
            assertEquals(Code.CONFLICT, loser.get(10, TimeUnit.SECONDS))
            assertEquals(approved, second.service.list(A).single().record)
            assertEquals(1, first.store.publications)
            assertEquals(0, second.store.publications)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `replace and remove only affect saved versions not the authorized running instance or draft`() {
        val f = Fixture()
        val old = f.save(f.files.put())
        val r = RuntimeFixture(f.files)
        val active = r.activate(f.service, f.confirmation(), old)
        val runningTurn = r.context()
        val newRaw = f.files.put(bytes = "\n${source(title = "Replacement", text = "new output").decodeToString()}\r\n".encodeToByteArray())
        val replacementConfirmation = f.confirmation()
        val replacement = f.service.replaceConfirmed(replacementConfirmation, old, digest(newRaw)) { true }
        assertFalse(replacementConfirmation.isOpen)
        assertCanonicalInstallationId(replacement.installationId)
        assertTrue(old.installationId != replacement.installationId)
        assertEquals("Replacement", f.service.previewSaved(A, ID, replacement.sha256, replacement.installationId).title)
        val afterReplace = Store(f.disk).snapshot()
        assertEquals(replacement, afterReplace.record)
        assertContentEquals(newRaw, afterReplace.sourceBytes)
        assertSame(active, r.runtime.listForSession(A, SESSION).single())
        assertEquals(ToolValue.StringValue("固定内容"), r.manager.invoke(runningTurn, TEXT_TOOL, ToolArguments()).values["text"])

        val removalConfirmation = f.confirmation()
        val reads = f.files.reads.size
        f.service.removeConfirmed(removalConfirmation, replacement) { true }
        assertFalse(removalConfirmation.isOpen)
        assertEquals(reads, f.files.reads.size)
        assertContentEquals(newRaw, f.files.drafts.getValue(A to AuthoredPluginSource.path(ID)))
        assertTrue(AuthoredPluginInstallations(f.files, Store(f.disk)).list(A).isEmpty())
        installationError(Code.NOT_FOUND) { f.service.previewSaved(A, ID, replacement.sha256, replacement.installationId) }
        assertSame(active, r.runtime.listForSession(A, SESSION).single())
        assertEquals(ToolValue.StringValue("固定内容"), r.manager.invoke(r.context(), TEXT_TOOL, ToolArguments()).values["text"])
    }

    @Test
    fun `stale and empty installation identities never match a same-byte replacement or resave`() {
        val f = Fixture()
        val raw = f.files.put()
        val original = f.save(raw)
        assertEquals(original, f.save(raw)) // Saving the same existing version is still idempotent.
        val replacement = f.service.replaceConfirmed(f.confirmation(), original, digest(raw)) { true }
        assertEquals(original.copy(installationId = replacement.installationId), replacement)
        assertTrue(original.installationId != replacement.installationId)
        installationError(Code.VERSION_CHANGED) {
            f.service.previewSaved(A, ID, original.sha256, original.installationId)
        }
        val r = RuntimeFixture(f.files)
        installationError(Code.VERSION_CHANGED) {
            r.runtime.activateSavedForSession(f.service, f.confirmation(), ID, original.sha256, original.installationId) { true }
        }
        assertTrue(r.runtime.listForSession(A, SESSION).isEmpty())
        val reads = f.files.reads.size
        val casCalls = f.store.changeCalls
        for (stale in listOf(original, replacement.copy(installationId = ""), replacement.copy(sha256 = "0".repeat(64)),
            replacement.copy(installedAtEpochMillis = replacement.installedAtEpochMillis + 1))) {
            installationError(Code.VERSION_CHANGED) { f.service.removeConfirmed(f.confirmation(), stale) { true } }
            installationError(Code.VERSION_CHANGED) { f.service.replaceConfirmed(f.confirmation(), stale, digest(raw)) { true } }
        }
        assertEquals(casCalls, f.store.changeCalls)
        assertEquals(reads, f.files.reads.size)

        f.service.removeConfirmed(f.confirmation(), replacement) { true }
        installationError(Code.NOT_FOUND) { f.service.replaceConfirmed(f.confirmation(), replacement, digest(raw)) { true } }
        val resaved = f.save(raw)
        assertEquals(replacement.copy(installationId = resaved.installationId), resaved)
        assertEquals(3, setOf(original.installationId, replacement.installationId, resaved.installationId).size)
        installationError(Code.VERSION_CHANGED) { f.service.removeConfirmed(f.confirmation(), replacement) { true } }
        installationError(Code.VERSION_CHANGED) { f.service.replaceConfirmed(f.confirmation(), original, digest(raw)) { true } }
        assertEquals(resaved, f.service.list(A).single().record)
    }

    @Test
    fun `change requires the live owner before reading and rechecks authority before draft and publication`() {
        val f = Fixture()
        val raw = f.files.put()
        val original = f.save(raw)
        val newRaw = f.files.put(bytes = source(title = "New draft"))
        val initialReads = f.files.reads.size
        val initialLists = f.store.listRequests.size
        val agent = requireNotNull(f.foreground.openTurn(A, SESSION))
        installationError(Code.CALLER_UNAVAILABLE) { f.service.removeConfirmed(agent, original) { true } }
        assertTrue(agent.isOpen)
        f.foreground.select(B, B_SESSION)
        val wrongOwner = requireNotNull(f.foreground.openConfirmation(B, B_SESSION))
        installationError(Code.DENIED) { f.service.replaceConfirmed(wrongOwner, original, digest(newRaw)) { true } }
        assertFalse(wrongOwner.isOpen)
        f.foreground.select(A, SESSION)
        val expired = f.confirmation()
        f.foreground.pause(f.workbench)
        f.foreground.resume(f.workbench)
        installationError(Code.CALLER_UNAVAILABLE) { f.service.replaceConfirmed(expired, original, digest(newRaw)) { true } }
        val denied = f.confirmation()
        installationError(Code.DENIED) { f.service.removeConfirmed(denied, original) { false } }
        installationError(Code.CALLER_UNAVAILABLE) { f.service.removeConfirmed(denied, original) { true } }
        assertEquals(initialReads, f.files.reads.size)
        assertEquals(initialLists, f.store.listRequests.size)
        assertEquals(0, f.store.changeCalls)

        var allowed = true
        f.store.onList = { allowed = false }
        installationError(Code.DENIED) { f.service.replaceConfirmed(f.confirmation(), original, digest(newRaw)) { allowed } }
        assertEquals(initialReads, f.files.reads.size)
        assertEquals(0, f.store.changeCalls)
        f.store.onList = {}
        allowed = true
        f.store.beforePublication = { allowed = false }
        installationError(Code.DENIED) { f.service.removeConfirmed(f.confirmation(), original) { allowed } }
        f.store.beforePublication = { f.foreground.select(B, B_SESSION); f.foreground.select(A, SESSION) }
        installationError(Code.CALLER_UNAVAILABLE) { f.service.replaceConfirmed(f.confirmation(), original, digest(newRaw)) { true } }
        assertEquals(2, f.store.changeCalls)
        assertEquals(1, f.store.publications)
        assertEquals(original, f.service.list(A).single().record)
    }

    @Test
    fun `replacement checks new raw digest and validates source without repairing unreadable rows`() {
        val f = Fixture()
        val raw = f.files.put()
        val original = f.save(raw)
        val snapshot = f.store.snapshot()
        f.files.drafts.clear()
        val missingDraft = assertFailsWith<WorkspaceFileException> {
            f.service.replaceConfirmed(f.confirmation(), original, digest(raw)) { true }
        }
        assertEquals(WorkspaceFileException.Code.FILE_NOT_FOUND, missingDraft.code)
        assertEquals(original, f.service.list(A).single().record)
        val malformed = f.files.put(bytes = "not JSON".encodeToByteArray())
        installationError(Code.VERSION_CHANGED) { f.service.replaceConfirmed(f.confirmation(), original, digest(raw)) { true } }
        installationError(Code.INVALID_SOURCE) { f.service.replaceConfirmed(f.confirmation(), original, digest(malformed)) { true } }
        val reviewed = f.files.put(bytes = source(title = "Reviewed"))
        val reads = f.files.reads.size
        val variants = listOf(
            AuthoredPluginStoredInstallation.Broken(ID) to Code.CORRUPT,
            snapshot.copy(record = snapshot.record.copy(installationId = "")) to Code.CORRUPT,
            AuthoredPluginStoredInstallation.Unsupported(ID) to Code.UNSUPPORTED,
        )
        for ((row, failure) in variants) {
            f.disk.rows[A to ID] = row
            installationError(failure) { f.service.removeConfirmed(f.confirmation(), original) { true } }
            installationError(failure) { f.service.replaceConfirmed(f.confirmation(), original, digest(reviewed)) { true } }
            assertSame(row, f.disk.rows[A to ID])
        }
        assertEquals(reads, f.files.reads.size)
        assertEquals(0, f.store.changeCalls)
    }

    @Test
    fun `replacement can keep a full workspace slot and removal frees just that slot`() {
        val f = Fixture()
        val max = AuthoredPluginInstallations.MAX_PER_WORKSPACE
        repeat(max - 1) { index ->
            val id = "draft-$index"
            f.disk.rows[A to id] = AuthoredPluginStoredInstallation.Broken(id)
        }
        val original = f.save(f.files.put())
        val reviewed = f.files.put(bytes = transform())
        val replacement = f.service.replaceConfirmed(f.confirmation(), original, digest(reviewed)) { true }
        assertEquals(max, f.service.list(A).size)
        val extra = f.files.put(id = "extra")
        val refused = f.confirmation()
        installationError(Code.QUOTA) { f.service.saveConfirmed(refused, "extra", digest(extra)) { true } }
        f.service.removeConfirmed(f.confirmation(), replacement) { true }
        installationError(Code.CALLER_UNAVAILABLE) { f.service.saveConfirmed(refused, "extra", digest(extra)) { true } }
        assertEquals(max - 1, f.service.list(A).size)
        assertEquals("extra", f.save(extra, "extra").id)
        assertEquals(max, f.service.list(A).size)
    }

    @Test
    fun `uncertain change requires inspection and is never replayed`() {
        val f = Fixture()
        val original = f.save(f.files.put())
        val reviewed = f.files.put(bytes = transform())
        f.store.afterPublication = { throw AuthoredPluginInstallationStoreException(StoreCode.COMMIT_UNKNOWN, PRIVATE) }
        val replacementConfirmation = f.confirmation()
        storeError(StoreCode.COMMIT_UNKNOWN) {
            f.service.replaceConfirmed(replacementConfirmation, original, digest(reviewed)) { true }
        }
        assertFalse(replacementConfirmation.isOpen)
        assertEquals(1, f.store.changeCalls)
        val replacement = requireNotNull(f.service.list(A).single().record)
        assertEquals(digest(reviewed), replacement.sha256)
        assertTrue(original.installationId != replacement.installationId)
        installationError(Code.CALLER_UNAVAILABLE) {
            f.service.replaceConfirmed(replacementConfirmation, original, digest(reviewed)) { true }
        }
        val removalConfirmation = f.confirmation()
        storeError(StoreCode.COMMIT_UNKNOWN) { f.service.removeConfirmed(removalConfirmation, replacement) { true } }
        assertEquals(2, f.store.changeCalls)
        assertTrue(f.service.list(A).isEmpty())
        installationError(Code.CALLER_UNAVAILABLE) { f.service.removeConfirmed(removalConfirmation, replacement) { true } }
        assertEquals(2, f.store.changeCalls)
    }

    @Test
    fun `concurrent confirmations for the same generation publish at most one change`() {
        val disk = Disk()
        val first = Fixture(disk)
        val second = Fixture(disk)
        val raw = first.files.put()
        val original = first.save(raw)
        val bothChecked = CountDownLatch(2)
        val firstDone = CountDownLatch(1)
        first.store.beforeChangeCheck = {
            bothChecked.countDown()
            assertTrue(bothChecked.await(5, TimeUnit.SECONDS))
        }
        second.store.beforeChangeCheck = {
            bothChecked.countDown()
            assertTrue(firstDone.await(5, TimeUnit.SECONDS))
        }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val winner = workers.submit<AuthoredPluginInstalledRecord> {
                try {
                    first.service.replaceConfirmed(first.confirmation(), original, digest(raw)) { true }
                } finally {
                    firstDone.countDown()
                }
            }
            val loser = workers.submit<Code> {
                assertFailsWith<AuthoredPluginInstallationException> {
                    second.service.removeConfirmed(second.confirmation(), original) { true }
                }.code
            }
            val replacement = winner.get(10, TimeUnit.SECONDS)
            assertEquals(Code.VERSION_CHANGED, loser.get(10, TimeUnit.SECONDS))
            assertEquals(1, first.store.changeGates)
            assertEquals(1, second.store.changeCalls)
            assertEquals(0, second.store.changeGates)
            assertEquals(replacement, second.service.list(A).single().record)
        } finally {
            workers.shutdownNow()
        }
    }

    @Test
    fun `disappearance after validation does not become an insert and never reaches the gate`() {
        val f = Fixture()
        val raw = f.files.put()
        val original = f.save(raw)
        val peer = Fixture(f.disk)
        f.store.beforeChangeCheck = { peer.service.removeConfirmed(peer.confirmation(), original) { true } }
        installationError(Code.NOT_FOUND) { f.service.replaceConfirmed(f.confirmation(), original, digest(raw)) { true } }
        assertEquals(1, f.store.changeCalls)
        assertEquals(0, f.store.changeGates)
        assertEquals(1, peer.store.changeGates)
        assertTrue(f.service.list(A).isEmpty())
    }

    private class Fixture(val disk: Disk = Disk()) {
        val files = Files()
        val store = Store(disk)
        val service = AuthoredPluginInstallations(files, store, clock(FIRST_TIME))
        val workbench = Any()
        val foreground = AuthoredPluginForeground().apply { resume(workbench); select(A, SESSION) }

        fun confirmation() = requireNotNull(foreground.openConfirmation(A, SESSION))
        fun save(bytes: ByteArray, id: String = ID, maySave: () -> Boolean = { true }) =
            service.saveConfirmed(confirmation(), id, digest(bytes), maySave)
    }

    private class RuntimeFixture(files: Files, mayManage: (ToolInvocationContext) -> Boolean = { false }) {
        private val registry = InMemoryToolDefinitionRegistry()
        val kernel = PluginKernel()
        val runtime = AuthoredPluginRuntime(files, NoApproval, kernel, PluginToolContributions(registry), mayManage)
        val manager = ToolManagerPlugin(registry, NoPreferences, Workspaces, { PluginState.ENABLED },
            sessionWorkspace = mapOf(SESSION to A, OTHER_SESSION to A, B_SESSION to B)::get).apply { onEnable() }

        fun context(workspace: String = A, session: String = SESSION) = ToolInvocationContext(
            workspace, sessionId = session, toolRegistrations = manager.resolveForTurn(ToolActivationContext(workspace, sessionId = session))
                .associate { it.descriptor.id to requireNotNull(it.registration) },
        )

        fun activate(
            installations: AuthoredPluginInstallations,
            confirmation: AuthoredPluginTurn,
            saved: AuthoredPluginInstalledRecord,
            mayActivate: () -> Boolean = { true },
        ) = runtime.activateSavedForSession(installations, confirmation, saved.id, saved.sha256, mayActivate = mayActivate)
    }

    private object NoPreferences : MutableToolPreferenceStore {
        override fun isEnabled(workspaceId: String, toolId: String): Boolean? = null
        override fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean?) = error("unexpected preference write")
    }

    private object Workspaces : WorkspaceManagerService {
        private val workspaces = listOf(Workspace(A, "A", 1L, 1L), Workspace(B, "B", 1L, 1L))
        override fun findSessionWorkspace(workspaceId: String) = get(workspaceId)
        override fun get(workspaceId: String) = workspaces.find { it.id == workspaceId }
        override fun resolveSessionWorkspace(workspaceId: String) = requireNotNull(get(workspaceId))
        override fun initializeDefaultIfEligible() = error("not called")
        override fun create(name: String) = error("not called")
        override fun list(includeArchived: Boolean) = error("not called")
        override fun rename(workspaceId: String, name: String) = error("not called")
        override fun archive(workspaceId: String) = error("not called")
        override fun restore(workspaceId: String) = error("not called")
        override fun select(workspaceId: String) = error("not called")
        override fun activeWorkspace() = error("not called")
        override fun resolveRoot(workspaceId: String) = error("not called")
    }

    private data class Read(val workspace: String, val path: String, val offset: Long, val maxBytes: Int)

    private class Files : WorkspaceFileService {
        val drafts = mutableMapOf<Pair<String, String>, ByteArray>()
        val reads = mutableListOf<Read>()
        var failReads: WorkspaceFileException? = null

        fun put(workspace: String = A, id: String = ID, bytes: ByteArray = source(id)): ByteArray =
            bytes.also { drafts[workspace to AuthoredPluginSource.path(id)] = it.copyOf() }

        override fun read(workspaceId: String, relativePath: String, offset: Long, maxBytes: Int): WorkspaceFileContent {
            reads += Read(workspaceId, relativePath, offset, maxBytes)
            failReads?.let { throw it }
            val value = drafts[workspaceId to relativePath] ?: throw WorkspaceFileException(WorkspaceFileException.Code.FILE_NOT_FOUND, PRIVATE)
            return WorkspaceFileContent(value.copyOf(minOf(value.size, maxBytes)), "not-the-digest-or-an-authority")
        }

        override fun list(workspaceId: String, relativeDirectory: String, cursor: String?, limit: Int) = error("unexpected list")
        override fun write(workspaceId: String, relativePath: String, bytes: ByteArray, expectedRevision: String?) = error("unexpected draft write")
        override fun delete(workspaceId: String, relativePath: String, expectedRevision: String) = error("unexpected delete")
        override fun move(workspaceId: String, sourceRelativePath: String, destinationRelativePath: String, expectedRevision: String) = error("unexpected move")
    }

    private class Disk {
        val rows = mutableMapOf<Pair<String, String>, AuthoredPluginStoredInstallation>()
    }

    private class Store(private val disk: Disk) : AuthoredPluginInstallationStore {
        var insertCalls = 0
        var changeCalls = 0
        var changeGates = 0
        var publications = 0
        var failList: StoreCode? = null
        var onList: () -> Unit = {}
        val listRequests = mutableListOf<String>()
        var beforeChangeCheck: () -> Unit = {}
        var beforePublication: () -> Unit = {}
        var afterPublication: () -> Unit = {}

        override fun list(workspaceId: String): List<AuthoredPluginStoredInstallation> = synchronized(disk) {
            listRequests += workspaceId
            failList?.let { throw AuthoredPluginInstallationStoreException(it, PRIVATE) }
            onList()
            disk.rows.entries.filter { it.key.first == workspaceId }.map { it.value.detached() }
        }

        fun snapshot(workspaceId: String = A, id: String = ID): AuthoredPluginStoredInstallation.Snapshot =
            list(workspaceId).single { it.id == id } as AuthoredPluginStoredInstallation.Snapshot

        override fun insertIfAbsent(
            workspaceId: String,
            installation: AuthoredPluginStoredInstallation.Snapshot,
            commit: (() -> Unit) -> Unit,
        ): AuthoredPluginInstallationWriteResult = synchronized(disk) {
            insertCalls++
            check(workspaceId == installation.record.workspaceId)
            val key = workspaceId to installation.id
            disk.rows[key]?.let { return@synchronized AuthoredPluginInstallationWriteResult.Existing(it.detached()) }
            if (disk.rows.keys.count { it.first == workspaceId } >= AuthoredPluginInstallations.MAX_PER_WORKSPACE) {
                return@synchronized AuthoredPluginInstallationWriteResult.QuotaExceeded
            }
            assertCanonicalInstallationId(installation.record.installationId)
            val staged = installation.detached()
            beforePublication()
            commit { disk.rows[key] = staged; publications++ }
            afterPublication()
            AuthoredPluginInstallationWriteResult.Inserted
        }

        override fun changeIfCurrent(
            workspaceId: String,
            expected: AuthoredPluginInstalledRecord,
            replacement: AuthoredPluginStoredInstallation.Snapshot?,
            commit: (() -> Unit) -> Unit,
        ): AuthoredPluginInstallationChangeResult {
            // Simulate time between the service's review read and the Store's transaction.
            beforeChangeCheck()
            return synchronized(disk) {
                changeCalls++
                check(workspaceId == expected.workspaceId)
                val key = workspaceId to expected.id
                val current = disk.rows[key] ?: return@synchronized AuthoredPluginInstallationChangeResult.NOT_FOUND
                if (expected.installationId.isEmpty() || current !is AuthoredPluginStoredInstallation.Snapshot ||
                    current.record != expected || digest(current.sourceBytes) != expected.sha256
                ) return@synchronized AuthoredPluginInstallationChangeResult.STALE

                if (replacement != null) {
                    check(replacement.record.workspaceId == workspaceId && replacement.id == expected.id)
                    check(replacement.record.installationId != expected.installationId)
                    assertCanonicalInstallationId(replacement.record.installationId)
                }
                val staged = replacement?.detached()
                beforePublication()
                changeGates++
                commit {
                    if (staged == null) disk.rows.remove(key) else disk.rows[key] = staged
                    publications++
                }
                afterPublication()
                AuthoredPluginInstallationChangeResult.CHANGED
            }
        }

        private fun AuthoredPluginStoredInstallation.detached(): AuthoredPluginStoredInstallation = when (this) {
            is AuthoredPluginStoredInstallation.Snapshot -> copy(record = record.copy(toolIds = record.toolIds.toList()), sourceBytes = sourceBytes.copyOf())
            else -> this
        }
    }

    private object NoApproval : ApprovalGateway {
        override fun requestApproval(request: ApprovalRequest, timeoutMillis: Long): ApprovalDecision = error("trusted installation or activation must not request tool approval")
        override fun respond(requestId: String, decision: ApprovalDecision) = error("unexpected approval response")
        override fun pendingRequests() = emptyList<ApprovalRequest>()
        override fun clear() = error("unexpected approval clear")
    }

    private companion object {
        const val A = "workspace-a"
        const val B = "workspace-b"
        const val ID = "checklist"
        const val SESSION = "session"
        const val OTHER_SESSION = "other-session"
        const val B_SESSION = "workspace-b-session"
        const val TEXT_TOOL = "extension.authored.$ID.show"
        const val FIRST_TIME = 1000L
        const val PRIVATE = "PRIVATE_BACKEND_OR_TAMPERED_DATA"

        fun source(id: String = ID, title: String = "Checklist", text: String = "固定内容") =
            """{"schemaVersion":1,"id":"$id","title":"$title","tools":[{"name":"show","description":"Show checklist","text":"$text"}]}""".encodeToByteArray()

        fun transform() =
            """{"schemaVersion":2,"id":"$ID","title":"Checklist","tools":[{"kind":"text_transform","name":"cut","description":"Trim","operation":"trim"}]}""".encodeToByteArray()

        fun assertCanonicalInstallationId(id: String) = assertEquals(id, UUID.fromString(id).toString())
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()
        fun clock(millis: Long) = Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC)

        fun installationError(code: Code, run: () -> Unit) {
            val error = assertFailsWith<AuthoredPluginInstallationException>(block = run)
            assertEquals(code, error.code)
            assertFalse(PRIVATE in error.message.orEmpty())
        }

        fun runtimeError(code: RuntimeCode, run: () -> Unit) {
            assertEquals(code, assertFailsWith<AuthoredPluginException>(block = run).code)
        }

        fun storeError(code: StoreCode, run: () -> Unit) {
            val error = assertFailsWith<AuthoredPluginInstallationStoreException>(block = run)
            assertEquals(code, error.code)
            assertFalse(PRIVATE in error.message.orEmpty())
        }
    }
}

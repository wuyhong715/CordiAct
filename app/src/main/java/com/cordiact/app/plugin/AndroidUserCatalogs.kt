package com.cordiact.app.plugin

import android.util.AtomicFile
import com.cordiact.core.plugin.CatalogSource
import com.cordiact.core.sandbox.sha256Hex
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Device-private user trust. Removed entries keep their namespace and checkpoint ownership. */
internal class AndroidUserCatalogs(private val privateDirectory: File, private val bundledSources: List<CatalogSource>) {
    class Candidate internal constructor(data: ByteArray, internal val policy: CatalogBuildPolicy) {
        private val raw = data.copyOf()
        val id = sha256Hex(raw)
        val rootFingerprint = sha256Hex(policy.rootKey.encoded)
        val sources get() = policy.sources
        val rootRevision get() = policy.minimumRootRevision
        val rootDigest get() = policy.minimumRootDigest
        val rootSource get() = policy.trustSourceId
        fun copyBytes() = raw.copyOf()
    }

    data class Directory(val id: String, val rootFingerprint: String, val access: AndroidSignedCatalogAccess)

    private class Entry(val candidate: Candidate, val directory: Directory?) {
        val enabled get() = directory != null
    }

    private val file = AtomicFile(File(privateDirectory, "cordiact-user-catalogs.v1"))
    @Volatile private var entries = load()
    @Volatile var uncertain = false
        private set
    private val mutable = MutableStateFlow(entries.mapNotNull { it.directory })
    val directories = mutable.asStateFlow()

    fun reserves(id: String): Boolean = uncertain || entries.any { entry -> entry.candidate.sources.any { id.startsWith(it.packagePrefix) } }

    fun catalogForSource(sourceId: String) = mutable.value.firstOrNull { it.access.ownsSource(sourceId) }?.access?.catalog()

    @Synchronized fun add(candidate: Candidate) {
        check(!uncertain) { "CATALOG_CONFIGURATION_UNAVAILABLE" }
        val existing = entries.find { it.candidate.id == candidate.id }
        if (existing?.enabled == true) return
        val others = entries.filter { it !== existing }
        require(others.size < MAX_CATALOGS) { "CATALOG_CONFIGURATION_LIMIT" }
        require(!overlaps(candidate.sources, bundledSources + others.flatMap { it.candidate.sources })) { "CATALOG_SOURCE_CONFLICT" }
        publish(others + entry(candidate, enabled = true))
    }

    @Synchronized fun remove(expected: Directory) {
        check(!uncertain) { "CATALOG_CONFIGURATION_UNAVAILABLE" }
        check(entries.any { it.directory === expected }) { "CATALOG_CONFIGURATION_EXPIRED" }
        publish(entries.map { if (it.directory === expected) entry(it.candidate, enabled = false) else it })
    }

    private fun entry(candidate: Candidate, enabled: Boolean) = Entry(candidate, if (enabled) {
        Directory(candidate.id, candidate.rootFingerprint,
            AndroidSignedCatalogAccess.forUser(candidate.policy, privateDirectory.canonicalFile.toPath(), candidate.id))
    } else null)

    private fun load(): List<Entry> {
        val stream = try { file.openRead() } catch (missing: FileNotFoundException) {
            if (!file.baseFile.exists()) return emptyList()
            throw missing
        }
        return DataInputStream(stream).use { input ->
            check(input.readInt() == MAGIC)
            val count = input.readUnsignedByte()
            check(count <= MAX_CATALOGS)
            val records = ArrayList<Entry>(count)
            repeat(count) {
                val enabled = input.readUnsignedByte()
                val size = input.readUnsignedShort()
                check(enabled in 0..1 && size in 1..CatalogBuildPolicy.MAX_POLICY_BYTES)
                val bytes = ByteArray(size)
                input.readFully(bytes)
                val candidate = preview(bytes.inputStream())
                check(!overlaps(candidate.sources, bundledSources + records.flatMap { it.candidate.sources }))
                records += entry(candidate, enabled == 1)
            }
            check(input.read() == -1)
            records
        }
    }

    private fun publish(next: List<Entry>) {
        try {
            val out = file.startWrite()
            try {
                val data = DataOutputStream(out)
                data.writeInt(MAGIC)
                data.writeByte(next.size)
                next.forEach { record ->
                    val bytes = record.candidate.copyBytes()
                    data.writeByte(if (record.enabled) 1 else 0)
                    data.writeShort(bytes.size)
                    data.write(bytes)
                }
                data.flush()
                out.fd.sync()
                file.finishWrite(out)
            } catch (failure: Exception) {
                file.failWrite(out)
                throw failure
            }
            fun identity(records: List<Entry>) = records.map { it.candidate.id to it.enabled }
            check(identity(load()) == identity(next)) { "CATALOG_CONFIGURATION_UNAVAILABLE" }
        } catch (failure: Exception) {
            uncertain = true
            entries.forEach { it.directory?.access?.retire() }
            mutable.value = emptyList()
            throw failure
        }
        entries.filter { it !in next }.forEach { it.directory?.access?.retire() }
        entries = next.toList()
        mutable.value = next.mapNotNull { it.directory }
    }

    companion object {
        private const val MAX_CATALOGS = 8
        private const val MAGIC = 0x43445531

        fun preview(input: InputStream): Candidate {
            val bytes = ByteArray(CatalogBuildPolicy.MAX_POLICY_BYTES + 1)
            var offset = 0
            while (offset < bytes.size) {
                val count = input.read(bytes, offset, bytes.size - offset)
                if (count < 0) break
                require(count > 0) { "CATALOG_CONFIGURATION_UNAVAILABLE" }
                offset += count
            }
            val raw = bytes.copyOf(offset)
            return Candidate(raw, CatalogBuildPolicy.read(raw.inputStream()))
        }

        private fun overlaps(first: List<CatalogSource>, second: List<CatalogSource>): Boolean = first.any { a ->
            second.any { b -> a.id == b.id || a.packagePrefix.startsWith(b.packagePrefix) || b.packagePrefix.startsWith(a.packagePrefix) }
        }
    }
}

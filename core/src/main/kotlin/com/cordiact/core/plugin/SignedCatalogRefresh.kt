package com.cordiact.core.plugin

import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** The host calls these from explicit, separate actions. No startup refresh or transport retry. */
class SignedCatalogRefresh internal constructor(
    private val catalog: SignedPackageCatalog,
    private val trustSourceId: String,
    private val transport: CatalogDocumentTransport,
) {
    constructor(catalog: SignedPackageCatalog, trustSourceId: String) : this(catalog, trustSourceId, IsolatedCatalogHttps())

    fun prepareTrust() = prepare(catalog.trustRefreshTarget(trustSourceId))
    fun prepareIndex(sourceId: String, keyId: String) = prepare(catalog.indexRefreshTarget(sourceId, keyId))

    private fun prepare(target: SignedPackageCatalog.RefreshTarget) = Operation { cancellation, checkScope, publish ->
        fun admit() {
            checkScope()
            cancellation.checkActive()
            catalog.checkRefresh(target)
            cancellation.checkActive()
            checkScope()
        }
        admit()
        val document = CatalogSignedDocument.decode(transport.getDocument(target.uri, cancellation, ::admit))
        cancellation.checkActive()
        publish {
            if (target.sourceId == null) catalog.acceptTrust(document.bytes, document.signature, target)
            else catalog.acceptIndex(target.sourceId, requireNotNull(target.keyId), document.bytes, document.signature, target)
        }
    }

    class Operation internal constructor(
        private val execute: (CatalogDownloadCancellation, () -> Unit, (() -> Unit) -> Unit) -> Unit,
    ) {
        private val used = AtomicBoolean()
        private val cancellation = CatalogDownloadCancellation()
        private val lock = Any()
        private var publishing = false

        fun refresh() = refresh({}, { publish -> publish() })

        /** Page-bound callers must synchronously guard the final publication under their scope lock. */
        internal fun refresh(checkScope: () -> Unit, scopePublish: (() -> Unit) -> Unit) {
            check(used.compareAndSet(false, true)) { "CATALOG_REFRESH_ALREADY_USED" }
            execute(cancellation, checkScope) { commit ->
                scopePublish {
                    synchronized(lock) {
                        cancellation.checkActive()
                        check(!publishing)
                        publishing = true
                    }
                    commit()
                }
            }
        }

        /** Cancellation wins until final admission. It cannot undo a local commit already admitted. */
        fun cancel() = synchronized(lock) {
            if (!publishing) cancellation.cancel()
        }
    }
}

internal fun interface CatalogDocumentTransport {
    fun getDocument(uri: URI, cancellation: CatalogDownloadCancellation, beforeSend: () -> Unit): ByteArray
}

/** Wire: `CDX1`, big-endian u32 body size, u16 signature size, exact JSON bytes, exact DER signature. */
internal class CatalogSignedDocument private constructor(val bytes: ByteArray, val signature: ByteArray) {
    companion object {
        private const val MAGIC = 0x43445831
        private const val HEADER = 10
        private const val MAX_BODY = 64 * 1024
        private const val MAX_SIGNATURE = 72
        const val MAX_FRAME_SIZE = HEADER + MAX_BODY + MAX_SIGNATURE

        fun decode(frame: ByteArray): CatalogSignedDocument {
            require(frame.size in (HEADER + 1 + 8)..MAX_FRAME_SIZE) { "CATALOG_INVALID_FRAME" }
            val data = ByteBuffer.wrap(frame)
            require(data.int == MAGIC) { "CATALOG_INVALID_FRAME" }
            val bodySize = data.int
            val signatureSize = data.short.toInt()
            require(bodySize in 1..MAX_BODY && signatureSize in 8..MAX_SIGNATURE && data.remaining() == bodySize + signatureSize) {
                "CATALOG_INVALID_FRAME"
            }
            return CatalogSignedDocument(ByteArray(bodySize).also { data.get(it) }, ByteArray(signatureSize).also { data.get(it) })
        }
    }
}
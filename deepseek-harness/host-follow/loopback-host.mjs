import { listenFollowTls } from './tls-follow.mjs'
import { createFollowPairingController, describeLoopbackFollowRoute } from './pairing-offer.mjs'
import { approveFollowOnTerminal } from './operator-tty.mjs'

/**
 * Explicit in-process assembly for a single pre-enrolled route. Owns the listener and this
 * route's grant, not the shared DSH services. The caller must own the terminal and attach
 * close to its own lifecycle. No transport for submitting challenges is provided here.
 */
export async function startLoopbackFollowHost({ reader, authority, identity, signingKey, deliveryKey, scope, signal }) {
  if (signal?.aborted) throw new Error('Follow Host stopped')
  if (!identity) throw new TypeError('An explicit certificate and private key are required')
  const { connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId } = scope
  const cancelled = new AbortController()
  const listener = await listenFollowTls({ reader, identity })
  let pairing
  let stopping

  function close() {
    if (stopping) return stopping
    signal?.removeEventListener('abort', abort)
    cancelled.abort()
    try { pairing?.close() }
    finally { stopping = listener.close() }
    return stopping
  }
  function abort() { void close() }

  try {
    const endpoint = Object.freeze({ ip: '127.0.0.1', port: listener.port, certificateSha256: listener.certificateSha256 })
    const route = { connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId, ...endpoint }
    pairing = createFollowPairingController({ authority, signingKey, deliveryKey, route })
    const descriptor = describeLoopbackFollowRoute(route, signingKey)
    if (signal?.aborted) throw new Error('Follow Host stopped')
    signal?.addEventListener('abort', abort, { once: true })
    void listener.closed.then(abort)
    return Object.freeze({ endpoint, descriptor, close,
      approve: (nonce, proof) => approveFollowOnTerminal({ controller: pairing, nonce, proof, signal: cancelled.signal }),
      revoke: () => pairing.revoke(),
    })
  } catch (error) {
    await close()
    throw error
  }
}

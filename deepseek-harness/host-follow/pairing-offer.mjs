import { isIPv4 } from 'node:net'
import { createCipheriv, randomBytes, sign } from 'node:crypto'

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}(?![\s\S])/u
const HEX_256 = /^[0-9a-f]{64}(?![\s\S])/u
const TOKEN = /^caf1_[A-Za-z0-9_-]{43}(?![\s\S])/u
const GRANT_MS = 15_000

/**
 * Trusted control-plane helper, not a network handler. The caller supplies a route it has already
 * approved and a challenge nonce received out of band. Never expose authority or signingKey to the
 * TLS carrier. This low-level function returns plaintext; use issueSealedFollowPairingOffer for
 * delivery. The caller owns the offer bytes and the revoke handle.
 */
export function issueFollowPairingOffer({ authority, signingKey, route, nonce }) {
  const { connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId, ip, port, certificateSha256 } = route
  const ids = [connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId]
  if (!ids.every(id => typeof id === 'string' && ID.test(id)) || typeof ip !== 'string' || !isIPv4(ip)
    || !Number.isInteger(port) || port < 1 || port > 65535
    || typeof certificateSha256 !== 'string' || !HEX_256.test(certificateSha256)
    || typeof nonce !== 'string' || !HEX_256.test(nonce)
    || signingKey?.type !== 'private' || signingKey.asymmetricKeyType !== 'ec'
    || signingKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw new TypeError('Invalid approved pairing route or signing key')
  }
  const grant = authority.issue(remoteWorkspaceId, remoteSessionId, GRANT_MS)
  let offer
  try {
    if (typeof grant.token !== 'string' || !TOKEN.test(grant.token)) throw new Error('Invalid follow grant')
    offer = Buffer.from(`cordiact-follow-pair/1 ${ids.join(' ')} ${ip} ${port} ${certificateSha256} ${nonce} ${grant.token}\n`, 'ascii')
    const signature = sign('sha256', offer, { key: signingKey, dsaEncoding: 'der' })
    return Object.freeze({ offer, signature, revoke: grant.revoke })
  } catch (error) {
    offer?.fill(0)
    grant.revoke()
    throw error
  }
}

/** Seal a signed offer with a separately provisioned, per-route random 256-bit delivery key. */
export function issueSealedFollowPairingOffer({ authority, signingKey, route, nonce, deliveryKey }) {
  if (!Buffer.isBuffer(deliveryKey) || deliveryKey.length !== 32) throw new TypeError('Invalid pairing delivery key')
  const issued = issueFollowPairingOffer({ authority, signingKey, route, nonce })
  let plain
  try {
    const iv = randomBytes(12)
    const cipher = createCipheriv('aes-256-gcm', deliveryKey, iv, { authTagLength: 16 })
    cipher.setAAD(Buffer.from(`cordiact-follow-seal/1 ${nonce}\n`, 'ascii'))
    plain = Buffer.concat([Buffer.from([issued.signature.length]), issued.signature, issued.offer])
    const envelope = Buffer.concat([iv, cipher.update(plain), cipher.final(), cipher.getAuthTag()])
    return Object.freeze({ envelope, revoke: issued.revoke })
  } catch (error) {
    issued.revoke()
    throw error
  } finally {
    plain?.fill(0)
    issued.offer.fill(0)
  }
}

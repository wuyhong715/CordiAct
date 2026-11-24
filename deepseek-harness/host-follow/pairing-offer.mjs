import { isIPv4 } from 'node:net'
import { sign } from 'node:crypto'

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}(?![\s\S])/u
const HEX_256 = /^[0-9a-f]{64}(?![\s\S])/u
const TOKEN = /^caf1_[A-Za-z0-9_-]{43}(?![\s\S])/u
const GRANT_MS = 15_000

/**
 * Trusted control-plane helper, not a network handler. The caller supplies a route it has already
 * approved and a challenge nonce received out of band. Never expose authority or signingKey to the
 * TLS carrier. Deliver the returned offer through a confidential channel; its signature is not
 * encryption. The caller owns the offer bytes and the revoke handle.
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

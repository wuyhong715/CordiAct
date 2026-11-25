import { isIPv4 } from 'node:net'
import { createCipheriv, randomBytes, sign } from 'node:crypto'
import { performance } from 'node:perf_hooks'

const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}(?![\s\S])/u
const HEX_256 = /^[0-9a-f]{64}(?![\s\S])/u
const TOKEN = /^caf1_[A-Za-z0-9_-]{43}(?![\s\S])/u
const GRANT_MS = 15_000
const MAX_CHALLENGES = 256
const APPROVAL_MS = 3_000

const validNonce = nonce => typeof nonce === 'string' && HEX_256.test(nonce)
const validDeliveryKey = key => Buffer.isBuffer(key) && key.length === 32

function validatedRoute({ connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId, ip, port, certificateSha256 }, signingKey) {
  const ids = [connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId]
  if (!ids.every(id => typeof id === 'string' && ID.test(id)) || typeof ip !== 'string' || !isIPv4(ip)
    || !Number.isInteger(port) || port < 1 || port > 65535
    || typeof certificateSha256 !== 'string' || !HEX_256.test(certificateSha256)
    || signingKey?.type !== 'private' || signingKey.asymmetricKeyType !== 'ec'
    || signingKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1') {
    throw new TypeError('Invalid approved pairing route or signing key')
  }
  return { ids, fields: { connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId, ip, port, certificateSha256 } }
}

/**
 * Trusted control-plane helper, not a network handler. The caller supplies a route it has already
 * approved and a challenge nonce received out of band. Never expose authority or signingKey to the
 * TLS carrier. This low-level function returns plaintext; use issueSealedFollowPairingOffer for
 * delivery. The caller owns the offer bytes and the revoke handle.
 */
export function issueFollowPairingOffer({ authority, signingKey, route, nonce }) {
  const { ids, fields } = validatedRoute(route, signingKey)
  if (!validNonce(nonce)) throw new TypeError('Invalid pairing challenge')
  const { remoteWorkspaceId, remoteSessionId, ip, port, certificateSha256 } = fields
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
  if (!validDeliveryKey(deliveryKey)) throw new TypeError('Invalid pairing delivery key')
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

/**
 * In-process operator control for one approved route. Give capture/redeem only to the trusted
 * operator adapter; these methods are not endpoints and do not authenticate an operator.
 * One issuance per challenge for this controller's lifetime, with a hard cap and no eviction.
 * Create a replacement with newly provisioned trust material and close the old one on rotation.
 */
export function createFollowPairingController({ authority, signingKey, route, deliveryKey }, now = () => performance.now()) {
  const { fields } = validatedRoute(route, signingKey)
  Object.freeze(fields)
  if (!validDeliveryKey(deliveryKey)) throw new TypeError('Invalid pairing delivery key')
  const key = Buffer.from(deliveryKey)
  const seen = new Set()
  let active = null
  let pending = null
  let closed = false
  let issuing = false
  let cancelled = false

  function revoke() {
    // Revocation can synchronously notify observers. Do not let them issue a replacement here.
    const wasIssuing = issuing
    issuing = true
    cancelled = true
    pending = null
    try {
      retireActive()
    } finally {
      issuing = wasIssuing
    }
  }

  function retireActive() {
    const retire = active
    active = null
    retire?.()
  }

  // Capture the exact challenge and immutable route for review. No authority call is made here.
  function capture(nonce) {
    if (closed || issuing || !validNonce(nonce) || seen.has(nonce) || seen.size >= MAX_CHALLENGES || pending?.ticket.nonce === nonce) return null
    const ticket = Object.freeze({ nonce, route: fields })
    pending = { ticket, deadline: now() + APPROVAL_MS }
    return ticket
  }

  // Only the trusted adapter may redeem the exact reviewed object after affirmative consent.
  function redeem(ticket) {
    if (closed || issuing || pending === null || pending.ticket !== ticket) return null
    const { deadline } = pending
    pending = null
    const { nonce } = ticket
    // Capture is bounded and side-effect free; only an attempted redemption consumes the challenge.
    if (now() >= deadline) return null
    seen.add(nonce)
    issuing = true
    cancelled = false
    try {
      retireActive()
      if (cancelled) return null
      const result = issueSealedFollowPairingOffer({ authority, signingKey, route: fields, nonce, deliveryKey: key })
      if (cancelled) {
        result.revoke()
        return null
      }
      active = result.revoke
      return result.envelope
    } catch {
      return null
    } finally {
      issuing = false
    }
  }

  function close() {
    if (closed) return
    closed = true
    key.fill(0)
    seen.clear()
    revoke()
  }

  function dismiss(ticket) {
    if (pending === null || pending.ticket !== ticket) return false
    pending = null
    return true
  }

  return Object.freeze({ capture, redeem, dismiss, revoke, close })
}

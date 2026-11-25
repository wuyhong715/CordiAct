import assert from 'node:assert/strict'
import { createDecipheriv, generateKeyPairSync, randomBytes, verify } from 'node:crypto'
import test from 'node:test'
import { createFollowService } from '../follow-service.mjs'
import { createFollowPairingController, issueFollowPairingOffer, issueSealedFollowPairingOffer } from '../pairing-offer.mjs'

import { makeProof } from './challenge-proof.fixture.mjs'

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
const route = Object.freeze({ connectionId: 'host-a', localWorkspaceId: 'local-a', remoteWorkspaceId: 'remote-a',
  remoteSessionId: 'session-a', ip: '127.0.0.1', port: 12345, certificateSha256: '00'.repeat(32) })
const nonce = '01'.repeat(32)
const token = `caf1_${'A'.repeat(43)}`
const challenge = ordinal => ordinal.toString(16).padStart(64, '0')
const testKey = randomBytes(32)
const capture = (controller, nonce, key = testKey) => controller.capture(nonce, makeProof(key, route, nonce))
const consent = (controller, nonce, key) => controller.redeem(capture(controller, nonce, key))

function unseal(envelope, key, nonce) {
  const decipher = createDecipheriv('aes-256-gcm', key, envelope.subarray(0, 12))
  decipher.setAAD(Buffer.from(`cordiact-follow-seal/1 ${nonce}\n`, 'ascii'))
  decipher.setAuthTag(envelope.subarray(-16))
  return Buffer.concat([decipher.update(envelope.subarray(12, -16)), decipher.final()])
}

test('signs the exact approved tuple and only requests a short scoped grant', () => {
  let revoked = 0
  const authority = { issue(...args) {
    assert.deepEqual(args, ['remote-a', 'session-a', 15_000])
    return { token, revoke: () => { revoked++ } }
  } }
  const result = issueFollowPairingOffer({ authority, signingKey: privateKey, route, nonce })
  assert.equal(result.offer.toString('ascii'),
    `cordiact-follow-pair/1 host-a local-a remote-a session-a 127.0.0.1 12345 ${route.certificateSha256} ${nonce} ${token}\n`)
  assert.equal(verify('sha256', result.offer, publicKey, result.signature), true)
  result.revoke()
  assert.equal(revoked, 1)
})

test('delivery seals the signed grant and authenticates the challenge', () => {
  const deliveryKey = randomBytes(32)
  let issued = 0
  const authority = { issue() { issued++; return { token, revoke() {} } } }
  const options = { authority, signingKey: privateKey, route, nonce, deliveryKey }
  assert.throws(() => issueSealedFollowPairingOffer({ ...options, deliveryKey: Buffer.alloc(31) }), TypeError)
  assert.equal(issued, 0)
  const result = issueSealedFollowPairingOffer(options)
  const { envelope } = result
  assert.deepEqual(Object.keys(result).sort(), ['envelope', 'revoke'])
  assert.equal(envelope.includes(token), false)
  assert.notDeepEqual(envelope.subarray(0, 12), issueSealedFollowPairingOffer(options).envelope.subarray(0, 12))
  assert.throws(() => unseal(envelope, deliveryKey, '02'.repeat(32)))
  const plain = unseal(envelope, deliveryKey, nonce)
  const split = plain[0] + 1
  assert.equal(verify('sha256', plain.subarray(split), publicKey, plain.subarray(1, split)), true)
  assert.equal(plain.subarray(split).toString('ascii').endsWith(` ${token}\n`), true)
})

test('invalid route, challenge, or key cannot reach issuance; malformed grants are retired', () => {
  let issued = 0
  let revoked = 0
  const authority = { issue() {
    issued++
    return { token: 'wrong-format', revoke: () => { revoked++ } }
  } }
  const base = { authority, signingKey: privateKey, route, nonce }
  for (const replacement of [
    { route: { ...route, remoteSessionId: 'session-a\n' } },
    { route: { ...route, ip: 'localhost' } },
    { route: { ...route, certificateSha256: `${route.certificateSha256}\n` } },
    { nonce: `${nonce}\n` },
    { signingKey: publicKey },
  ]) assert.throws(() => issueFollowPairingOffer({ ...base, ...replacement }), TypeError)
  assert.equal(issued, 0)
  assert.throws(() => issueFollowPairingOffer(base), /Invalid follow grant/)
  assert.equal(issued, 1)
  assert.equal(revoked, 1)
})

test('controller binds one route and key, rejects duplicate challenges and retires replaced grants', async () => {
  const service = createFollowService((work, session) => work === 'remote-a' && session === 'session-a'
    ? { identity: {}, read: () => ({ cursor: -1, active: false, running: false }) } : null)
  const inputRoute = { ...route }
  const inputKey = randomBytes(32)
  const originalKey = Buffer.from(inputKey)
  const controller = createFollowPairingController({ authority: service, signingKey: privateKey, route: inputRoute, deliveryKey: inputKey })
  function readGrant(envelope, nonce) {
    const plain = unseal(envelope, originalKey, nonce)
    const fields = plain.subarray(plain[0] + 1).toString('ascii').trimEnd().split(' ')
    assert.equal(fields[3], 'remote-a')
    return fields.at(-1)
  }
  const open = token => service.reader.open({ version: 1, workspaceId: 'remote-a', sessionId: 'session-a', token })
  try {
    inputRoute.remoteWorkspaceId = 'wrong'
    inputKey.fill(0)
    const firstToken = readGrant(consent(controller, challenge(1), originalKey), challenge(1))
    const stream = open(firstToken)
    assert.equal((await stream.next()).done, false)
    assert.equal(consent(controller, challenge(1), originalKey), null)
    assert.equal(consent(controller, 'bad', originalKey), null)
    assert.equal(stream.signal.aborted, false)
    const pendingRead = stream.next()
    const nextToken = readGrant(consent(controller, challenge(2), originalKey), challenge(2))
    assert.deepEqual(await pendingRead, { done: true, reason: 'access-lost' })
    assert.equal(open(firstToken), null)
    controller.close()
    assert.equal(open(nextToken), null)
    assert.equal(consent(controller, challenge(3), originalKey), null)
  } finally {
    controller.close()
    service.dispose()
  }
})

test('capture is passive and redeem requires the exact live review from this controller', () => {
  let attempts = 0
  const authority = { issue() { attempts++; return { token, revoke() {} } } }
  const cfg = { authority, signingKey: privateKey, route, deliveryKey: testKey }
  let now = 0
  const controller = createFollowPairingController(cfg, () => now)
  const other = createFollowPairingController(cfg, () => now)
  try {
    const original = capture(controller, challenge(1))
    assert.equal(attempts, 0)
    assert.deepEqual(original.route, route)
    assert.ok(Object.isFrozen(original) && Object.isFrozen(original.route))
    assert.equal(other.redeem(original), null)
    assert.equal(controller.redeem({ ...original }), null)
    const current = capture(controller, challenge(2))
    assert.equal(controller.redeem(original), null)
    assert.equal(controller.dismiss(original), false)
    assert.equal(controller.dismiss(current), true)
    assert.equal(controller.redeem(current), null)
    const expired = capture(controller, challenge(3))
    now = 3_000
    assert.equal(controller.redeem(expired), null)
    const withdrawn = capture(controller, challenge(4))
    controller.revoke()
    assert.equal(controller.redeem(withdrawn), null)
    assert.equal(attempts, 0)
    const reviewed = capture(controller, challenge(5))
    assert.ok(Buffer.isBuffer(controller.redeem(reviewed)))
    assert.equal(controller.redeem(reviewed), null)
    assert.equal(attempts, 1)
  } finally { controller.close(); other.close() }
})

test('only an exact proof reaches review; denial consumes it without issuing a grant', () => {
  let issued = 0
  const authority = { issue() { issued++; return { token, revoke() {} } } }
  const controller = createFollowPairingController({ authority, signingKey: privateKey, route, deliveryKey: testKey })
  try {
    const existing = capture(controller, challenge(1))
    assert.ok(existing)
    const next = challenge(2)
    const exact = makeProof(testKey, route, next)
    for (const proof of [undefined, exact.toUpperCase(), `${exact}\n`, makeProof(randomBytes(32), route, next),
      makeProof(testKey, route, challenge(3)), ...Object.entries({ connectionId: 'other-host', localWorkspaceId: 'other-local',
        remoteWorkspaceId: 'other-work', remoteSessionId: 'other-session', ip: '127.0.0.2', port: 12346,
        certificateSha256: '01'.repeat(32) }).map(([field, value]) => makeProof(testKey, { ...route, [field]: value }, next))]) {
      assert.equal(controller.capture(next, proof), null)
    }
    assert.equal(issued, 0)
    assert.ok(controller.redeem(existing))
    const denied = controller.capture(next, exact)
    assert.ok(denied)
    assert.equal(controller.dismiss(denied), true)
    assert.equal(controller.capture(next, exact), null)
    assert.equal(issued, 1)
  } finally { controller.close() }
})

test('failed issuance consumes the challenge and the bounded log never evicts it', () => {
  let attempts = 0
  const authority = { issue() { attempts++; throw new Error('source unavailable') } }
  const controller = createFollowPairingController({ authority, signingKey: privateKey, route, deliveryKey: testKey })
  try {
    for (let i = 0; i < 256; i++) assert.equal(consent(controller, challenge(i)), null)
    assert.equal(consent(controller, challenge(0)), null)
    assert.equal(consent(controller, challenge(256)), null)
    assert.equal(attempts, 256)
  } finally { controller.close() }
})

test('failed replacement does not restore the previous grant', () => {
  let attempts = 0
  let revoked = 0
  const authority = { issue() {
    if (++attempts > 1) throw new Error('source unavailable')
    return { token, revoke: () => { revoked++ } }
  } }
  const controller = createFollowPairingController({ authority, signingKey: privateKey, route, deliveryKey: testKey })
  try {
    assert.ok(Buffer.isBuffer(consent(controller, challenge(1))))
    assert.equal(consent(controller, challenge(2)), null)
    assert.equal(revoked, 1)
    assert.equal(consent(controller, challenge(2)), null)
    controller.revoke()
    assert.equal(attempts, 2)
    assert.equal(revoked, 1)
  } finally { controller.close() }
})

test('revoking during issuance cannot publish an offer or re-enter issuance', () => {
  let revoked = 0
  const authority = { issue() {
    assert.equal(consent(controller, challenge(2)), null)
    controller.revoke()
    return { token, revoke: () => { revoked++ } }
  } }
  const controller = createFollowPairingController({ authority, signingKey: privateKey, route, deliveryKey: testKey })
  try {
    assert.equal(consent(controller, challenge(1)), null)
    assert.equal(consent(controller, challenge(1)), null)
    assert.equal(revoked, 1)
  } finally { controller.close() }
})

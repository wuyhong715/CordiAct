import assert from 'node:assert/strict'
import { generateKeyPairSync, verify } from 'node:crypto'
import test from 'node:test'
import { issueFollowPairingOffer } from '../pairing-offer.mjs'

const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
const route = Object.freeze({ connectionId: 'host-a', localWorkspaceId: 'local-a', remoteWorkspaceId: 'remote-a',
  remoteSessionId: 'session-a', ip: '127.0.0.1', port: 12345, certificateSha256: '00'.repeat(32) })
const nonce = '01'.repeat(32)
const token = `caf1_${'A'.repeat(43)}`

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

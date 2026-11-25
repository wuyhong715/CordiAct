import { generateKeyPairSync, randomBytes } from 'node:crypto'
import { approveFollowOnTerminal } from '../operator-tty.mjs'
import { createFollowPairingController } from '../pairing-offer.mjs'
import { makeProof } from './challenge-proof.fixture.mjs'

const { privateKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
let issues = 0
const nonce = '01'.repeat(32)
const deliveryKey = randomBytes(32)
const route = { connectionId: 'fixture-host', localWorkspaceId: 'fixture-local', remoteWorkspaceId: 'remote-w',
  remoteSessionId: 'remote-s', ip: '127.0.0.1', port: 12345, certificateSha256: '00'.repeat(32) }
const controller = createFollowPairingController({ signingKey: privateKey, deliveryKey, route,
  authority: { issue() { issues++; return { token: `caf1_${'X'.repeat(43)}`, revoke() {} } } } })
const cancel = new AbortController()
const timer = process.argv[2] === 'abort' ? setTimeout(() => cancel.abort(), 100) : null
const proof = makeProof(deliveryKey, route, nonce)
deliveryKey.fill(0)
const result = await approveFollowOnTerminal({ controller, nonce, proof, signal: cancel.signal })
clearTimeout(timer)
process.stdout.write(`RESULT=${result ? 'approved' : 'denied'};ISSUES=${issues}\n`)
controller.close()

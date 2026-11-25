import { createHash, createPrivateKey } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { createFollowPairingController } from '../pairing-offer.mjs'
import { createFollowService } from '../follow-service.mjs'
import { listenFollowTls } from '../tls-follow.mjs'

const stop = new AbortController()
const identity = {}
let state = { cursor: 2, active: true, running: false }
const service = createFollowService((workspace, session) => workspace === 'remote-a' && session === 'session-a'
  ? { identity, read: () => state }
  : null)
const grants = Array.from({ length: 2 }, () => service.issue('remote-a', 'session-a', 20_000))
const listener = await listenFollowTls({ reader: service.reader, pfx: await readFile(process.argv[2]),
  passphrase: 'synthetic-only', signal: stop.signal })
const signingKey = createPrivateKey({ key: await readFile(process.argv[3]), format: 'der', type: 'pkcs8' })
const certificateSha256 = createHash('sha256').update(await readFile(process.argv[4])).digest('hex')
const deliveryKey = await readFile(process.argv[5])
const pairing = createFollowPairingController({ authority: service, signingKey, deliveryKey,
  route: { connectionId: 'host-a', localWorkspaceId: 'local-a', remoteWorkspaceId: 'remote-a',
    remoteSessionId: 'session-a', ip: '127.0.0.1', port: listener.port, certificateSha256 } })
deliveryKey.fill(0)
try {
  // Synthetic control channel. No grant or operator command is accepted by the TLS listener itself.
  process.stdout.write(`${listener.port} ${grants.map(grant => grant.token).join(' ')}\n`)
  process.stdin.setEncoding('ascii')
  let pending = ''
  for await (const chunk of process.stdin) {
    pending += chunk
    if (pending.length > 256) throw new Error('fixture control overflow')
    let lf
    while ((lf = pending.indexOf('\n')) >= 0) {
      const command = pending.slice(0, lf)
      pending = pending.slice(lf + 1)
      if (command.startsWith('pair ')) {
        const [, nonce, ...extra] = command.split(' ')
        if (extra.length) throw new Error('unexpected fixture pairing arguments')
        const envelope = pairing.issue(nonce)
        process.stdout.write(`${envelope === null ? 'denied' : envelope.toString('base64')}\n`)
        continue
      }
      if (command === 'update') state = { cursor: 9, active: true, running: true }
      else if (command === 'revoke') { grants[0].revoke(); pairing.revoke() }
      else if (command === 'stop') { pairing.close(); stop.abort() }
      else throw new Error('unexpected fixture command')
      process.stdout.write(`${command}\n`)
    }
  }
} finally {
  pairing.close()
  await listener.close()
  service.dispose()
}

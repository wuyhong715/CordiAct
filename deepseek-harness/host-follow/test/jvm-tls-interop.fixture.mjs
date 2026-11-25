import { createHash, createPrivateKey } from 'node:crypto'
import { once } from 'node:events'
import { readFile } from 'node:fs/promises'
import { connect, createServer } from 'node:net'
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
// Single-peer test relay: forwards encrypted bytes until a test freezes the Host direction.
// It never terminates TLS and can only reach this fixture's own loopback listener.
const relay = process.argv[6] === 'silent-relay' ? await listenRelay(listener.port) : null
const signingKey = createPrivateKey({ key: await readFile(process.argv[3]), format: 'der', type: 'pkcs8' })
const certificateSha256 = createHash('sha256').update(await readFile(process.argv[4])).digest('hex')
const deliveryKey = await readFile(process.argv[5])
const pairing = createFollowPairingController({ authority: service, signingKey, deliveryKey,
  route: { connectionId: 'host-a', localWorkspaceId: 'local-a', remoteWorkspaceId: 'remote-a',
    remoteSessionId: 'session-a', ip: '127.0.0.1', port: relay?.port ?? listener.port, certificateSha256 } })
deliveryKey.fill(0)
try {
  // Synthetic control channel. No grant or operator command is accepted by the TLS listener itself.
  process.stdout.write(`${relay?.port ?? listener.port} ${grants.map(grant => grant.token).join(' ')}\n`)
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
        const [, nonce, proof, ...extra] = command.split(' ')
        if (extra.length) throw new Error('unexpected fixture pairing arguments')
        // Test-only simulated consent; the data socket cannot perform either operation.
        const review = pairing.capture(nonce, proof)
        const envelope = pairing.redeem(review)
        process.stdout.write(`${envelope === null ? 'denied' : envelope.toString('base64')}\n`)
        continue
      }
      if (command === 'update') state = { cursor: 9, active: true, running: true }
      else if (command === 'revoke') { grants[0].revoke(); pairing.revoke() }
      else if (command === 'freeze' && relay) await relay.freeze(() => pairing.revoke())
      else if (command === 'client-closed' && relay) await relay.clientClosed()
      else if (command === 'stop') { pairing.close(); stop.abort() }
      else throw new Error('unexpected fixture command')
      process.stdout.write(`${command}\n`)
    }
  }
} finally {
  pairing.close()
  await relay?.close()
  await listener.close()
  service.dispose()
}

async function listenRelay(port) {
  let front, back, frontClosed, backClosed
  const server = createServer(socket => {
    if (front) return socket.destroy()
    front = socket
    back = connect({ host: '127.0.0.1', port })
    frontClosed = new Promise(resolve => front.once('close', resolve))
    backClosed = new Promise(resolve => back.once('close', resolve))
    front.on('error', () => front.destroy())
    back.on('error', () => back.destroy())
    front.once('close', () => back.destroy())
    front.pipe(back)
    back.pipe(front, { end: false })
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  return {
    port: server.address().port,
    async freeze(revoke) {
      if (!front || front.destroyed || !back || back.destroyed) throw new Error('Expected one live TLS relay')
      back.unpipe(front)
      back.resume() // Drop future server bytes, and never forward server EOF.
      revoke()
      await backClosed
      if (front.destroyed || front.readableEnded || front.writableEnded) throw new Error('Relay propagated the disconnect')
    },
    clientClosed() {
      if (!frontClosed) throw new Error('Expected a connected client')
      return frontClosed
    },
    close() {
      front?.destroy()
      back?.destroy()
      return new Promise(resolve => server.close(resolve))
    },
  }
}

import { readFile } from 'node:fs/promises'
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
try {
  // Synthetic control channel. No grant or operator command is accepted by the TLS listener itself.
  process.stdout.write(`${listener.port} ${grants.map(grant => grant.token).join(' ')}\n`)
  process.stdin.setEncoding('ascii')
  let pending = ''
  for await (const chunk of process.stdin) {
    pending += chunk
    if (pending.length > 128) throw new Error('fixture control overflow')
    let lf
    while ((lf = pending.indexOf('\n')) >= 0) {
      const command = pending.slice(0, lf)
      pending = pending.slice(lf + 1)
      if (command === 'update') state = { cursor: 9, active: true, running: true }
      else if (command === 'revoke') grants[0].revoke()
      else if (command === 'stop') stop.abort()
      else throw new Error('unexpected fixture command')
      process.stdout.write(`${command}\n`)
    }
  }
} finally {
  await listener.close()
  service.dispose()
}

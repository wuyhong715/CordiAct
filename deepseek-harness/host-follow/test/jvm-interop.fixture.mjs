import { createFollowService } from '../follow-service.mjs'
import { serveFollowPipe } from '../ipc-follow.mjs'

let state = { cursor: -1, active: false, running: false }
const identity = {}
const service = createFollowService((workspace, session) => workspace === 'remote-a' && session === 'session-a'
  ? { identity, read: () => state }
  : null)
const grant = service.issue('remote-a', 'session-a', 20_000)
const reader = { open(request) {
  const lease = service.reader.open(request)
  if (!lease) return null
  let reads = 0
  return { ...lease, next() {
    reads++
    if (reads === 2) state = { cursor: 7, active: true, running: true }
    if (reads === 3) grant.revoke()
    return lease.next()
  } }
} }
const pipe = serveFollowPipe({ reader, input: process.stdin, output: process.stdout })
// Dedicated test process: stderr is a synthetic grant channel, not a diagnostic sink.
process.stderr.write(`${grant.token}\n`)
await pipe.completion
service.dispose()

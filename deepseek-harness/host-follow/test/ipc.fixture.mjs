import { createFollowService } from '../follow-service.mjs'
import { serveFollowPipe } from '../ipc-follow.mjs'

let state = { cursor: -1, active: false, running: false }
const identity = {}
const service = createFollowService((workspace, session) => workspace === 'workspace-a' && session === 'session-a'
  ? { identity, read: () => ({ ...state, queue: ['synthetic-private'], pendingInput: ['synthetic-private'] }) }
  : null)
const ttl = process.argv[2] === 'expire' ? 1_000 : 30_000
const grant = service.issue('workspace-a', 'session-a', ttl)
const pipe = serveFollowPipe({ reader: service.reader, input: process.stdin, output: process.stdout })

// Private synthetic control channel, never part of the carrier or production profile.
process.on('message', command => {
  if (command === 'update') state = { cursor: state.cursor + 1, active: true, running: true }
  if (command === 'revoke') grant.revoke()
  process.send?.({ acknowledged: command })
})
process.on('disconnect', pipe.close)
process.send?.({ token: grant.token })
await pipe.completion
service.dispose()
if (process.connected) process.disconnect()

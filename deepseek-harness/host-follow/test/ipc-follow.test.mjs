import assert from 'node:assert/strict'
import { fork } from 'node:child_process'
import { once } from 'node:events'
import { PassThrough, Writable } from 'node:stream'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { serveFollowPipe } from '../ipc-follow.mjs'

const fixture = fileURLToPath(new URL('./ipc.fixture.mjs', import.meta.url))
const wire = (token, workspace = 'workspace-a', session = 'session-a', version = 1) => (
  `cordiact-follow/${version} ${workspace} ${session} ${token}\n`
)
const baseline = { delivery: 0, cursor: -1, active: false, running: false }
const update = { delivery: 1, cursor: 0, active: true, running: true }
const options = { timeout: 8_000 }

async function start(t, mode) {
  const child = fork(fixture, mode ? [mode] : [], { stdio: ['pipe', 'pipe', 'pipe', 'ipc'], execArgv: [], env: {} })
  const closed = once(child, 'close')
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL')
    await closed
  })
  const lines = []
  let tail = ''
  let stderr = ''
  let waiting
  child.stderr.setEncoding('utf8').on('data', data => { stderr += data })
  child.stdout.setEncoding('utf8').on('data', data => {
    const chunks = (tail + data).split('\n')
    tail = chunks.pop()
    lines.push(...chunks)
    waiting?.()
  })
  child.stdout.on('end', () => { waiting?.() })
  const [{ token }] = await once(child, 'message')
  const next = async () => {
    while (lines.length === 0 && !child.stdout.readableEnded) {
      await new Promise(resolve => { waiting = resolve })
      waiting = undefined
    }
    if (lines.length === 0) return null
    return JSON.parse(lines.shift())
  }
  const command = name => child.send(name)
  const exit = async () => {
    assert.deepEqual(await closed, [0, null], stderr)
    assert.equal(stderr, '')
    assert.equal(tail, '')
  }
  return { child, token, next, command, exit }
}

test('cross-process pipe sends three-field state and ends on revocation', options, async t => {
  const f = await start(t)
  const open = wire(f.token)
  f.child.stdin.write(open.slice(0, 17))
  f.child.stdin.write(open.slice(17))
  assert.deepEqual(await f.next(), baseline)
  f.command('update')
  assert.deepEqual(await f.next(), update)
  f.command('revoke')
  assert.equal(await f.next(), null)
  await f.exit()
  const restart = await start(t)
  restart.child.stdin.write(open)
  assert.equal(await restart.next(), null)
  await restart.exit()
})

test('cross-process grant expiry closes an otherwise idle reader', options, async t => {
  const f = await start(t, 'expire')
  f.child.stdin.write(wire(f.token))
  assert.deepEqual(await f.next(), baseline)
  assert.equal(await f.next(), null)
  await f.exit()
})

test('cross-process stdin half-close or later bytes terminate, never reauthorize', options, async t => {
  for (const end of [f => f.child.stdin.end(), f => f.child.stdin.write(wire(f.token))]) {
    const f = await start(t)
    f.child.stdin.write(wire(f.token))
    assert.deepEqual(await f.next(), baseline)
    end(f)
    assert.equal(await f.next(), null)
    await f.exit()
  }
})

test('cross-process denial is silent before the first state', options, async t => {
  const requests = [
    f => wire(f.token, 'workspace-b'),
    f => wire(f.token, 'workspace-a', 'session-b'),
    f => wire(f.token, 'workspace-a', 'session-a', 2),
    () => wire('owner-token'),
    () => 'session/prompt\n',
    () => '/api/remote.mux\n',
    () => Buffer.from([0xff, 10]),
    () => Buffer.alloc(385, 97),
    f => `${wire(f.token)}session/prompt\n`,
  ]
  for (const request of requests) {
    const f = await start(t)
    f.child.stdin.write(request(f))
    assert.equal(await f.next(), null)
    await f.exit()
  }
  const truncated = await start(t)
  truncated.child.stdin.end('cordiact-follow/1 workspace-a')
  assert.equal(await truncated.next(), null)
  await truncated.exit()
})

test('an incomplete request does not renew admission timeout', options, async t => {
  const f = await start(t)
  f.child.stdin.write('cordiact-follow/1 ')
  assert.equal(await f.next(), null)
  await f.exit()
})

test('write backpressure holds one state; revocation interrupts without flushing more', options, async () => {
  for (const highWaterMark of [1, 512]) {
    const input = new PassThrough()
    const leaseAbort = new AbortController()
    let pulls = 0
    let release
    const writes = []
    const output = new Writable({ highWaterMark, write(data, _, callback) {
      writes.push(String(data))
      release = callback
    } })
    const reader = { open: () => ({ signal: leaseAbort.signal, canDeliver: () => true,
      next: async () => { pulls++; return { done: false, value: baseline } }, close: () => leaseAbort.abort() }) }
    const lease = serveFollowPipe({ reader, input, output })
    input.write(wire('caf1_' + 'A'.repeat(43)))
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(pulls, 1)
    assert.equal(writes.length, 1)
    leaseAbort.abort()
    await lease.completion
    assert.equal(output.destroyed, true)
    release()
    await new Promise(resolve => setImmediate(resolve))
    assert.equal(pulls, 1)
    assert.equal(writes.length, 1)
  }
})

test('a sink that never completes its write is closed at the write timeout', options, async () => {
  const input = new PassThrough()
  let writes = 0
  let pulls = 0
  const output = new Writable({ write() { writes++ } })
  const leaseAbort = new AbortController()
  const reader = { open: () => ({ signal: leaseAbort.signal, canDeliver: () => true,
    next: async () => { pulls++; return { done: false, value: baseline } }, close: () => leaseAbort.abort() }) }
  const pipe = serveFollowPipe({ reader, input, output })
  input.write(wire('caf1_' + 'A'.repeat(43)))
  let watchdogFired = false
  const watchdog = setTimeout(() => { watchdogFired = true; pipe.close() }, 5_000)
  try {
    await pipe.completion
    assert.equal(watchdogFired, false)
    assert.equal(output.destroyed, true)
    assert.equal(leaseAbort.signal.aborted, true)
    assert.equal(writes, 1)
    assert.equal(pulls, 1)
  } finally {
    clearTimeout(watchdog)
  }
})

test('final grant check can refuse a returned frame before sink handoff', options, async () => {
  const input = new PassThrough()
  const output = new PassThrough()
  output.on('data', () => assert.fail('A retired frame reached the sink'))
  const leaseAbort = new AbortController()
  const reader = { open: () => ({ signal: leaseAbort.signal, canDeliver: () => false,
    next: async () => ({ done: false, value: baseline }), close: () => leaseAbort.abort() }) }
  const lease = serveFollowPipe({ reader, input, output })
  input.write(wire('caf1_' + 'A'.repeat(43)))
  await lease.completion
  assert.equal(leaseAbort.signal.aborted, true)
})

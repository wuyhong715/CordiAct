import assert from 'node:assert/strict'
import { once } from 'node:events'
import { PassThrough, Writable } from 'node:stream'
import test from 'node:test'
import { serveFollowPairingHandoff } from '../pairing-handoff.mjs'

const nonce = '01'.repeat(32)
const proof = 'ab'.repeat(32)
const line = `cordiact-follow-review/1 ${nonce} ${proof}\n`

function exercise(approve, signal, output = new PassThrough()) {
  const input = new PassThrough()
  const frames = []
  output.on('data', bytes => { frames.push(bytes) })
  const handoff = serveFollowPairingHandoff({ input, output, approve, signal })
  return { input, output, frames, ...handoff }
}

test('handoff waits for request EOF, emits one sealed frame, and clears the owned reply', async () => {
  let attempts = 0
  const sealed = Buffer.alloc(38, 0xa5)
  const expected = `cordiact-follow-envelope/1 ${sealed.toString('base64')}\n`
  const session = exercise(async (n, p, signal) => {
    attempts++
    assert.equal(n, nonce)
    assert.equal(p, proof)
    assert.equal(signal.aborted, false)
    return sealed
  })
  const closed = once(session.output, 'close')
  session.input.write(line.slice(0, 11))
  session.input.write(line.slice(11))
  assert.equal(attempts, 0)
  session.input.end()
  await Promise.all([session.completion, closed])
  assert.equal(attempts, 1)
  assert.equal(Buffer.concat(session.frames).toString(), expected)
  assert.deepEqual(sealed, Buffer.alloc(38))
  assert.equal(session.input.destroyed, true)
})

test('handoff denies extra bytes, noncanonical data, and premature close before approval', async () => {
  let attempts = 0
  const approve = () => { attempts++; return null }
  for (const bad of [line.slice(0, -1), line.replace('\n', '\r\n'), line.replace(proof, proof.toUpperCase()),
    line + line, line.replace('review/1', 'review/2'), line.replace('ab', 'á')]) {
    const session = exercise(approve)
    session.input.end(bad)
    await session.completion
    assert.deepEqual(session.frames, [])
  }
  const session = exercise(approve)
  session.input.write(line)
  session.input.destroy()
  await session.completion
  assert.deepEqual(session.frames, [])
  assert.equal(attempts, 0)
})

test('cancellation suppresses late approval, clears the late reply, and never retries', async () => {
  const controller = new AbortController()
  const waiting = Promise.withResolvers()
  const entered = Promise.withResolvers()
  const sealed = Buffer.alloc(38, 1)
  let attempts = 0
  const session = exercise((_n, _p, signal) => {
    attempts++
    entered.resolve(signal)
    return waiting.promise
  }, controller.signal)
  session.input.end(line)
  const signal = await entered.promise
  controller.abort()
  await session.completion
  assert.equal(signal.aborted, true)
  waiting.resolve(sealed)
  await waiting.promise
  assert.equal(attempts, 1)
  assert.deepEqual(session.frames, [])
  assert.deepEqual(sealed, Buffer.alloc(38))
})

test('denials and malformed approvals write nothing; a failed write is attempted once', async () => {
  for (const outcome of [null, Buffer.alloc(37, 1), Buffer.alloc(865, 1)]) {
    const session = exercise(() => outcome)
    session.input.end(line)
    await session.completion
    assert.deepEqual(session.frames, [])
    if (outcome) assert.ok(outcome.every(byte => byte === 0))
  }
  let writes = 0
  const output = new Writable({ write(_bytes, _encoding, done) { writes++; done(new Error('write failed')) } })
  const session = exercise(() => Buffer.alloc(38, 1), undefined, output)
  session.input.end(line)
  await session.completion
  assert.equal(writes, 1)
})

test('request deadline is absolute and EOF does not authorize after it', { timeout: 9_000 }, async () => {
  let attempts = 0
  const session = exercise(() => { attempts++; return null })
  session.input.write(line)
  // The handoff timer is intentionally unreferenced; retain the test until it retires the streams.
  const keepAlive = setTimeout(() => {}, 8_000)
  try {
    await session.completion
    assert.deepEqual(session.frames, [])
    assert.equal(session.input.destroyed, true)
    assert.equal(session.output.destroyed, true)
    assert.equal(attempts, 0)
  } finally { clearTimeout(keepAlive) }
})

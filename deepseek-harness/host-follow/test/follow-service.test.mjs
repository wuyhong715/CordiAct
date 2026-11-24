import assert from 'node:assert/strict'
import test from 'node:test'
import { setTimeout as delay } from 'node:timers/promises'
import { createFollowService } from '../follow-service.mjs'

const idle = { cursor: -1, active: false, running: false }
const running = { cursor: -1, active: true, running: true }
const lost = { done: true, reason: 'access-lost' }
const disconnected = { done: true, reason: 'disconnected' }
const scope = { version: 1, workspaceId: 'workspace-a', sessionId: 'session-a' }

function setup(t) {
  let state = idle
  let samples = 0
  let binds = 0
  const identity = {}
  const service = createFollowService(() => {
    binds++
    return { identity, read: () => { samples++; return state } }
  })
  t.after(service.dispose)
  const issue = (ttlMs = 10_000) => service.issue(scope.workspaceId, scope.sessionId, ttlMs)
  const open = (token, overrides = {}) => service.reader.open({ ...scope, token, ...overrides })
  return { service, issue, open, identity, samples: () => samples, binds: () => binds,
    update: value => { state = value } }
}

test('only exact one-use follow credentials can sample a named pair', async t => {
  const f = setup(t)
  const other = setup(t)
  assert.deepEqual(Object.keys(f.service.reader), ['open'])
  const { token } = f.issue()
  for (const request of [null, {}, { ...scope }, { ...scope, token: 'owner-token' },
    { ...scope, token: 'caf1_' + 'A'.repeat(43) }, { ...scope, token, version: 2 },
    { ...scope, token, workspaceId: 'workspace-b' }, { ...scope, token, sessionId: 'session-b' },
    { ...scope, token, activate: true }, { ...scope, token, method: 'session/prompt' }]) {
    assert.equal(f.service.reader.open(request), null)
  }
  assert.equal(other.open(token), null)
  assert.equal(other.binds(), 0)
  assert.equal(other.samples(), 0)
  assert.equal(f.binds(), 1)
  assert.equal(f.samples(), 1)
  const stream = f.open(token)
  assert.ok(stream)
  assert.equal(f.open(token), null)
  assert.deepEqual(await stream.next(), { done: false, value: idle })
  stream.close()
  assert.deepEqual(await stream.next(), disconnected)
  assert.equal(f.open(token), null)
})

test('only the three primitive fields travel, including same-cursor replacement and jumping cursor', async t => {
  const f = setup(t)
  const stream = f.open(f.issue().token)
  assert.deepEqual(await stream.next(), { done: false, value: idle })
  const sensitive = { ...running, queue: ['synthetic-secret'], pendingInput: ['approval'], cwd: '/private' }
  f.update(sensitive)
  const frame = await stream.next()
  assert.deepEqual(frame, { done: false, value: running })
  assert.equal(Object.isFrozen(frame.value), true)
  f.update({ ...running, cursor: 3 })
  f.update({ ...running, cursor: 8 })
  assert.deepEqual(await stream.next(), { done: false, value: { ...running, cursor: 8 } })
  f.update({ ...running, cursor: 7 })
  assert.deepEqual(await stream.next(), lost)
})

test('revocation closes an idle read, drops outstanding state and does not touch a replacement grant', async t => {
  const f = setup(t)
  const oldGrant = f.issue()
  const oldStream = f.open(oldGrant.token)
  await oldStream.next()
  const pending = oldStream.next()
  await assert.rejects(oldStream.next(), /already pending/)
  f.update(running)
  const newStream = f.open(f.issue().token)
  oldGrant.revoke()
  oldGrant.revoke()
  assert.equal(oldStream.signal.aborted, true)
  assert.deepEqual(await pending, lost)
  oldStream.close()
  assert.equal(f.open(oldGrant.token), null)
  assert.equal(newStream.signal.aborted, false)
  assert.deepEqual(await newStream.next(), { done: false, value: running })
  const scopeWait = newStream.next()
  f.service.retireSource({})
  assert.equal(newStream.signal.aborted, false)
  f.service.retireSource(f.identity)
  assert.deepEqual(await scopeWait, lost)
})

test('a blocked consumer detects a rollback behind its unread sample', { timeout: 2_000 }, async t => {
  const f = setup(t)
  const grant = f.issue()
  const stream = f.open(grant.token)
  await stream.next()
  f.update({ ...running, cursor: 4 })
  const before = f.samples()
  while (f.samples() === before) await delay(25)
  f.update(idle)
  assert.deepEqual(await stream.next(), lost)
  assert.equal(stream.signal.aborted, true)
})

test('invalid source values, unavailable source and thrown sampling errors fail closed', async t => {
  for (const bad of [null, { ...idle, cursor: -0 }, { ...idle, cursor: Number.MAX_SAFE_INTEGER + 1 },
    { ...running, active: false }, { ...idle, active: 'false' }, { get cursor() { throw Error('secret') } }]) {
    const f = setup(t)
    const stream = f.open(f.issue().token)
    f.update(bad)
    assert.deepEqual(await stream.next(), lost)
  }
  const service = createFollowService(() => { throw Error('private path') })
  t.after(service.dispose)
  assert.throws(() => service.issue(scope.workspaceId, scope.sessionId, 1_000), { message: 'Follow grant unavailable' })
})

test('expiry aborts an idle stream and invalidates unopened grants', { timeout: 2_000 }, async t => {
  const f = setup(t)
  const stream = f.open(f.issue(100).token)
  const unopened = f.issue(100)
  await stream.next()
  const pending = stream.next()
  const [result] = await Promise.all([pending, delay(125)])
  assert.equal(stream.signal.aborted, true)
  assert.deepEqual(result, lost)
  assert.equal(f.open(unopened.token), null)
})

test('deadline admission, grant capacity and service disposal are finite', async t => {
  const f = setup(t)
  for (const invalid of [0, 300_001, NaN, Infinity, 0.5]) assert.throws(() => f.issue(invalid))
  assert.throws(() => f.service.issue('../work', 'session-a', 5))
  assert.equal(f.binds(), 0)
  const grants = Array.from({ length: 16 }, () => f.issue())
  assert.throws(() => f.issue())
  grants[0].revoke()
  const lease = f.open(f.issue().token)
  await lease.next()
  const pending = lease.next()
  f.service.dispose()
  assert.equal(lease.signal.aborted, true)
  assert.deepEqual(await pending, disconnected)
  assert.equal(f.open(grants[1].token), null)
  assert.throws(() => f.issue())
})

import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'

export const name = 'cordiact-follow-profile-fixture'
export const inject = ['agents', 'sessions', 'workspaceRegistry', 'appExit', 'appReady', 'loader']

const idle = { cursor: -1, active: false, running: false }
const lost = { done: true, reason: 'access-lost' }

async function verify(ctx) {
  for (const service of ['webServer', 'connection', 'typertGateway', 'remoteAccess', 'sessionController',
    'sessionQuery', 'credentials', 'fileUploads', 'attachments', 'agentLoop', 'llm', 'tools', 'hmr']) {
    assert.equal(ctx.get(service), undefined, `unexpected service: ${service}`)
  }
  const a = resolve('synthetic-a')
  const b = resolve('synthetic-b')
  await Promise.all([mkdir(a), mkdir(b)])
  const wa = await ctx.workspaceRegistry.create(a)
  const wb = await ctx.workspaceRegistry.create(b)
  const one = ctx.sessions.prepare('session-one', { meta: { cwd: a } })
  const two = ctx.sessions.create('session-two', { meta: { cwd: a } })
  const three = ctx.sessions.create('session-three', { meta: { cwd: b } })
  const removeOne = ctx.effect(() => ctx.sessions.enter(one))
  ctx.sessions.announce(one)
  await wa.attachSession(one.id)
  await wa.attachSession(two.id)
  await wb.attachSession(three.id)

  const authority = ctx.get('cordiactFollowAuthority')
  const reader = ctx.get('cordiactFollowReader')
  assert.deepEqual(Object.keys(authority), ['issue'])
  assert.deepEqual(Object.keys(reader), ['open'])
  const open = (token, workspaceId = wa.id, sessionId = one.id, extra = {}) => (
    reader.open({ version: 1, workspaceId, sessionId, token, ...extra })
  )
  const issue = (workspaceId = wa.id, sessionId = one.id) => authority.issue(workspaceId, sessionId, 5_000)
  assert.throws(() => issue(wb.id, one.id))
  assert.throws(() => issue(wa.id, 'session-cold'))
  const grant = issue()
  assert.equal(open(grant.token, wb.id, one.id), null)
  assert.equal(open(grant.token, wa.id, two.id), null)
  assert.equal(open(grant.token, wb.id, three.id), null)
  assert.equal(open('synthetic-owner-token'), null)
  assert.equal(open(grant.token, wa.id, one.id, { version: 2 }), null)
  const stream = open(grant.token)
  assert.deepEqual(await stream.next(), { done: false, value: idle })

  // The registry tracks a real attachment; the fixture supplies the external run state.
  const agent = { id: one.id, session: one, ctx, status: 'idle' }
  const detach = ctx.effect(() => ctx.agents.enter(agent))
  await ctx.agents.announce(agent, 'startup')
  assert.deepEqual(await stream.next(), { done: false, value: { ...idle, active: true } })
  agent.status = 'running'
  assert.deepEqual(await stream.next(), { done: false, value: { ...idle, active: true, running: true } })
  one.append('turn/start', { turn: 1 })
  assert.deepEqual(await stream.next(), { done: false, value: { cursor: 0, active: true, running: true } })
  await detach()
  assert.deepEqual(await stream.next(), { done: false, value: { ...idle, cursor: 0 } })

  const unchanged = open(issue(wa.id, two.id).token, wa.id, two.id)
  await unchanged.next()
  const oldRead = stream.next()
  const unopened = issue()
  await wa.detachSession(one.id)
  await wa.attachSession(one.id)
  assert.equal(stream.signal.aborted, true)
  assert.deepEqual(await oldRead, lost)
  assert.equal(open(unopened.token), null)
  assert.equal(unchanged.signal.aborted, false)

  const archiveStream = open(issue().token)
  await archiveStream.next()
  const archiveRead = archiveStream.next()
  await ctx.workspaceRegistry.archiveSession(one.id)
  await ctx.workspaceRegistry.unarchiveSession(one.id)
  assert.deepEqual(await archiveRead, lost)

  const oldStream = open(issue().token)
  await oldStream.next()
  const closing = oldStream.next()
  await removeOne()
  const replacement = ctx.sessions.create(one.id, { meta: { cwd: a } })
  assert.deepEqual(await closing, lost)
  assert.notEqual(replacement, one)
  const newStream = open(issue().token)
  oldStream.close()
  assert.deepEqual(await newStream.next(), { done: false, value: idle })
  const unloadRead = newStream.next()
  const entry = [...ctx.loader.entries()].find(row => row.options.id === 'cordiact-readonly-follow')
  assert.ok(entry?.fiber)
  await entry.update({ disabled: true })
  assert.deepEqual(await unloadRead, { done: true, reason: 'disconnected' })
  assert.equal(unchanged.signal.aborted, true)
  assert.equal(ctx.get('cordiactFollowAuthority'), undefined)
  assert.equal(ctx.get('cordiactFollowReader'), undefined)
  assert.throws(() => issue())
  process.stdout.write('CORDIACT_FOLLOW_PROFILE_OK\n')
}

export function apply(ctx) {
  ctx.effect(() => ctx.appReady.onReady(() => {
    // Keep the test process alive while the production grant timers are unreferenced.
    const deadline = setTimeout(() => { throw new Error('Follow profile fixture timed out') }, 10_000)
    void verify(ctx).then(() => {
      clearTimeout(deadline)
      ctx.appExit(0)
    }, error => {
      clearTimeout(deadline)
      process.stderr.write(`${error.stack}\n`)
      ctx.appExit(1)
    })
  }), 'cordiact-follow-fixture:execute')
}

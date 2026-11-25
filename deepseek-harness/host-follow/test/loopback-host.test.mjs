import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash, createPublicKey, generateKeyPairSync, randomBytes, X509Certificate } from 'node:crypto'
import { once } from 'node:events'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { connect as dial } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { PassThrough } from 'node:stream'
import { connect } from 'node:tls'
import test from 'node:test'
import { startLoopbackFollowHost } from '../loopback-host.mjs'
import { apply as mountLoopback } from '../loopback-plugin.mjs'

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'cordiact-loopback-host-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const cert = join(root, 'cert.pem')
  const key = join(root, 'key.pem')
  execFileSync('openssl', ['req', '-new', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1',
    '-nodes', '-days', '1', '-subj', '/CN=Synthetic Follow', '-addext', 'subjectAltName=IP:127.0.0.1',
    '-keyout', key, '-out', cert], { cwd: root, timeout: 8_000, stdio: 'pipe' })
  const { privateKey: signingKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  return {
    identity: { certificate: readFileSync(cert), privateKey: readFileSync(key) }, signingKey,
    scope: { connectionId: 'host', localWorkspaceId: 'local', remoteWorkspaceId: 'work', remoteSessionId: 'session' },
    deliveryKey: randomBytes(32), authority: { issue: () => assert.fail('The data socket must never issue a grant') },
    reader: { open: () => null },
  }
}

test('rejects a mismatched leaf key and a pre-aborted assembly', { timeout: 10_000 }, async t => {
  const options = fixture(t)
  await assert.rejects(startLoopbackFollowHost({ ...options, identity: { ...options.identity, privateKey: options.signingKey } }), /does not match/)
  await assert.rejects(startLoopbackFollowHost({ ...options, signal: AbortSignal.abort() }), /stopped/)
})

test('the advertised pin matches the peer and stopping clears active and pending connections', { timeout: 10_000 }, async t => {
  const options = fixture(t)
  const source = new AbortController()
  const stop = new AbortController()
  let opens = 0
  let ready
  const opened = new Promise(resolve => { ready = resolve })
  options.reader = { open: () => {
    opens++
    ready()
    return { signal: source.signal, canDeliver: () => true, next: () => new Promise(resolve => {
      source.signal.addEventListener('abort', () => resolve({ done: true }), { once: true })
    }), close: () => source.abort() }
  } }
  const host = await startLoopbackFollowHost({ ...options, signal: stop.signal })
  t.after(() => host.close())
  const { ip, port, certificateSha256 } = host.endpoint
  assert.equal(ip, '127.0.0.1')
  const spki = createPublicKey(options.signingKey).export({ type: 'spki', format: 'der' })
  assert.equal(host.descriptor, `cordiact-follow-route/1 host local work session ${ip} ${port} ${certificateSha256} ${spki.toString('hex')}\n`)
  const socket = connect({ host: ip, port, ca: options.identity.certificate, ALPNProtocols: ['cordiact-follow/1'], minVersion: 'TLSv1.3' })
  t.after(() => socket.destroy())
  const socketClosed = once(socket, 'close')
  void socketClosed.catch(() => {})
  await once(socket, 'secureConnect')
  assert.equal(certificateSha256, createHash('sha256').update(new X509Certificate(options.identity.certificate).raw).digest('hex'))
  assert.equal(certificateSha256, createHash('sha256').update(socket.getPeerCertificate().raw).digest('hex'))
  socket.write(`cordiact-follow/1 work session caf1_${'A'.repeat(43)}\n`)
  await opened
  const pending = dial({ host: ip, port })
  t.after(() => pending.destroy())
  const pendingClosed = once(pending, 'close')
  void pendingClosed.catch(() => {})
  await once(pending, 'connect')
  stop.abort()
  await Promise.all([host.close(), socketClosed, pendingClosed])
  assert.equal(source.signal.aborted, true)
  assert.equal(opens, 1)
  assert.equal(await host.approve('01'.repeat(32)), null)
  const closedPeer = dial({ host: ip, port })
  t.after(() => closedPeer.destroy())
  const [error] = await once(closedPeer, 'error')
  assert.equal(error.code, 'ECONNREFUSED')
})

test('Host owns exactly one explicit handoff and closes it on revocation or teardown', async t => {
  const host = await startLoopbackFollowHost(fixture(t))
  t.after(() => host.close())
  const first = [new PassThrough(), new PassThrough()]
  const second = [new PassThrough(), new PassThrough()]
  t.after(() => { for (const stream of [...first, ...second]) stream.destroy() })
  const active = host.handoff(...first)
  assert.ok(active)
  assert.equal(host.handoff(...second), null)
  assert.ok(second.every(stream => !stream.destroyed))
  host.revoke()
  await active.completion
  assert.ok(first.every(stream => stream.destroyed))
  const following = host.handoff(...second)
  assert.ok(following)
  await host.close()
  await following.completion
  assert.ok(second.every(stream => stream.destroyed))
  assert.equal(host.handoff(...first), null)
})

// Drive the adapter's own effect cleanup deterministically; the built profile separately uses real Cordis.
function mount(options) {
  const services = new Map([['cordiactFollowReader', options.reader], ['cordiactFollowAuthority', options.authority]])
  const disposers = []
  mountLoopback({
    get: key => services.get(key),
    provide(key, value) {
      services.set(key, value)
      return () => services.delete(key)
    },
    effect(fn) { for (const dispose of fn()) disposers.push(dispose) },
  })
  return { service: services.get('cordiactFollowLoopback'), services,
    async dispose() { for (const dispose of disposers.reverse()) await dispose() } }
}

test('adapter disposal racing an unopened start rejects the handoff and retires stale service references', { timeout: 10_000 }, async t => {
  const options = fixture(t)
  const adapter = mount(options)
  const started = adapter.service.start(options)
  const rejected = assert.rejects(started, /stopped/)
  const disposing = adapter.dispose()
  await Promise.all([disposing, rejected])
  assert.equal(adapter.services.has('cordiactFollowLoopback'), false)
  assert.equal(await adapter.service.start(options), null)
})

test('a replaced dependency cannot be used through a captured start service', { timeout: 10_000 }, async t => {
  const options = fixture(t)
  const adapter = mount(options)
  adapter.services.set('cordiactFollowReader', { open: () => assert.fail('stale adapter cannot read') })
  try {
    assert.equal(await adapter.service.start(options), null)
  } finally { await adapter.dispose() }
})

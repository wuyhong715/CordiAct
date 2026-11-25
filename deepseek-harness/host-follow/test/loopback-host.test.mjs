import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash, generateKeyPairSync, randomBytes, X509Certificate } from 'node:crypto'
import { once } from 'node:events'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { connect as dial } from 'node:net'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { connect } from 'node:tls'
import test from 'node:test'
import { startLoopbackFollowHost } from '../loopback-host.mjs'

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

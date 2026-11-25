import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { once } from 'node:events'
import { mkdtempSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { performance } from 'node:perf_hooks'
import { setImmediate as yieldToIo } from 'node:timers/promises'
import { connect } from 'node:tls'
import test from 'node:test'
import { listenFollowTls } from '../tls-follow.mjs'

const LOOPBACK = '127.0.0.1'
const request = `cordiact-follow/1 work session caf1_${'A'.repeat(43)}\n`
// Finite synthetic updates only. No large frames or new production write options.
const MAX_PULLS = 250_000

async function withListener(t, reader) {
  const directory = mkdtempSync(join(tmpdir(), 'cordiact-follow-pressure-'))
  const key = join(directory, 'key.pem')
  const cert = join(directory, 'cert.pem')
  const pfx = join(directory, 'tls.p12')
  const passphrase = 'fixture-only'
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const openssl = (...args) => execFileSync('openssl', args, { cwd: directory, timeout: 8_000, stdio: 'pipe' })
  openssl('req', '-new', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
    '-days', '1', '-subj', '/CN=Follow Backpressure Fixture', '-addext', 'subjectAltName=IP:127.0.0.1', '-keyout', key, '-out', cert)
  openssl('pkcs12', '-export', '-inkey', key, '-in', cert, '-out', pfx, '-passout', `pass:${passphrase}`)
  const listener = await listenFollowTls({ reader, pfx: readFileSync(pfx), passphrase })
  t.after(() => listener.close())
  const socket = connect({ host: LOOPBACK, port: listener.port, ca: readFileSync(cert),
    minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3', ALPNProtocols: ['cordiact-follow/1'], highWaterMark: 1024 })
  t.after(() => socket.destroy())
  await once(socket, 'secureConnect')
  assert.equal(socket.alpnProtocol, 'cordiact-follow/1')
  return socket
}

test('a real TLS peer that stops reading stalls the pull loop and the Host retires it', { timeout: 20_000 }, async t => {
  const abort = new AbortController()
  let count = 0
  let lastPull
  let retired
  const done = new Promise(resolve => { retired = resolve })
  let hitCap = false
  const reader = { open: () => ({
    signal: abort.signal,
    canDeliver: () => !abort.signal.aborted,
    async next() {
      const cursor = count++
      if (cursor >= MAX_PULLS) {
        hitCap = true
        return { done: true }
      }
      // Keep timers and socket callbacks able to run even on an unusually fast loopback.
      if (cursor % 64 === 0) await yieldToIo()
      lastPull = performance.now()
      return { done: false, value: { cursor, active: true, running: false } }
    },
    close: () => {
      abort.abort()
      retired(performance.now())
    },
  }) }
  const socket = await withListener(t, reader)
  socket.pause()
  socket.write(request)
  const end = await done
  t.diagnostic(`Synthetic pulls: ${count}; retirement after last pull: ${Math.round(end - lastPull)} ms`)
  assert.equal(hitCap, false, 'The finite fixture must actually encounter TLS backpressure')
  assert.ok(count > 1 && count <= MAX_PULLS, 'The connection must have passed admission before stalling')
  assert.ok(end - lastPull >= 2_900, 'Must retire only after a transport write remains pending near the 3 s deadline')
  assert.ok(end - lastPull < 7_000, 'Must retire without the peer draining its kernel buffer')
  const pullsAtRetirement = count
  // Do not drain or replay the stream: queued bytes may have already reached the peer.
  socket.destroy()
  await yieldToIo()
  assert.equal(count, pullsAtRetirement)
})

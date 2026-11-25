import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import { createDecipheriv, createHash, generateKeyPairSync, randomBytes, verify, X509Certificate } from 'node:crypto'
import { once } from 'node:events'
import { connect } from 'node:tls'
import { finished } from 'node:stream/promises'
import { createInterface } from 'node:readline'
import { mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const expected = '0.2.3-alpha.1'
const profile = 'cordiact-follow-test'
const stage = process.env.CORDIACT_PINNED_DSH_TEST_ROOT
const built = process.env.CORDIACT_BUILT_DSH_TEST_ROOT
const loopbackTls = process.env.CORDIACT_BUILT_DSH_TEST_TLS === '1'
assert.ok(!(stage && built), 'Choose exactly one Host artifact mode')
assert.ok(!loopbackTls || built, 'Loopback TLS requires the built artifact mode')

test(`the pinned DSH CLI loads the synthetic ${loopbackTls ? 'loopback TLS' : 'no-listener'} profile`, {
  skip: !(stage || built) && 'Use run-pinned-profile.mjs or run-built-profile.mjs',
  timeout: 40_000,
}, async t => {
  const bin = built ? join(built, 'apps/cli/lib/bin.js') : realpathSync(join(stage, 'node_modules/@deepseek-ai/dsh/lib/bin.js'))
  const { name, version } = JSON.parse(readFileSync(join(dirname(bin), '../package.json'), 'utf8'))
  assert.equal(name, '@deepseek-ai/dsh')
  assert.equal(version, expected)
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'cordiact-follow-profile-')))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const home = join(root, 'dsh-home')
  const profileDir = join(home, 'profiles', profile)
  mkdirSync(profileDir, { recursive: true })
  writeFileSync(join(profileDir, 'package.json'), JSON.stringify({
    name: 'cordiact-follow-test-profile', private: true, dsh: { profile: { bundles: [] } },
  }))
  const overlay = fileURLToPath(new URL('./profile.fixture.yml', import.meta.url))
  const networkGuard = fileURLToPath(new URL(loopbackTls ? './allow-built-profile-loopback.mjs' : './deny-profile-sockets.mjs', import.meta.url))
  const nodeArgs = ['--import', networkGuard]
  if (!built) nodeArgs.push('--import', realpathSync(join(stage, 'cordiact-vendor/register-vendor.mjs')))
  const args = [...nodeArgs, bin, '--profile', profile, '--patch', overlay]
  const env = { PATH: dirname(process.execPath), HOME: root, DSH_HOME: home, DSH_TELEMETRY_DISABLED: '1' }
  const composed = execFileSync(process.execPath, [...args, '--dump-config'], {
    cwd: root, env, timeout: 20_000, maxBuffer: 128 * 1024, encoding: 'utf8',
  })
  assert.deepEqual([...composed.matchAll(/^- id: ([\w-]+)$/gm)].map(match => match[1]), [
    'session', 'session-projection', 'agent', 'storage', 'storage-json', 'storage-domain',
    'session-persistence', 'workspace', 'cordiact-readonly-follow', 'fixture',
  ])
  let credentials
  if (loopbackTls) {
    const key = join(root, 'fixture-key.pem')
    const crt = join(root, 'fixture-cert.pem')
    const pfx = join(root, 'fixture.p12')
    const openssl = (...args) => execFileSync('openssl', args, { cwd: root, timeout: 8_000, stdio: 'pipe' })
    openssl('req', '-new', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
      '-days', '1', '-subj', '/CN=Synthetic Follow Host', '-keyout', key, '-out', crt)
    openssl('pkcs12', '-export', '-inkey', key, '-in', crt, '-out', pfx, '-passout', 'pass:synthetic-only')
    const cert = readFileSync(crt)
    const pin = createHash('sha256').update(new X509Certificate(cert).raw).digest('hex')
    const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
    const delivery = randomBytes(32)
    const spare = randomBytes(32)
    const provision = (name, bytes) => {
      const path = join(root, name)
      writeFileSync(path, bytes, { mode: 0o600, flag: 'wx' })
      return path
    }
    env.CORDIACT_TEST_TLS_IDENTITY = pfx
    env.CORDIACT_TEST_PAIR_SIGNER = provision('fixture-pair.pem', privateKey.export({ type: 'pkcs8', format: 'pem' }))
    env.CORDIACT_TEST_PAIR_DELIVERY = provision('fixture-delivery.bin', delivery)
    env.CORDIACT_TEST_PAIR_SPARE = provision('fixture-spare.bin', spare)
    env.CORDIACT_TEST_PAIR_PIN = pin
    credentials = { cert, pin, publicKey, delivery, spare }
    t.after(() => { delivery.fill(0); spare.fill(0) })
  }
  const dataPipe = loopbackTls ? 'ignore' : 'pipe'
  const child = spawn(process.execPath, args, { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe', dataPipe, dataPipe, 'ipc'] })
  const closed = once(child, 'close')
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL')
    await closed
  })
  let stdout = ''
  let stderr = ''
  child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk })
  child.stderr.setEncoding('utf8').on('data', chunk => { stderr += chunk })
  const [grant] = await Promise.race([
    once(child, 'message'), closed.then(() => assert.fail(`Follow profile exited before grant: ${stderr}`)),
  ])
  if (loopbackTls) {
    try {
      await verifyTls(t, child, closed, credentials, grant)
    } catch (error) {
      if (stderr) t.diagnostic(stderr)
      throw error
    }
    assert.deepEqual(await closed, [0, null], stderr)
    assert.match(stdout, /^CORDIACT_FOLLOW_PROFILE_OK$/m)
    return
  }
  const { token, workspace, session } = grant
  const frames = createInterface({ input: child.stdio[4] })[Symbol.asyncIterator]()
  child.stdio[3].write(`cordiact-follow/1 ${workspace} ${session} ${token}\n`)
  const baseline = await frames.next()
  assert.equal(baseline.done, false)
  assert.deepEqual(JSON.parse(baseline.value), { delivery: 0, cursor: -1, active: false, running: false })
  child.send('update')
  const update = await frames.next()
  assert.equal(update.done, false)
  assert.deepEqual(JSON.parse(update.value), { delivery: 1, cursor: 0, active: false, running: false })
  child.send('revoke')
  assert.equal((await frames.next()).done, true)
  assert.deepEqual(await closed, [0, null], stderr)
  assert.match(stdout, /^CORDIACT_FOLLOW_PROFILE_OK$/m)
})

async function verifyTls(t, child, closed, credentials, scoped) {
  const { cert, pin, publicKey, delivery, spare } = credentials
  const { port, workspace, session, wrongWorkspace, wrongSession } = scoped
  assert.ok(Number.isInteger(port) && port > 0 && port <= 65535)
  const nonce = () => randomBytes(32).toString('hex')
  const primary = nonce()
  const unused = nonce()
  function exchange(message) {
    const answer = Promise.race([
      once(child, 'message'), closed.then(() => assert.fail('Synthetic Host stopped before replying')),
    ])
    void answer.catch(() => {})
    child.send(message)
    return answer.then(([reply]) => reply)
  }
  const { envelope, unusedEnvelope } = await exchange({ primary, unused })
  function checkOffer(encoded, key, challenge) {
    const sealed = Buffer.from(encoded, 'base64')
    const decipher = createDecipheriv('aes-256-gcm', key, sealed.subarray(0, 12))
    decipher.setAAD(Buffer.from(`cordiact-follow-seal/1 ${challenge}\n`, 'ascii'))
    decipher.setAuthTag(sealed.subarray(-16))
    const plaintext = Buffer.concat([decipher.update(sealed.subarray(12, -16)), decipher.final()])
    try {
      const split = plaintext[0] + 1
      const offer = plaintext.subarray(split)
      assert.equal(verify('sha256', offer, publicKey, plaintext.subarray(1, split)), true)
      const prefix = `cordiact-follow-pair/1 fixture-host fixture-local ${workspace} ${session} 127.0.0.1 ${port} ${pin} ${challenge} `
      assert.equal(offer.subarray(0, prefix.length).equals(Buffer.from(prefix, 'ascii')), true)
      const data = offer.subarray(prefix.length)
      assert.equal(data.length, 49)
      assert.ok(/^caf1_[A-Za-z0-9_-]{43}\n$/u.test(data.toString('latin1')), 'Expected a scoped synthetic grant')
      return data.subarray(0, -1).toString('ascii')
    } finally { plaintext.fill(0) }
  }
  assert.throws(() => checkOffer(envelope, spare, primary))
  const token = checkOffer(envelope, delivery, primary)
  const unusedToken = checkOffer(unusedEnvelope, spare, unused)
  async function open(line) {
    const socket = connect({ host: '127.0.0.1', port, ca: cert,
      minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3', ALPNProtocols: ['cordiact-follow/1'],
      checkServerIdentity: (_host, leaf) => createHash('sha256').update(leaf.raw).digest('hex') === pin
        ? undefined : new Error('Unexpected synthetic Host certificate'),
    })
    t.after(() => socket.destroy())
    const completion = finished(socket, { writable: false, cleanup: true })
    // Keep errors observable without letting an early socket failure become unhandled.
    void completion.catch(() => {})
    socket.setTimeout(2_500, () => socket.destroy(new Error('Synthetic TLS peer timed out')))
    await once(socket, 'secureConnect')
    assert.equal(socket.alpnProtocol, 'cordiact-follow/1')
    const frames = createInterface({ input: socket, crlfDelay: Infinity })[Symbol.asyncIterator]()
    socket.write(line)
    return { frames, completion }
  }
  const request = (work, id, credential) => `cordiact-follow/1 ${work} ${id} ${credential}\n`
  async function deny(line) {
    const { frames, completion } = await open(line)
    assert.equal((await frames.next()).done, true)
    await completion
  }
  await deny(request(wrongWorkspace, session, token))
  await deny(request(workspace, wrongSession, token))
  await deny(request(workspace, session, 'synthetic-owner-token'))
  await deny('POST /api HTTP/1.1\n')
  const { frames, completion } = await open(request(workspace, session, token))
  assert.deepEqual(JSON.parse((await frames.next()).value), { delivery: 0, cursor: -1, active: false, running: false })
  await deny(request(workspace, session, token))
  child.send('update')
  assert.deepEqual(JSON.parse((await frames.next()).value), { delivery: 1, cursor: 0, active: false, running: false })
  child.send('unload')
  assert.equal((await frames.next()).done, true)
  await completion
  const { status } = await exchange({ afterUnload: nonce() })
  assert.equal(status, 'unloaded')
  await deny(request(workspace, session, unusedToken))
  child.send('stop')
}

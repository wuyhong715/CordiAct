import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import { createDecipheriv, createHash, generateKeyPairSync, randomBytes, verify, X509Certificate } from 'node:crypto'
import { once } from 'node:events'
import { connect } from 'node:tls'
import { finished } from 'node:stream/promises'
import { createInterface } from 'node:readline'
import { chmodSync, mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { makeProof } from './challenge-proof.fixture.mjs'

const expected = '0.2.3-alpha.1'
const profile = 'cordiact-follow-test'
const stage = process.env.CORDIACT_PINNED_DSH_TEST_ROOT
const built = process.env.CORDIACT_BUILT_DSH_TEST_ROOT
const loopbackTls = process.env.CORDIACT_BUILT_DSH_TEST_TLS === '1'
const shellQuote = value => `'${value.replaceAll("'", "'\\''")}'`
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
  if (loopbackTls) args.push('--patch', fileURLToPath(new URL('./profile-loopback.fixture.yml', import.meta.url)))
  const env = { PATH: dirname(process.execPath), HOME: root, DSH_HOME: home, DSH_TELEMETRY_DISABLED: '1' }
  const composed = execFileSync(process.execPath, [...args, '--dump-config'], {
    cwd: root, env, timeout: 20_000, maxBuffer: 128 * 1024, encoding: 'utf8',
  })
  assert.deepEqual([...composed.matchAll(/^- id: ([\w-]+)$/gm)].map(match => match[1]), [
    'session', 'session-projection', 'agent', 'storage', 'storage-json', 'storage-domain',
    'session-persistence', 'workspace', 'cordiact-readonly-follow', 'fixture',
    ...(loopbackTls ? ['cordiact-follow-loopback'] : []),
  ])
  let credentials
  if (loopbackTls) {
    const openssl = (...args) => execFileSync('openssl', args, { cwd: root, timeout: 8_000, stdio: 'pipe' })
    const provision = (directory, name, bytes) => {
      const path = join(directory, name)
      writeFileSync(path, bytes, { mode: 0o600, flag: 'wx' })
      return path
    }
    const identity = () => {
      const directory = mkdtempSync(join(realpathSync(homedir()), '.cordiact-profile-identity-'))
      t.after(() => rmSync(directory, { recursive: true, force: true }))
      const key = join(directory, 'tls-key.pem')
      const crt = join(directory, 'tls-cert.pem')
      openssl('req', '-new', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:prime256v1', '-nodes',
        '-days', '1', '-subj', '/CN=Synthetic Follow Host', '-keyout', key, '-out', crt)
      chmodSync(crt, 0o600)
      chmodSync(key, 0o600)
      const cert = readFileSync(crt)
      const pin = createHash('sha256').update(new X509Certificate(cert).raw).digest('hex')
      const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
      const delivery = randomBytes(32)
      t.after(() => delivery.fill(0))
      const signer = provision(directory, 'pairing-key.pem', privateKey.export({ type: 'pkcs8', format: 'pem' }))
      provision(directory, 'delivery-key.bin', delivery)
      return { directory, signer, cert, pin, publicKey, delivery }
    }
    const first = identity()
    const rotated = identity()
    const spare = randomBytes(32)
    env.CORDIACT_TEST_IDENTITY_DIR = first.directory
    env.CORDIACT_TEST_ROTATED_IDENTITY_DIR = rotated.directory
    env.CORDIACT_TEST_PAIR_SIGNER = first.signer
    env.CORDIACT_TEST_PAIR_SPARE = provision(root, 'fixture-spare.bin', spare)
    credentials = { ...first, rotated, spare }
    t.after(() => spare.fill(0))
  }
  const dataPipe = loopbackTls ? 'ignore' : 'pipe'
  // script provides tty stdin/stderr while the subprocess keeps its separate inherited IPC.
  const command = ['exec', ...[process.execPath, ...args].map(shellQuote)].join(' ')
  const child = loopbackTls
    ? spawn('/usr/bin/script', ['-qefc', command, '/dev/null'], {
      cwd: root, env: { ...env, SHELL: '/bin/sh' }, stdio: ['pipe', 'pipe', 'pipe', 'pipe', 'pipe', 'ipc'],
    })
    : spawn(process.execPath, args, { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe', dataPipe, dataPipe, 'ipc'] })
  const closed = once(child, 'close')
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) child.kill(loopbackTls ? 'SIGTERM' : 'SIGKILL')
    await closed
  })
  let stdout = ''
  let stderr = ''
  const replies = []
  let promptTail = ''
  child.stdout.setEncoding('utf8').on('data', chunk => {
    stdout += chunk
    if (!loopbackTls) return
    promptTail += chunk
    // Respond once per complete prompt, on the PTY only. Test IPC never confirms consent.
    for (;;) {
      const lf = promptTail.indexOf('\n')
      if (lf < 0) break
      const row = promptTail.slice(0, lf)
      promptTail = promptTail.slice(lf + 1)
      const code = /3 秒内输入 approve ([0-9a-f]{8}) 并回车/.exec(row)?.[1]
      if (!code || replies.length >= 4) continue
      replies.push(code)
      child.stdin.write(`${replies.length === 1 ? 'deny' : `approve ${code}`}\n`)
    }
  })
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
    assert.match(stdout, /^CORDIACT_FOLLOW_PROFILE_OK\r?$/m)
    assert.equal(replies.length, 4)
    assert.ok(stdout.includes(`证书 SHA-256：${credentials.rotated.pin}`))
    assert.ok(stdout.includes(`证书 SHA-256：${credentials.pin}`))
    assert.ok(stdout.includes(`Host：127.0.0.1:${grant.port}`))
    assert.ok(stdout.includes(`远端工作区：${grant.workspace}`))
    assert.ok(stdout.includes(`远端会话：${grant.session}`))
    assert.ok(!/caf1_[A-Za-z0-9_-]{43}/u.test(stdout), 'TTY must not print a bearer')
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
  const { cert, pin, publicKey, delivery, spare, rotated } = credentials
  const { port, workspace, session, wrongWorkspace, wrongSession } = scoped
  assert.ok(Number.isInteger(port) && port > 0 && port <= 65535)
  const nonce = () => randomBytes(32).toString('hex')
  const denied = nonce()
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
  // The descriptor is public metadata, not bootstrap trust. Reconstruct its exact expected bytes
  // from an independent scope selection, leaf fingerprint and provisioned signer for each generation.
  function checkDescriptor(descriptor, selectedPort, selectedPin, signer) {
    assert.ok(typeof descriptor === 'string' && Buffer.byteLength(descriptor) <= 804)
    const route = { connectionId: 'fixture-host', localWorkspaceId: 'fixture-local', remoteWorkspaceId: workspace,
      remoteSessionId: session, ip: '127.0.0.1', port: selectedPort, certificateSha256: selectedPin }
    const spki = signer.export({ type: 'spki', format: 'der' }).toString('hex')
    const expected = `cordiact-follow-route/1 ${Object.values(route).join(' ')} ${spki}\n`
    assert.equal(descriptor === expected, true, 'Descriptor must contain only the independently expected public route')
    return route
  }
  const route = checkDescriptor(scoped.descriptor, port, pin, publicKey)
  const { envelope, unusedEnvelope } = await exchange({ denied, primary, unused,
    deniedProof: makeProof(delivery, route, denied), primaryProof: makeProof(delivery, route, primary),
    spareProof: makeProof(spare, route, unused) })
  function checkOffer(encoded, key, challenge, approvedRoute = route, signer = publicKey) {
    const sealed = Buffer.from(encoded, 'base64')
    const decipher = createDecipheriv('aes-256-gcm', key, sealed.subarray(0, 12))
    decipher.setAAD(Buffer.from(`cordiact-follow-seal/1 ${challenge}\n`, 'ascii'))
    decipher.setAuthTag(sealed.subarray(-16))
    const plaintext = Buffer.concat([decipher.update(sealed.subarray(12, -16)), decipher.final()])
    try {
      const split = plaintext[0] + 1
      const offer = plaintext.subarray(split)
      assert.equal(verify('sha256', offer, signer, plaintext.subarray(1, split)), true)
      const prefix = `cordiact-follow-pair/1 fixture-host fixture-local ${workspace} ${session} 127.0.0.1 ${approvedRoute.port} ${approvedRoute.certificateSha256} ${challenge} `
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
  async function open(line, endpoint = port, trust = { cert, pin }) {
    const socket = connect({ host: '127.0.0.1', port: endpoint, ca: trust.cert,
      minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3', ALPNProtocols: ['cordiact-follow/1'],
      checkServerIdentity: (_host, leaf) => createHash('sha256').update(leaf.raw).digest('hex') === trust.pin
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
  const retired = exchange('retire')
  assert.equal((await frames.next()).done, true)
  await completion
  assert.deepEqual(await retired, { status: 'retired' })
  await assert.rejects(open(request(workspace, session, unusedToken)), { code: 'ECONNREFUSED' })

  const { rotatedPort, descriptor } = await exchange('rotate')
  assert.ok(Number.isInteger(rotatedPort) && rotatedPort > 0 && rotatedPort <= 65535)
  const nextRoute = checkDescriptor(descriptor, rotatedPort, rotated.pin, rotated.publicKey)
  assert.notEqual(descriptor, scoped.descriptor)
  const nextNonce = nonce()
  assert.deepEqual(await exchange({ nextNonce, staleProof: makeProof(delivery, nextRoute, nextNonce) }), { status: 'handoff-ready' })
  const reply = createInterface({ input: child.stdio[4] })[Symbol.asyncIterator]()
  child.stdio[3].end(`cordiact-follow-review/1 ${nextNonce} ${makeProof(rotated.delivery, nextRoute, nextNonce)}\n`)
  const response = /^cordiact-follow-envelope\/1 ([A-Za-z0-9+/=]{52,1152})$/u.exec((await reply.next()).value)
  assert.ok(response, 'Expected one sealed reply on the handoff pipe')
  assert.equal((await reply.next()).done, true)
  const rotatedEnvelope = response[1]
  assert.throws(() => checkOffer(rotatedEnvelope, delivery, nextNonce, nextRoute, rotated.publicKey))
  assert.throws(() => checkOffer(rotatedEnvelope, rotated.delivery, nextNonce, nextRoute, publicKey))
  const nextToken = checkOffer(rotatedEnvelope, rotated.delivery, nextNonce, nextRoute, rotated.publicKey)
  // The new endpoint presents the new leaf, even when the caller still pins the old leaf.
  await assert.rejects(open(request(workspace, session, nextToken), rotatedPort, { cert: rotated.cert, pin }),
    /Unexpected synthetic Host certificate/)
  const stale = await open(request(workspace, session, unusedToken), rotatedPort, rotated)
  assert.equal((await stale.frames.next()).done, true)
  await stale.completion
  const current = await open(request(workspace, session, nextToken), rotatedPort, rotated)
  assert.deepEqual(JSON.parse((await current.frames.next()).value), { delivery: 0, cursor: 0, active: false, running: false })
  child.send('unload')
  assert.equal((await current.frames.next()).done, true)
  await current.completion
  const afterUnload = nonce()
  const { status } = await exchange({ afterUnload, lateProof: makeProof(rotated.delivery, nextRoute, afterUnload) })
  assert.equal(status, 'unloaded')
  await assert.rejects(open(request(workspace, session, nextToken), rotatedPort, rotated), { code: 'ECONNREFUSED' })
  child.send('stop')
}

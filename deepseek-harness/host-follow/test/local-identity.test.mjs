import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { generateKeyPairSync, randomBytes } from 'node:crypto'
import { chmodSync, existsSync, linkSync, mkdirSync, mkdtempSync, realpathSync, renameSync, rmSync, rmdirSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { withLocalFollowIdentity } from '../local-identity.mjs'

function fixture(t) {
  const root = mkdtempSync(join(realpathSync(homedir()), '.cordiact-identity-test-'))
  t.after(() => rmSync(root, { recursive: true, force: true }))
  const dir = join(root, 'identity')
  mkdirSync(dir, { mode: 0o700 })
  const pem = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey.export({ type: 'pkcs8', format: 'pem' })
  const delivery = randomBytes(32)
  const put = (name, data) => writeFileSync(join(dir, name), data, { flag: 'wx', mode: 0o600 })
  // TLS certificate validation is the listener's responsibility, covered by the built profile.
  put('tls-cert.pem', 'synthetic-leaf')
  put('tls-key.pem', pem)
  put('pairing-key.pem', pem)
  put('delivery-key.bin', delivery)
  t.after(() => delivery.fill(0))
  return { root, dir, delivery }
}

const reject = dir => assert.rejects(withLocalFollowIdentity(dir, undefined, () => assert.fail('unexpected identity handoff')), {
  message: 'Invalid private Follow identity directory',
})

test('loads explicit private material and clears buffer views after success or failure', async t => {
  const { dir, delivery } = fixture(t)
  let views
  const consume = material => {
    assert.equal(material.identity.privateKey.type, 'private')
    assert.equal(material.signingKey.type, 'private')
    assert.deepEqual(material.deliveryKey, delivery)
    views = [material.identity.certificate, material.deliveryKey]
  }
  chmodSync(join(dir, 'pairing-key.pem'), 0o400)
  assert.equal(await withLocalFollowIdentity(dir, undefined, material => { consume(material); return 'ok' }), 'ok')
  assert.ok(views.every(bytes => bytes.every(byte => byte === 0)))
  await assert.rejects(withLocalFollowIdentity(dir, undefined, material => {
    consume(material)
    throw new Error('startup failed')
  }), /startup failed/)
  assert.ok(views.every(bytes => bytes.every(byte => byte === 0)))
})

test('rejects noncanonical paths, symlink components and writable directories', async t => {
  const { root, dir } = fixture(t)
  const alias = join(root, 'alias')
  symlinkSync(dir, alias)
  for (const path of ['identity', `${dir}/`, `${root}/./identity`, alias]) await reject(path)
  const parentAlias = join(root, 'parent')
  symlinkSync(root, parentAlias)
  await reject(join(parentAlias, 'identity'))
  chmodSync(dir, 0o750)
  await reject(dir)
  chmodSync(dir, 0o700)
  chmodSync(root, 0o770)
  await reject(dir)
})

test('rejects exposed, oversized, linked and non-regular secret files without blocking', { timeout: 5_000 }, async t => {
  const { root, dir, delivery } = fixture(t)
  const key = join(dir, 'delivery-key.bin')
  chmodSync(key, 0o640)
  await reject(dir)
  chmodSync(key, 0o600)
  const duplicate = join(root, 'duplicate')
  linkSync(key, duplicate)
  await reject(dir)
  unlinkSync(key)
  symlinkSync(duplicate, key)
  await reject(dir)
  unlinkSync(key)
  writeFileSync(key, Buffer.concat([delivery, Buffer.of(0)]), { mode: 0o600, flag: 'wx' })
  await reject(dir)
  unlinkSync(key)
  mkdirSync(key, { mode: 0o700 })
  await reject(dir)
  const fifo = join(dir, 'fifo-check')
  execFileSync('mkfifo', [fifo], { timeout: 1_000 })
  // When the test environment supports a real FIFO, also exercise non-blocking open.
  if (existsSync(fifo)) {
    rmdirSync(key)
    renameSync(fifo, key)
    await reject(dir)
  } else t.diagnostic('FIFO creation is not implemented by this filesystem; tested a directory instead')
})

test('an aborted load does not hand off credentials', async t => {
  const { dir } = fixture(t)
  await assert.rejects(withLocalFollowIdentity(dir, AbortSignal.abort(), () => assert.fail('aborted identity handoff')), /stopped/)
  const abort = new AbortController()
  const start = withLocalFollowIdentity(dir, abort.signal, () => assert.fail('cancelled identity handoff'))
  abort.abort()
  await assert.rejects(start, /stopped/)
})

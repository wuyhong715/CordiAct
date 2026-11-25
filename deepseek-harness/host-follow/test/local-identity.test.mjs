import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { generateKeyPairSync, randomBytes } from 'node:crypto'
import { chmodSync, existsSync, linkSync, mkdirSync, mkdtempSync, realpathSync, renameSync, rmSync, rmdirSync, symlinkSync, unlinkSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { readPrivateFollowFile, withLocalFollowIdentity } from '../local-identity.mjs'

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

test('read snapshots tolerate private metadata drift but reject observable changes', async () => {
  const uid = process.geteuid()
  const initial = { isFile: () => true, uid: BigInt(uid), nlink: 1n, mode: 0o100600n,
    size: 32n, dev: 10n, ino: 7n, mtimeNs: 100n, ctimeNs: 200n }
  for (const scenario of [
    { name: 'ctime and private mode changed', attributes: { mode: 0o100400n, ctimeNs: 300n }, accepted: true },
    { name: 'permissions widened', attributes: { mode: 0o100640n }, afterLast: true },
    { name: 'new link', attributes: { nlink: 2n } },
    { name: 'changed owner', attributes: { uid: BigInt(uid) + 1n } },
    { name: 'changed inode', attributes: { ino: 8n } },
    { name: 'changed content time', attributes: { mtimeNs: 101n } },
    { name: 'same-size changed bytes with identical timestamps', changed: true },
    { name: 'trailing bytes', grown: true },
    { name: 'cancelled during read', cancelled: true },
  ]) {
    const buffers = new Set()
    const controller = new AbortController()
    let stats = 0
    let reads = 0
    const file = {
      async stat(options) {
        assert.deepEqual(options, { bigint: true })
        const index = stats++
        return { ...initial, ...(index >= (scenario.afterLast ? 2 : 1) ? scenario.attributes : {}) }
      },
      async read(buffer, offset, length, position) {
        buffers.add(buffer)
        if (position !== 0) return { bytesRead: 0 }
        const pass = reads++
        const count = scenario.grown ? 33 : 32
        assert.ok(count <= length)
        buffer.fill(scenario.changed && pass > 0 ? 2 : 1, offset, offset + count)
        if (scenario.cancelled) controller.abort()
        return { bytesRead: count }
      },
    }
    if (scenario.accepted) {
      const bytes = await readPrivateFollowFile(file, uid, 32, 32, controller.signal)
      assert.deepEqual(bytes, Buffer.alloc(32, 1), scenario.name)
      assert.equal(stats, 3)
      assert.equal(reads, 2)
      bytes.fill(0)
    } else {
      await assert.rejects(readPrivateFollowFile(file, uid, 32, 32, controller.signal),
        scenario.cancelled ? /stopped/ : /Invalid private Follow identity directory/, scenario.name)
    }
    assert.ok(buffers.size > 0)
    assert.ok([...buffers].every(buffer => buffer.every(byte => byte === 0)), scenario.name)
  }
})

test('an aborted load does not hand off credentials', async t => {
  const { dir } = fixture(t)
  await assert.rejects(withLocalFollowIdentity(dir, AbortSignal.abort(), () => assert.fail('aborted identity handoff')), /stopped/)
  const abort = new AbortController()
  const start = withLocalFollowIdentity(dir, abort.signal, () => assert.fail('cancelled identity handoff'))
  abort.abort()
  await assert.rejects(start, /stopped/)
})

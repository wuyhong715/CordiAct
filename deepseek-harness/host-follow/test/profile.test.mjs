import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import { once } from 'node:events'
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
assert.ok(!(stage && built), 'Choose exactly one Host artifact mode')

test('the pinned DSH CLI loads the synthetic no-listener profile', {
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
  const networkGuard = fileURLToPath(new URL('./deny-profile-sockets.mjs', import.meta.url))
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
  const child = spawn(process.execPath, args, { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe', 'pipe', 'pipe', 'ipc'] })
  const closed = once(child, 'close')
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL')
    await closed
  })
  let stdout = ''
  let stderr = ''
  child.stdout.setEncoding('utf8').on('data', chunk => { stdout += chunk })
  child.stderr.setEncoding('utf8').on('data', chunk => { stderr += chunk })
  const frames = createInterface({ input: child.stdio[4] })[Symbol.asyncIterator]()
  const [{ token, workspace, session }] = await Promise.race([
    once(child, 'message'), closed.then(() => assert.fail(`Follow profile exited before grant: ${stderr}`)),
  ])
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

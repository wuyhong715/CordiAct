import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, mkdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const expected = '0.2.3-alpha.1'
const profile = 'cordiact-follow-test'
const cli = process.env.CORDIACT_PINNED_DSH_BIN

test('the pinned DSH CLI loads the synthetic no-listener profile', { skip: !cli && 'Set CORDIACT_PINNED_DSH_BIN' }, t => {
  const bin = realpathSync(cli)
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
  const preload = process.env.CORDIACT_DSH_VENDOR_PRELOAD
  const nodeArgs = preload ? ['--import', realpathSync(preload)] : []
  const invoke = (...extra) => execFileSync(process.execPath, [...nodeArgs, bin, '--profile', profile, '--patch', overlay, ...extra], {
    cwd: root, env: { PATH: dirname(process.execPath), HOME: root, DSH_HOME: home, DSH_TELEMETRY_DISABLED: '1' },
    timeout: 20_000, maxBuffer: 128 * 1024, encoding: 'utf8',
  })
  const composed = invoke('--dump-config')
  assert.deepEqual([...composed.matchAll(/^- id: ([\w-]+)$/gm)].map(match => match[1]), [
    'session', 'session-projection', 'agent', 'storage', 'storage-json', 'storage-domain',
    'session-persistence', 'workspace', 'cordiact-readonly-follow', 'fixture',
  ])
  assert.match(invoke(), /^CORDIACT_FOLLOW_PROFILE_OK$/m)
})

// Verify the real CLI from a fixed, independently built DSH checkout; no module overrides.
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { lstatSync, readFileSync, realpathSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const revision = 'd98f5d68519215da48f3002da2cb9c9d2068d81d'
const [checkout, ...extra] = process.argv.slice(2)
const tls = extra.length === 1 && extra[0] === '--loopback-tls'
if (!checkout || (extra.length && !tls)) {
  console.error('Usage: node run-built-profile.mjs <fixed DSH checkout after build:artifacts> [--loopback-tls]')
  process.exit(2)
}

const root = realpathSync(checkout)
const git = (...args) => execFileSync('git', ['--no-replace-objects', '-C', root, ...args], {
  env: { PATH: process.env.PATH, GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1' },
  encoding: 'utf8', timeout: 15_000, maxBuffer: 64 * 1024,
}).trim()
assert.equal(git('rev-parse', '--show-toplevel'), root, 'Pass the root of the DSH checkout')
assert.equal(git('rev-parse', '--verify', 'HEAD'), revision, 'Use the exact fixed DSH commit')
assert.equal(git('status', '--porcelain=v1', '--untracked-files=normal'), '', 'Use a clean DSH checkout')
const manifest = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'))
assert.equal(manifest.packageManager, 'pnpm@11.28.5')
assert.equal(manifest.version, '0.2.3-alpha.1')

const bin = join(root, 'apps/cli/lib/bin.js')
const stat = lstatSync(bin, { throwIfNoEntry: false })
assert.ok(stat?.isFile() && realpathSync(bin) === bin, 'Build the CLI in this checkout first with pnpm run build:artifacts')
const record = JSON.parse(readFileSync(join(root, '.dsh-build/client-build-environment.json'), 'utf8'))
assert.equal(record.formatVersion, 1)
assert.equal(record.environment?.DSH_CLIENT_BUILD_PROFILE, 'official')
assert.equal(record.environment?.DSH_CLIENT_COMMIT_HASH, revision.slice(0, 7))
assert.equal(record.environment?.DSH_CLIENT_VERSION, manifest.version)
const sha256 = path => createHash('sha256').update(readFileSync(path)).digest('hex')
const cliSha256 = sha256(bin)
console.log(JSON.stringify({ mode: 'same-tree-built', revision, node: process.version, platform: process.platform,
  arch: process.arch, pnpmRequired: manifest.packageManager, lockSha256: sha256(join(root, 'pnpm-lock.yaml')), cliSha256 }))
execFileSync(process.execPath, ['--test', fileURLToPath(new URL('./profile.test.mjs', import.meta.url))], {
  cwd: root, env: { PATH: `${dirname(process.execPath)}:/usr/bin:/bin`, CORDIACT_BUILT_DSH_TEST_ROOT: root,
    ...(tls ? { CORDIACT_BUILT_DSH_TEST_TLS: '1' } : {}) },
  stdio: 'inherit', timeout: 65_000,
})
assert.equal(sha256(bin), cliSha256, 'The tested CLI entry changed during verification')

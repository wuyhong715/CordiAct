// Run the fixed-version CLI in an isolated, test-only artifact directory.
import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { closeSync, copyFileSync, mkdirSync, mkdtempSync, openSync, readFileSync, realpathSync, rmSync } from 'node:fs'
import { homedir, tmpdir } from 'node:os'
import { basename, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const revision = 'd98f5d68519215da48f3002da2cb9c9d2068d81d'
const here = fileURLToPath(new URL('.', import.meta.url))
const deps = join(here, 'pinned-dsh')
const vendor = ['cordis', 'cosmokit', 'schemastery', 'loader', 'include', 'group', 'logger-console', 'timer', 'hmr']
const node = process.execPath
const [repo, mode, ...extra] = process.argv.slice(2)
if (!repo || (mode !== undefined && mode !== '--offline') || extra.length) {
  console.error('Usage: node run-pinned-profile.mjs <DSH git checkout> [--offline]')
  process.exit(2)
}

function run(command, args, options) {
  const result = spawnSync(command, args, { stdio: 'inherit', ...options })
  if (result.error) throw result.error
  if (result.status !== 0) throw new Error(`${basename(command)} failed (${result.signal ?? result.status})`)
  return result.stdout
}

function validateLock() {
  const lock = JSON.parse(readFileSync(join(deps, 'package-lock.json'), 'utf8'))
  const manifest = JSON.parse(readFileSync(join(deps, 'package.json'), 'utf8'))
  assert.equal(lock.lockfileVersion, 3)
  assert.deepEqual(lock.packages[''].dependencies, manifest.dependencies)
  for (const [name, version] of Object.entries(manifest.dependencies)) {
    assert.equal(lock.packages[`node_modules/${name}`]?.version, version)
  }
  for (const [path, entry] of Object.entries(lock.packages)) {
    if (path === '') continue
    assert.match(path, /^node_modules\//)
    const url = new URL(entry.resolved)
    assert.equal(url.origin, 'https://registry.npmjs.org')
    assert.equal(url.username + url.password + url.search + url.hash, '')
    assert.match(entry.integrity, /^sha512-[A-Za-z0-9+/]{86}==$/)
    assert.equal(entry.link, undefined)
  }
}

const source = realpathSync(repo)
const pathEnv = { PATH: process.env.PATH }
const gitEnv = { ...pathEnv, GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1' }
const git = ['--no-replace-objects', '-C', source]
const resolved = run('git', [...git, 'rev-parse', '--verify', `${revision}^{commit}`], {
  stdio: ['ignore', 'pipe', 'inherit'], encoding: 'utf8', env: gitEnv, timeout: 10_000,
}).trim()
assert.equal(resolved, revision, 'The pinned DSH Git object is required')
validateLock()

const stage = mkdtempSync(join(realpathSync(tmpdir()), 'cordiact-pinned-profile-'))
try {
  const sourceDir = join(stage, 'upstream')
  const generated = join(stage, 'cordiact-vendor')
  mkdirSync(sourceDir)
  mkdirSync(generated)
  const archivePath = join(stage, 'vendor.tar')
  const archive = openSync(archivePath, 'wx', 0o600)
  try {
    run('git', [...git, 'archive', '--format=tar', revision, ...vendor.map(name => `vendor/${name}/src`)], {
      stdio: ['ignore', archive, 'inherit'], env: gitEnv, timeout: 30_000,
    })
  } finally {
    closeSync(archive)
  }
  run('tar', ['-xf', archivePath, '-C', sourceDir], { cwd: stage, env: pathEnv, timeout: 30_000 })

  for (const file of ['package.json', 'package-lock.json']) copyFileSync(join(deps, file), join(stage, file))
  // Never inherit npm config or run package lifecycle scripts. The cache is not an artifact input:
  // npm ci verifies downloads and cache hits against the committed lock.
  run('npm', ['ci', '--ignore-scripts', '--no-audit', '--no-fund', '--registry=https://registry.npmjs.org',
    '--userconfig', join(stage, 'npm-user-config'), '--globalconfig', join(stage, 'npm-global-config'),
    '--cache', join(homedir(), '.npm'), ...(mode ? ['--offline'] : [])], {
    cwd: stage, env: { ...pathEnv, HOME: stage }, timeout: 20 * 60_000,
  })
  const esbuild = join(stage, 'node_modules/esbuild/bin/esbuild')
  for (const name of vendor) {
    run(node, [esbuild, join(sourceDir, 'vendor', name, 'src/index.ts'), '--bundle', '--platform=node',
      '--target=node24', '--format=esm', '--packages=external', `--outfile=${join(generated, `${name}.mjs`)}`], {
      cwd: stage, env: { ...pathEnv, HOME: stage }, timeout: 30_000,
    })
  }
  copyFileSync(join(here, 'register-vendor.mjs'), join(generated, 'register-vendor.mjs'))
  console.log(`DSH profile verification: 0.2.3-alpha.1 registry artifacts + Cordis source ${revision} (test only)`)
  run(node, ['--test', join(here, 'profile.test.mjs')], {
    cwd: stage, env: { ...pathEnv, HOME: stage, CORDIACT_PINNED_DSH_TEST_ROOT: stage }, timeout: 60_000,
  })
} finally {
  rmSync(stage, { recursive: true, force: true })
}

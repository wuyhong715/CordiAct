import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const fixture = fileURLToPath(new URL('./operator-tty.fixture.mjs', import.meta.url))
const shellQuote = text => `'${text.replaceAll("'", "'\\''")}'`

test('non-interactive input cannot even render a review', { timeout: 5_000 }, async t => {
  const child = spawn(process.execPath, [fixture], { stdio: ['pipe', 'pipe', 'pipe'] })
  const closed = once(child, 'close')
  t.after(() => { if (child.exitCode === null) child.kill() })
  child.stdin.end('approve anything\n')
  let stdout = ''
  let stderr = ''
  child.stdout.setEncoding('utf8').on('data', data => { stdout += data })
  child.stderr.setEncoding('utf8').on('data', data => { stderr += data })
  assert.deepEqual(await closed, [0, null], stderr)
  assert.equal(stdout, 'RESULT=denied;ISSUES=0\n')
  assert.equal(stderr, '')
})

for (const mode of ['approve', 'wrong', 'timeout', 'abort', 'expired-at-submit']) {
  test(`PTY ${mode} issues only after the exact displayed response`, { timeout: 7_000 }, async t => {
    const command = `${shellQuote(process.execPath)} ${shellQuote(fixture)} ${shellQuote(mode)}`
    const child = spawn('script', ['-qefc', command, '/dev/null'], { stdio: ['pipe', 'pipe', 'pipe'] })
    const closed = once(child, 'close')
    t.after(() => { if (child.exitCode === null) child.kill() })
    let output = ''
    let sent = false
    child.stdout.setEncoding('utf8').on('data', data => {
      output += data
      const code = /approve ([0-9a-f]{8})/.exec(output)?.[1]
      if (!sent && code && (mode === 'approve' || mode === 'wrong' || mode === 'expired-at-submit')) {
        sent = true
        child.stdin.write(`${mode === 'wrong' ? 'yes' : `approve ${code}`}\n`)
      }
    })
    let stderr = ''
    child.stderr.setEncoding('utf8').on('data', data => { stderr += data })
    assert.deepEqual(await closed, [0, null], stderr)
    assert.match(output, /fixture-local/)
    assert.match(output, /remote-w/)
    assert.match(output, /remote-s/)
    assert.match(output, /127\.0\.0\.1:12345/)
    assert.doesNotMatch(output, /caf1_/)
    assert.match(output, mode === 'approve' ? /RESULT=approved;ISSUES=1/ : /RESULT=denied;ISSUES=0/)
  })
}

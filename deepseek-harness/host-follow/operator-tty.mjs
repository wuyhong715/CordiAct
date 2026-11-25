import { randomBytes } from 'node:crypto'
import { isatty } from 'node:tty'

const WAIT_MS = 3_000
const MAX_REPLY = 64
let busy = false

/**
 * Opt-in local Host adapter. Call only from a trusted process that owns stdin and stderr;
 * terminal access is not operator authentication (a PTY holder can answer). No secrets are printed.
 * Never expose this operation on a remotely driven data socket or reuse automation stdin.
 */
export async function approveFollowOnTerminal({ controller, nonce, proof, signal }) {
  if (busy || signal?.aborted || !isatty(0) || !isatty(2)) return null
  const input = process.stdin
  const output = process.stderr
  if (input.isRaw || input.readableFlowing === true || input.readableLength !== 0 || input.listenerCount('data') ||
    input.listenerCount('readable') || input.destroyed || input.readableEnded || output.destroyed) return null
  busy = true
  let review
  try {
    review = controller.capture(nonce, proof)
    if (!review || signal?.aborted) return null
    const code = randomBytes(4).toString('hex')
    const route = review.route
    const prompt = `\nCordiAct 只读跟随：${route.connectionId}\n` +
      `本地工作区：${route.localWorkspaceId}\nHost：${route.ip}:${route.port}\n证书 SHA-256：${route.certificateSha256}\n` +
      `远端工作区：${route.remoteWorkspaceId}\n远端会话：${route.remoteSessionId}\n挑战：${review.nonce}\n` +
      `允许读取短时状态；会替换此前授权。3 秒内输入 approve ${code} 并回车；其他输入拒绝。\n`
    if (!await answer(input, output, prompt, `approve ${code}`, signal) || signal?.aborted) return null
    return controller.redeem(review)
  } finally {
    try { if (review) controller.dismiss(review) }
    finally { busy = false }
  }
}

function answer(input, output, prompt, expected, signal) {
  return new Promise(resolve => {
    const buffer = Buffer.alloc(MAX_REPLY)
    let size = 0
    let done = false
    const timer = setTimeout(deny, WAIT_MS)
    function finish(ok) {
      if (done) return
      done = true
      clearTimeout(timer)
      input.pause()
      input.off('data', read)
      input.off('end', deny)
      input.off('close', deny)
      input.off('error', deny)
      output.off('error', deny)
      output.off('close', deny)
      signal?.removeEventListener('abort', deny)
      buffer.fill(0)
      resolve(ok)
    }
    function deny() { finish(false) }
    function read(chunk) {
      if (!Buffer.isBuffer(chunk) || chunk.length > MAX_REPLY - size) return deny()
      const lf = chunk.indexOf(10)
      if (lf >= 0 && lf !== chunk.length - 1) return deny()
      chunk.copy(buffer, size)
      size += chunk.length
      if (lf < 0) return
      const length = buffer[size - 2] === 13 ? size - 2 : size - 1
      finish(buffer.subarray(0, length).toString('latin1') === expected)
    }
    input.once('end', deny)
    input.once('close', deny)
    input.once('error', deny)
    output.once('error', deny)
    output.once('close', deny)
    signal?.addEventListener('abort', deny, { once: true })
    if (signal?.aborted) return deny()
    try {
      // Do not start consuming input until the prompt is delivered or the deadline has ended.
      output.write(prompt, error => {
        if (error) return deny()
        if (!done) input.on('data', read).resume()
      })
    } catch { deny() }
  })
}

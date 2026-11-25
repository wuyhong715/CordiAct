import { performance } from 'node:perf_hooks'

const REQUEST = /^cordiact-follow-review\/1 ([0-9a-f]{64}) ([0-9a-f]{64})\n(?![\s\S])/u
const REQUEST_BYTES = Buffer.byteLength('cordiact-follow-review/1 ') + 64 + 1 + 64 + 1
const REPLY_BYTES = 864
const WAIT_MS = 7_000

/**
 * Explicit, one-shot control-plane handoff over two exclusively owned byte streams.
 * The trusted launcher owns peer admission. This does not listen or authenticate the transport.
 * approve(nonce, proof, signal, canApprove) must verify proof and consent, and check
 * canApprove synchronously before issuing a grant. It transfers ownership of its reply buffer.
 */
export function serveFollowPairingHandoff({ input, output, approve, signal }) {
  if (input === output) throw new TypeError('Pairing requires two separate streams')
  const run = new AbortController()
  const request = Buffer.alloc(REQUEST_BYTES)
  const deadline = performance.now() + WAIT_MS
  let length = 0
  let ended = false
  let closed = false
  const completion = Promise.withResolvers()
  const timer = setTimeout(close, WAIT_MS)
  timer.unref()

  function close() {
    if (closed) return
    closed = true
    clearTimeout(timer)
    signal?.removeEventListener('abort', close)
    request.fill(0)
    run.abort()
    input.off('data', receive)
    input.destroy()
    output.destroy()
    completion.resolve()
  }

  const canApprove = () => !closed && performance.now() < deadline

  function receive(chunk) {
    if (closed) return
    if (ended || performance.now() >= deadline || !Buffer.isBuffer(chunk) || chunk.length > request.length - length) return close()
    chunk.copy(request, length)
    length += chunk.length
  }

  async function finishRequest() {
    if (closed || ended) return
    ended = true
    const match = performance.now() < deadline && length === request.length && REQUEST.exec(request.toString('latin1'))
    request.fill(0)
    if (!match) return close()
    let sealed
    try {
      sealed = await approve(match[1], match[2], run.signal, canApprove)
      if (closed || performance.now() >= deadline || !Buffer.isBuffer(sealed) || sealed.length < 38 || sealed.length > REPLY_BYTES) return close()
      // One attempt. A failed/unknown write never replays and cannot retract accepted bytes.
      output.end(`cordiact-follow-envelope/1 ${sealed.toString('base64')}\n`, close)
    } catch {
      close()
    } finally {
      if (Buffer.isBuffer(sealed)) sealed.fill(0)
    }
  }

  input.once('end', finishRequest)
  input.once('close', () => { if (!ended) close() })
  input.on('error', close)
  output.once('close', close)
  output.on('error', close)
  if (signal?.aborted || input.destroyed || input.readableEnded || output.destroyed || output.writableEnded) close()
  else {
    signal?.addEventListener('abort', close, { once: true })
    input.on('data', receive)
  }
  return Object.freeze({ completion: completion.promise, close })
}

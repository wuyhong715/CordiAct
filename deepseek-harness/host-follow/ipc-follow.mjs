import { Buffer } from 'node:buffer'
import { performance } from 'node:perf_hooks'

const MAX_REQUEST_BYTES = 384
const OPEN_MS = 3_000
const WRITE_MS = 3_000
const MAX_STREAM_MS = 5 * 60_000
const OPEN = /^cordiact-follow\/1 ([A-Za-z0-9][A-Za-z0-9._:-]{0,127}) ([A-Za-z0-9][A-Za-z0-9._:-]{0,127}) (caf1_[A-Za-z0-9_-]{43})\n$/u

/**
 * Serve one grant over an exclusively owned pair of byte streams (e.g. child pipes).
 * Only the reader crosses this interface. There is no listener, retry, dispatcher or
 * authority to issue credentials. Input must remain open and silent after one request.
 * Completion closes both directions; bytes already handed to the OS cannot be recalled.
 */
export function serveFollowPipe({ reader, input, output, signal }) {
  const controller = new AbortController()
  const done = controller.signal
  const request = Buffer.alloc(MAX_REQUEST_BYTES)
  let size = 0
  let lease
  let opened = false
  let writeTimer
  let timer
  let deadline
  let complete
  const completion = new Promise(resolve => { complete = resolve })

  function close() {
    if (done.aborted) return
    controller.abort()
    clearTimeout(timer)
    clearTimeout(writeTimer)
    signal?.removeEventListener('abort', close)
    lease?.signal.removeEventListener('abort', close)
    lease?.close()
    lease = undefined
    request.fill(0)
    input.off('data', receive)
    input.destroy()
    output.destroy()
    complete()
  }

  function resetDeadline(delay) {
    clearTimeout(timer)
    deadline = performance.now() + delay
    timer = setTimeout(close, delay)
    timer.unref()
  }

  function receive(chunk) {
    if (done.aborted) return
    if (opened || performance.now() >= deadline || !Buffer.isBuffer(chunk)
      || chunk.length > request.length - size) return close()
    const lf = chunk.indexOf(10)
    if (lf !== -1 && lf !== chunk.length - 1) return close()
    chunk.copy(request, size)
    size += chunk.length
    if (lf === -1) return
    const raw = request.subarray(0, size)
    if (raw.some(byte => byte > 127)) return close()
    const match = OPEN.exec(raw.toString('latin1'))
    request.fill(0)
    if (match === null) return close()
    opened = true
    try {
      const candidate = reader.open({ version: 1, workspaceId: match[1], sessionId: match[2], token: match[3] })
      if (done.aborted) {
        candidate?.close()
        return
      }
      lease = candidate
    } catch {
      close()
      return
    }
    if (!lease || lease.signal.aborted) return close()
    lease.signal.addEventListener('abort', close, { once: true })
    resetDeadline(MAX_STREAM_MS)
    void pump()
  }

  function writeFrame(frame) {
    return new Promise(resolve => {
      const settle = () => {
        done.removeEventListener('abort', settle)
        clearTimeout(writeTimer)
        resolve()
      }
      done.addEventListener('abort', settle, { once: true })
      writeTimer = setTimeout(close, WRITE_MS)
      writeTimer.unref()
      try {
        output.write(frame, error => {
          if (error) close()
          settle()
        })
      } catch {
        close()
      }
    })
  }

  async function pump() {
    try {
      for (let delivery = 0; !done.aborted; delivery++) {
        const result = await lease.next()
        if (done.aborted || lease.signal.aborted || result.done) break
        const { cursor, active, running } = result.value
        if (!Number.isSafeInteger(delivery) || !Number.isSafeInteger(cursor) || cursor < -1 || Object.is(cursor, -0)
          || typeof active !== 'boolean' || typeof running !== 'boolean' || (running && !active)) break
        const frame = `${JSON.stringify({ delivery, cursor, active, running })}\n`
        // No await between the final cancellation check and handing this one frame to the sink.
        if (done.aborted || performance.now() >= deadline || !lease.canDeliver()) break
        await writeFrame(frame)
      }
    } catch {
      // Transport and source errors intentionally have no wire payload.
    } finally {
      close()
    }
  }

  input.once('end', close)
  input.once('close', close)
  input.on('error', close)
  output.once('close', close)
  output.on('error', close)
  // A write-only output is required; a finished or half-retired endpoint cannot acquire a grant.
  output.once('finish', close)
  if (signal?.aborted || input.destroyed || input.readableEnded || output.destroyed || output.writableEnded) {
    close()
  } else {
    signal?.addEventListener('abort', close, { once: true })
    resetDeadline(OPEN_MS)
    input.on('data', receive)
  }
  return Object.freeze({ close, completion })
}

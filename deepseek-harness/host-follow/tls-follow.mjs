import { Buffer } from 'node:buffer'
import { performance } from 'node:perf_hooks'
import { createServer } from 'node:tls'
import { serveFollowPipe } from './ipc-follow.mjs'

const PROTOCOL = 'cordiact-follow/1'
const LOOPBACK = '127.0.0.1'
const MAX_CONNECTIONS = 16
const HANDSHAKE_MS = 3_000

/**
 * Opt-in loopback-only prototype. This accepts only the projected reader and TLS identity,
 * not the authority or any DSH router. The caller owns the identity and grant distribution.
 * A supplied abort signal retires the listener and all admitted connections.
 */
export async function listenFollowTls({ reader, pfx, passphrase, signal }) {
  if (signal?.aborted) throw new Error('Follow listener stopped')
  if (!Buffer.isBuffer(pfx) || pfx.length === 0 || typeof passphrase !== 'string' || passphrase.length === 0) {
    throw new TypeError('An explicit Follow TLS identity is required')
  }
  const sockets = new Set()
  const channels = new Set()
  const pending = new Map()
  let stopped = false
  let stopping
  const server = createServer({
    pfx, passphrase,
    minVersion: 'TLSv1.3', maxVersion: 'TLSv1.3',
    ALPNProtocols: [PROTOCOL], handshakeTimeout: HANDSHAKE_MS,
  }, socket => {
    const entry = pending.get(socket.remotePort)
    if (stopped || !entry || performance.now() >= entry.deadline || socket.alpnProtocol !== PROTOCOL) {
      socket.destroy()
      return
    }
    pending.delete(socket.remotePort)
    clearTimeout(entry.timer)
    const pipe = serveFollowPipe({ reader, input: socket, output: socket })
    channels.add(pipe)
    void pipe.completion.then(() => channels.delete(pipe))
  })
  server.maxConnections = MAX_CONNECTIONS
  server.prependListener('connection', socket => {
    if (stopped) return socket.destroy()
    sockets.add(socket)
    const port = socket.remotePort
    const entry = { deadline: performance.now() + HANDSHAKE_MS, timer: setTimeout(() => socket.destroy(), HANDSHAKE_MS) }
    entry.timer.unref()
    pending.set(port, entry)
    socket.once('close', () => {
      sockets.delete(socket)
      clearTimeout(entry.timer)
      if (pending.get(port) === entry) pending.delete(port)
    })
  })
  // Node delivers pre-handshake errors here, before the application stream has any listeners.
  server.on('tlsClientError', (_error, socket) => socket.destroy())

  function close() {
    if (stopping) return stopping
    stopped = true
    signal?.removeEventListener('abort', abort)
    stopping = new Promise(resolve => server.close(() => resolve()))
    for (const pipe of channels) pipe.close()
    for (const socket of sockets) socket.destroy()
    return stopping
  }
  function abort() { void close() }

  try {
    await new Promise((resolve, reject) => {
      server.once('error', reject)
      server.listen({ host: LOOPBACK, port: 0, backlog: MAX_CONNECTIONS }, () => {
        server.off('error', reject)
        server.on('error', abort)
        resolve()
      })
    })
    if (signal?.aborted) throw new Error('Follow listener stopped')
    signal?.addEventListener('abort', abort, { once: true })
    const { port } = server.address()
    return Object.freeze({ port, close })
  } catch (error) {
    await close()
    throw error
  }
}

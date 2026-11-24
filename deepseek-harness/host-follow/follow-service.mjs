import { createHash, randomBytes } from 'node:crypto'
import { performance } from 'node:perf_hooks'

const VERSION = 1
const MAX_GRANTS = 16
const MAX_TTL_MS = 5 * 60_000
const POLL_MS = 100
const ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/u
const TOKEN = /^caf1_[A-Za-z0-9_-]{43}$/u
const validId = value => typeof value === 'string' && ID.test(value)
const digest = token => createHash('sha256').update(token).digest('hex')
const denied = () => new Error('Follow grant unavailable')

function project(read) {
  try {
    const { cursor, active, running } = read() ?? {}
    if (!Number.isSafeInteger(cursor) || cursor < -1 || Object.is(cursor, -0)
        || typeof active !== 'boolean' || typeof running !== 'boolean' || (running && !active)) return null
    return Object.freeze({ cursor, active, running })
  } catch {
    // Source failures are not credential or protocol responses.
    return null
  }
}

function sameState(left, right) {
  return left?.cursor === right.cursor && left.active === right.active && left.running === right.running
}

/**
 * In-process follow authority. A trusted source returns an exact identity and
 * synchronous sampler, or null when the target cannot be bound. Null samples end access.
 * The reader cannot mint grants or dispatch any other command.
 */
export function createFollowService(bind) {
  const grants = new Map()
  let disposed = false

  function issue(workspaceId, sessionId, ttlMs) {
    if (disposed || grants.size >= MAX_GRANTS || !validId(workspaceId) || !validId(sessionId)
        || !Number.isSafeInteger(ttlMs) || ttlMs < 1 || ttlMs > MAX_TTL_MS) throw denied()
    let source
    try {
      source = bind(workspaceId, sessionId)
    } catch {
      throw denied()
    }
    if (source == null || project(source.read) === null || disposed || grants.size >= MAX_GRANTS) throw denied()
    const { identity } = source
    let read = source.read
    const token = `caf1_${randomBytes(32).toString('base64url')}`
    const key = digest(token)
    if (grants.has(key)) throw denied()
    const endAt = performance.now() + ttlMs
    const cancellation = new AbortController()
    let ended = null
    let opened = false
    let polling
    let latest = null
    let delivered = null
    let waiting = null
    let reading = false

    function signalChange() {
      waiting?.()
      waiting = null
    }

    function end(reason) {
      if (ended !== null) return
      ended = Object.freeze({ done: true, reason })
      grants.delete(key)
      clearTimeout(expiration)
      clearInterval(polling)
      latest = null
      delivered = null
      read = null
      cancellation.abort()
      signalChange()
    }

    function checkTime() {
      if (ended === null && performance.now() >= endAt) end('access-lost')
      return ended === null
    }

    function refresh() {
      if (!checkTime()) return
      const state = project(read)
      if (!checkTime()) return
      if (state === null || (delivered !== null && state.cursor < delivered.cursor)
          || (latest !== null && state.cursor < latest.cursor)) {
        end('access-lost')
        return
      }
      latest = sameState(delivered, state) ? null : state
      if (latest !== null) signalChange()
    }

    async function next() {
      if (reading) throw new Error('Follow read already pending')
      reading = true
      try {
        for (;;) {
          refresh()
          if (ended !== null) return ended
          if (latest !== null) {
            const value = latest
            delivered = value
            latest = null
            return Object.freeze({ done: false, value })
          }
          await new Promise(resolve => { waiting = resolve })
        }
      } finally {
        reading = false
      }
    }

    function open(requestedWorkspace, requestedSession) {
      if (!checkTime() || opened || requestedWorkspace !== workspaceId || requestedSession !== sessionId) return null
      refresh()
      if (ended !== null) return null
      opened = true
      polling = setInterval(refresh, POLL_MS)
      polling.unref()
      // A carrier must check the monotonic lease immediately before handing off bytes.
      return Object.freeze({ signal: cancellation.signal, next, canDeliver: checkTime, close: () => end('disconnected') })
    }

    const expiration = setTimeout(() => end('access-lost'), ttlMs)
    expiration.unref()
    grants.set(key, { identity, workspaceId, sessionId, open, end })
    return Object.freeze({ token, revoke: () => end('access-lost') })
  }

  /** Input is a plain parsed request supplied by the trusted caller; null covers all denials. */
  function open(request) {
    if (request === null || typeof request !== 'object' || Object.keys(request).length !== 4
        || !['version', 'workspaceId', 'sessionId', 'token'].every(key => Object.hasOwn(request, key))) return null
    const { version, workspaceId, sessionId, token } = request
    if (version !== VERSION || !validId(workspaceId) || !validId(sessionId)
        || typeof token !== 'string' || !TOKEN.test(token)) return null
    return grants.get(digest(token))?.open(workspaceId, sessionId) ?? null
  }

  function retireSource(identity) {
    for (const grant of grants.values()) if (grant.identity === identity) grant.end('access-lost')
  }

  function retireWhere(matches) {
    for (const grant of grants.values()) {
      if (matches(grant.workspaceId, grant.sessionId)) grant.end('access-lost')
    }
  }

  function dispose() {
    if (disposed) return
    disposed = true
    for (const grant of grants.values()) grant.end('disconnected')
  }

  return Object.freeze({ reader: Object.freeze({ open }), issue, retireSource, retireWhere, dispose })
}

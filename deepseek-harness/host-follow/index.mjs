import { createFollowService } from './follow-service.mjs'

export const name = 'cordiact-readonly-follow'
export const inject = ['agents', 'sessions', 'workspaceRegistry', 'storageDomain']

/** Fixed-version adapter to DSH's in-memory registries. No Gateway or listener is registered. */
export function apply(ctx) {
  const service = createFollowService((workspaceId, sessionId) => {
    const workspace = ctx.workspaceRegistry.get(workspaceId)
    const session = ctx.sessions.get(sessionId)
    if (!workspace || !session || session.header.cwd === undefined) return null

    return {
      identity: session,
      read() {
        if (ctx.workspaceRegistry.get(workspaceId) !== workspace || ctx.sessions.get(sessionId) !== session
            || !workspace.sessionIds.includes(sessionId)) return null
        const domain = ctx.storageDomain.get('workspace')
        const state = domain?.global.get()
        // Domain commits can precede the registry/entity cache update.
        if (!state?.workspaceIds.includes(workspaceId) || state.archivedSessionIds.includes(sessionId)
            || !domain.table('workspaces').get(workspaceId)?.sessionIds.includes(sessionId)) return null
        const agent = ctx.agents.get(sessionId)
        if (agent && agent.session !== session) return null
        return { cursor: session.seq - 1, active: agent !== undefined, running: agent?.status === 'running' }
      },
    }
  })

  ctx.effect(function* () {
    yield ctx.provide('cordiactFollowAuthority', Object.freeze({ issue: service.issue }))
    yield ctx.provide('cordiactFollowReader', service.reader)
    yield ctx.on('session/disposed', session => { service.retireSource(session) }, { global: true })
    yield ctx.on('domain/changed', change => {
      if (change.domain !== 'workspace') return
      // The committed domain value arrives before the entity swaps its cached record.
      if (change.table === 'workspaces') {
        service.retireWhere((workspaceId, sessionId) => workspaceId === change.key
          && (change.operation === 'deleted' || !change.value.sessionIds.includes(sessionId)))
      } else if (change.table === '' && change.operation === 'put') {
        service.retireWhere((workspaceId, sessionId) => !change.value.workspaceIds.includes(workspaceId)
          || change.value.archivedSessionIds.includes(sessionId))
      }
    }, { global: true })
    yield service.dispose
  }, 'cordiact-readonly-follow:lifecycle')
}

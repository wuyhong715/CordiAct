import { startLoopbackFollowHost } from './loopback-host.mjs'
import { withLocalFollowIdentity } from './local-identity.mjs'

export const name = 'cordiact-follow-loopback'
export const inject = ['cordiactFollowReader', 'cordiactFollowAuthority']

/** Opt-in Cordis adapter. Loading it doesn't listen, import keys, or register a command. */
export function apply(ctx) {
  ctx.effect(function* () {
    const reader = ctx.get('cordiactFollowReader')
    const authority = ctx.get('cordiactFollowAuthority')
    const lifetime = new AbortController()
    let started
    yield async () => {
      lifetime.abort()
      // A start racing disposal must settle before teardown returns.
      const host = await started?.catch(() => null)
      await host?.close()
    }
    const available = () => !lifetime.signal.aborted && ctx.get('cordiactFollowReader') === reader &&
      ctx.get('cordiactFollowAuthority') === authority
    function onceStart(start) {
      // Both entry points share one start per plugin generation, including on failure.
      if (started || !available()) return Promise.resolve(null)
      const result = Promise.withResolvers()
      started = result.promise
      try { result.resolve(start()) }
      catch (error) { result.reject(error) }
      return started
    }
    const launch = ({ identity, signingKey, deliveryKey }, scope) => {
      if (!available()) return null
      return startLoopbackFollowHost({ reader, authority, identity, signingKey, deliveryKey, scope, signal: lifetime.signal })
    }
    yield ctx.provide('cordiactFollowLoopback', Object.freeze({
      start: options => onceStart(() => launch(options ?? {}, options?.scope)),
      startFromDirectory: (directory, scope) => onceStart(() => {
        const { connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId } = scope ?? {}
        const fixed = { connectionId, localWorkspaceId, remoteWorkspaceId, remoteSessionId }
        return withLocalFollowIdentity(directory, lifetime.signal, material => launch(material, fixed))
      }),
    }))
  }, 'cordiact-follow-loopback:lifecycle')
}

import { startLoopbackFollowHost } from './loopback-host.mjs'

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
    yield ctx.provide('cordiactFollowLoopback', Object.freeze({
      start(options) {
        // A failed or closed start is not replayed. Reconfigure via a fresh plugin instance.
        if (lifetime.signal.aborted || started || ctx.get('cordiactFollowReader') !== reader ||
          ctx.get('cordiactFollowAuthority') !== authority) return Promise.resolve(null)
        const { identity, signingKey, deliveryKey, scope } = options ?? {}
        started = startLoopbackFollowHost({ reader, authority, identity, signingKey, deliveryKey, scope,
          signal: lifetime.signal })
        return started
      },
    }))
  }, 'cordiact-follow-loopback:lifecycle')
}

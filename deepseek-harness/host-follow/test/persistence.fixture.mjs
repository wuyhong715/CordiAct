export const name = 'synthetic-session-persistence'

export function apply(ctx) {
  ctx.provide('sessionPersistence', {
    list: async () => [],
    stat() { throw new Error('Unexpected synthetic persistence stat') },
    open() { throw new Error('Unexpected synthetic persistence open') },
  })
}

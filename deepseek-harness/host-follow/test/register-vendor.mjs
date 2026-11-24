import { registerHooks } from 'node:module'

// Test-only shim for reconstructing the pinned composition against the source of its
// Cordis dependencies. Keep this beside the generated modules under an isolated npm prefix.
const packages = new Map([
  ['@deepseek-ai/cordis', 'cordis'],
  ['@deepseek-ai/cosmokit', 'cosmokit'],
  ['@deepseek-ai/schemastery', 'schemastery'],
  ...['loader', 'include', 'group', 'logger-console', 'timer', 'hmr']
    .map(name => [`@deepseek-ai/cordis-plugin-${name}`, name]),
])

registerHooks({
  resolve(specifier, context, next) {
    const name = packages.get(specifier)
    if (name === undefined) return next(specifier, context)
    return { url: new URL(`./${name}.mjs`, import.meta.url).href, shortCircuit: true }
  },
})

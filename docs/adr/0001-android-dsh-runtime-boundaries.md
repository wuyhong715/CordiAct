# ADR 0001: Native-first plugin architecture across Android and DeepSeek Harness

- Status: Accepted
- Date: 2026-10-31

## Context

CordiAct is a native Android product that should support Cordis and preserve the DeepSeek Harness ecosystem. The product principle is “everything is a plugin”: client capabilities are plugins too; essential core capabilities are protected system plugins that users cannot uninstall. A minimal kernel/host is still required to bootstrap and enforce security boundaries.

Cordis is JavaScript/TypeScript-oriented, while substantial DSH functionality depends on a Node-capable host (for example subprocess, host filesystem, sandbox, and PTC execution). Forcing the whole DSH host into an APK would couple Android to server assumptions and create a large, fragile portability layer.

Inspection of the current DSH workspace found:

- Cordis core source under `vendor/cordis/src` has no direct `node:`/Node built-in imports; it is a plausible embedded-runtime target, subject to dependency and host-service checks.
- DSH Gateway documents an authenticated WebSocket endpoint and a shared Remote stream protocol for native Desktop callers.
- DSH also has an experimental WebWorker runtime with a virtual filesystem, module loader, and process shim. It is useful prior art, but is not evidence of production Android support.
- DSH Node PTC/runtime packages directly use Node facilities such as `node:vm`, `node:repl`, `node:path`, and `node:module`.

## Decision

Adopt a **native-first, multi-target plugin architecture**. “Plugin” is the unified product and lifecycle abstraction; it does not mean every plugin runs in the same runtime.

1. A minimal Kotlin kernel/host owns bootstrap, plugin discovery/lifecycle, manifest validation, policy/consent enforcement, capability mediation, and failure recovery.
2. User-facing and client capabilities are implemented as plugins where practical. Essential shipped capabilities are protected system plugins, with non-removability enforced by the native host and signed app metadata.
3. Kotlin Android plugins provide native UI/device integrations through versioned host services.
4. An embedded JavaScript engine may host a constrained set of mobile-portable Cordis plugins through a narrow typed/capability-based bridge.
5. The full DSH ecosystem continues to run on a Node-capable Harness host. CordiAct connects as a native client through the Gateway's authenticated WebSocket/Remote stream protocol.
6. Node-only DSH operations are remotely hosted, not emulated or advertised as local Android support.
7. All requests that can affect Android-local data or device capabilities are re-evaluated by native policy; a remote session cannot bypass user consent.

The plugin registry presents target, API version, capability requirements, protection/install class, and connection status. The app uses a common typed action/event model while retaining target-specific runtime adapters.

## Compatibility classes

- `mobile-native`: Kotlin implementation requiring Android APIs; execute locally after policy/consent.
- `mobile-cordis`: validated Cordis/JavaScript implementation using only supported mobile APIs and declared CordiAct capabilities; execute locally in the embedded JS runtime.
- `harness-hosted`: Node built-ins, subprocess, host filesystem, sandbox, PTC, or DSH host services; execute on the Harness host via Gateway.

A plugin is not assumed portable between classes. Target and capabilities are declared and negotiated; unsupported requirements fail closed. System-plugin status is verified/enforced by the host, not trusted from an arbitrary plugin manifest.

## Consequences

### Positive

- CordiAct remains a real native Android app while keeping a consistent plugin product model.
- Native device features can be first-class plugins without pretending Kotlin is a JavaScript runtime.
- Cordis concepts and validated portable plugins can be reused.
- DSH retains its complete Node-backed ecosystem and remains accessible through a defined protocol boundary.
- Android-specific authorization stays enforceable on-device.

### Costs and limitations

- The product has separate Kotlin, embedded-JS, and remote Node toolchains/targets; unified UX does not erase different runtime semantics.
- A versioned bridge, compatibility matrix, cross-runtime debugging, lifecycle/cancellation handling, and target-specific tests are required.
- Gateway-hosted plugins require connectivity and authentication; they may be unavailable offline or have additional latency.
- A Cordis-compatible plugin is not automatically mobile-compatible; transitive dependencies and required host services must be declared and verified.
- Full offline DSH functionality on Android is not promised by this decision.

These costs are accepted because plugin extensibility and DSH compatibility are core product differentiators. The JS runtime remains optional until its proof of concept passes; v1 must not promise every Harness plugin runs locally.

## Follow-up

1. Define one plugin descriptor/registry contract with target, API version, dependencies, capabilities, lifecycle, and protected status.
2. Keep the kernel limited to bootstrap, policy/consent, plugin lifecycle, and recovery; implement product capabilities as system or optional plugins.
3. Add a native Gateway transport and test handshake, authentication, streaming, reconnect, cancellation, and protocol compatibility.
4. Prototype Cordis in candidate JS engines; test module loading, Promises, timers, cancellation, persistence boundaries, memory limits, and background lifecycle.
5. Publish a plugin compatibility matrix and move only validated plugins into `mobile-cordis`.
6. Revisit the experimental WebWorker runtime only after its packaging and lifecycle assumptions are validated on Android.

## Evidence inspected

- DSH `vendor/cordis/package.json` and `vendor/cordis/src`.
- DSH `packages/api/gateway` docs and `stream-protocol` exports.
- DSH `packages/experimental/webworker-runtime` README.
- DSH `packages/ptc-runtime/ptc-runtime-node` source.

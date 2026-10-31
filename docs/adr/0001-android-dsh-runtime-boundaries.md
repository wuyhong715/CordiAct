# ADR 0001: Android and DeepSeek Harness runtime boundaries

- Status: Accepted
- Date: 2026-10-31

## Context

CordiAct is a native Android product that should support Cordis and preserve the DeepSeek Harness ecosystem. Cordis is JavaScript/TypeScript-oriented, while substantial DSH functionality depends on a Node-capable host (for example subprocess, host filesystem, sandbox, and PTC execution). Forcing the whole DSH host into an APK would couple Android to server assumptions and create a large, fragile portability layer.

Inspection of the current DSH workspace found:

- Cordis core source under `vendor/cordis/src` has no direct `node:`/Node built-in imports; it is a plausible embedded-runtime target, subject to dependency and host-service checks.
- DSH Gateway documents an authenticated WebSocket endpoint and a shared Remote stream protocol for native Desktop callers.
- DSH also has an experimental WebWorker runtime with a virtual filesystem, module loader, and process shim. It is useful prior art, but is not evidence of production Android support.
- DSH Node PTC/runtime packages directly use Node facilities such as `node:vm`, `node:repl`, `node:path`, and `node:module`.

## Decision

Adopt a **dual-runtime, native-first** architecture:

1. Kotlin is authoritative for the Android UI, workspace, lifecycle, local action policy, user consent, and device capabilities.
2. An embedded JavaScript engine may host a constrained set of mobile-portable Cordis plugins through a narrow typed/capability-based bridge.
3. The full DSH ecosystem continues to run on a Node-capable Harness host. CordiAct connects to it as a native client through the Gateway's authenticated WebSocket/Remote stream protocol.
4. Node-only DSH operations are remotely hosted, not emulated or advertised as local Android support.
5. All requests that can affect Android-local data or device capabilities are re-evaluated by native policy; a remote session cannot bypass consent.

## Compatibility classes

- `mobile-portable`: local Cordis plugin, no undeclared Node APIs, uses only allowed CordiAct capabilities.
- `harness-hosted`: Node built-ins, subprocess, host filesystem, sandbox, PTC, or DSH host services; execute on the Harness host.
- `mobile-native`: Kotlin implementation requiring Android APIs; execute locally after policy/consent.

Plugin metadata and runtime negotiation must report unsupported capabilities explicitly and fail closed.

## Consequences

### Positive

- CordiAct remains a real native Android app.
- Cordis concepts and portable plugins can be reused without pretending Kotlin is a JS runtime.
- DSH retains its complete Node-backed ecosystem and remains accessible through a defined protocol boundary.
- Android-specific authorization stays enforceable on-device.

### Costs and limitations

- Local and remote plugin execution have different capability sets and latency.
- Gateway connectivity, authentication, and protocol-version compatibility need implementation and tests.
- A Cordis-compatible plugin is not automatically mobile-compatible; transitive dependencies and required host services must be declared and verified.
- Full offline DSH functionality on Android is not promised by this decision.

## Follow-up

1. Add a native Gateway transport and test handshake, authentication, streaming, reconnect, cancellation, and protocol compatibility.
2. Define the CordiAct capability manifest and local policy checks.
3. Prototype Cordis in candidate JS engines; test module loading, Promises, timers, cancellation, persistence boundaries, memory limits, and background lifecycle.
4. Publish a plugin compatibility matrix and move only validated plugins into the mobile-portable class.
5. Revisit the experimental WebWorker runtime only after its packaging and lifecycle assumptions are validated on Android.

## Evidence inspected

- DSH `vendor/cordis/package.json` and `vendor/cordis/src`.
- DSH `packages/api/gateway` docs and `stream-protocol` exports.
- DSH `packages/experimental/webworker-runtime` README.
- DSH `packages/ptc-runtime/ptc-runtime-node` source.

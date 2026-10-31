# CordiAct architecture

## Product boundary

CordiAct is an independent native Android product. Cordis is the plugin/kernel model to support; DeepSeek Harness (DSH) remains a first-class ecosystem and execution target. The Android APK is not required to embed every Node-oriented DSH package.

## Decision: dual runtime, native-first

- **Native Android host (Kotlin)** owns UI, workspace persistence, Android lifecycle, device capabilities, user consent, local action policy, and audit history.
- **Cordis-compatible JavaScript runtime** hosts only mobile-portable Cordis plugins. It reaches Android features through a narrow, versioned bridge; plugins do not receive ambient filesystem, shell, or Android permissions.
- **DeepSeek Harness runtime** remains the full-featured Node-capable host for the complete DSH plugin/tool ecosystem. CordiAct connects to it as a native client through DSH Gateway's authenticated WebSocket stream protocol.
- The app may run local Cordis plugins and remote DSH actions behind the same action/event interfaces. Remote execution does not grant access to Android capabilities; device actions are always checked by the local Kotlin policy.

This preserves native Android behavior and DSH compatibility without claiming that Node-only modules can run in an Android JavaScript engine.

## Runtime boundaries

### Native host

The app shell and domain APIs live in Kotlin. Provider-specific and DSH-specific behavior stays out of UI code. Workspace, policy, capability grants, connection state, and audit events have native implementations.

### Local Cordis runtime

The Cordis core under DSH's `vendor/cordis` has no direct Node built-in imports in its core source, so it is a plausible embedded-runtime target. That does **not** automatically make all plugins portable: every transitive dependency, module-loading assumption, timer/event-loop feature, and host service must be checked. Build Cordis as a pinned, reproducible JS artifact and host it behind a `CordisRuntime` interface. Select the concrete Android JS engine only after a proof of concept validates ESM loading, Promises, timers, cancellation, memory limits, and lifecycle behavior.

The bridge is capability-based and typed. Example capabilities: workspace read/write (scoped to a workspace), user-approved file picker, notifications, and explicit action requests. Do not expose a generic `eval`, unrestricted `file://` access, shell, or raw Android object graph.

### DeepSeek Harness integration

Use the DSH Gateway as the compatibility boundary for the full Harness. DSH's gateway package documents an authenticated WebSocket endpoint and exports a shared Remote stream protocol for native Desktop callers. CordiAct should implement that protocol in a native transport module (or a thin tested adapter), rather than embedding DSH's Node host, subprocess, sandbox, or PTC runtime in the APK.

The native app sends requests and consumes session/action events over this connection. DSH remains responsible for its full server-side plugins, tools, sessions, and Node-specific execution. The app remains responsible for local Android permissions and any local device action.

### Compatibility classes

- **Mobile portable**: Cordis plugin + supported Web APIs/declared CordiAct capabilities; runs locally.
- **Harness hosted**: needs Node built-ins, subprocess, host filesystem, sandbox, PTC, or DSH host services; runs on the DSH Gateway/host and is controlled remotely.
- **Mobile native**: Kotlin capability or UI feature; callable only after local policy/consent.

A plugin manifest should declare its class and required capabilities. Unsupported requirements fail closed with an actionable explanation, not a partial silent fallback.

## Request flow

1. Native UI submits a typed `ActionRequest` to the Kotlin runtime coordinator.
2. Local policy evaluates scope, risk, and required user confirmation.
3. Coordinator routes to an allowed local Cordis plugin, a local native action, or the authenticated DSH Gateway.
4. Remote events and results return as typed events; local Android capabilities remain inaccessible to the remote host unless the user approves a separate native action.
5. The app records provenance, policy decision, and result in the workspace audit trail.

## Known constraint

The DSH experimental WebWorker runtime is useful prior art for packaging a Harness tree without Node, but it is explicitly experimental and relies on its own virtual filesystem, module loader, process shim, and browser-worker host. Treat it as a possible later experiment, not as proof that the full DSH runtime is Android-ready.

## Initial modules

- `app`: native Android shell and unified conversation UI
- `core`: workspace, action policy, event, and runtime contracts
- `integration`: native DSH Gateway transport/protocol adapter
- `runtime-cordis`: Android JS runtime host and Cordis bridge (planned)
- `docs`: architecture decisions and compatibility matrix

## Open decisions

- Which embedded JS engine passes the Cordis mobile proof of concept
- Gateway authentication enrollment, credential storage, and reconnect policy
- Which DSH actions need local-native counterparts
- Plugin manifest format and signature/trust policy
- Offline behavior when no DSH Gateway is reachable
- Workspace synchronization strategy

See [ADR 0001: Android and DSH runtime boundaries](adr/0001-android-dsh-runtime-boundaries.md).

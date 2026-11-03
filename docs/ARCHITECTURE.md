# CordiAct architecture

## Product principle: everything is a plugin

CordiAct treats product capabilities as plugins: conversation surfaces, workspace features, Android integrations, local Cordis extensions, and remote DeepSeek Harness tools all enter through a common plugin model. Some plugins are **system plugins**: essential, shipped with the app, and not user-uninstallable. “Non-removable” is a lifecycle/protection policy, not a different product concept.

A very small **kernel/host** is still necessary to bootstrap the app and plugin system. It owns plugin discovery and lifecycle, manifest validation, dependency resolution, event routing, storage boundaries, permission/policy enforcement, and recovery when plugins fail. It should not absorb every feature implementation. Security enforcement cannot itself be delegated to an untrusted plugin.

## Decision: native-first, multi-target plugin runtime

The plugin model is unified; code does not have to run in one runtime. CordiAct has two on-device execution environments plus the external Harness host:

1. **Kotlin / Android host** owns the app shell, plugin manager, lifecycle integration, workspace/security boundary, Android APIs, policy, consent, and audit. Android-specific capability plugins are native Kotlin modules.
2. **Embedded JavaScript runtime** hosts only validated mobile-portable Cordis plugins, reached through a narrow typed capability bridge.
3. **Node-capable DeepSeek Harness host** continues to run the full DSH ecosystem, including plugins that depend on Node APIs, host filesystems, subprocesses, sandboxes, or PTC. The Android app connects through DSH Gateway's authenticated WebSocket stream protocol.

The UI should see one plugin registry and one typed action/event model. The registry can show each plugin's execution target, required capabilities, connection state, compatibility, and whether it is protected. It routes to the appropriate target only after local policy checks. A remote plugin must not silently acquire Android-local capabilities.

## System plugins and protection

System plugins use the same manifest, lifecycle, and events as ordinary plugins, but their protection is enforced by the native host and signed app metadata—not by trusting a self-declared flag. The first bundled system plugins are **Model Adapter** (`core.model-adapter`) and **Session Manager** (`core.session-manager`); together they are the conversation foundation. Model Adapter exposes a provider-neutral model catalog/generation API and owns in-flight request cancellation. Session Manager owns workspace-scoped durable sessions/messages. The bundled `model.deepseek` provider registers through the adapter boundary, streams Chat Completions, and is composed with a Session Manager-backed conversation turn service; provider networking and credentials remain outside this core plugin. The kernel/plugin manager, manifest verifier, permission gate, and minimal crash recovery remain host infrastructure.

User-uninstallable plugins can be disabled or removed through the plugin manager. Protected plugins can be updated as part of an app release or an explicitly trusted system-plugin update path; the user interface must say which applies. A plugin failure must not disable the policy gate or corrupt workspace data.

## Runtime targets and compatibility

### Native Android plugins

Kotlin plugins integrate Android lifecycle and Android APIs through narrow host services. They declare capabilities and never bypass the native policy/consent gate. The runtime should isolate failures and keep the plugin API versioned; Android code is trusted app code unless a separate verified sandbox is implemented.

### Local Cordis plugins

The Cordis core under DSH's `vendor/cordis` has no direct Node built-in imports in its core source, so it is a plausible embedded-runtime target. This does **not** make every Cordis plugin portable: transitive dependencies, module loading, timers/event-loop behavior, host services, and permission assumptions must be checked. Pin and reproducibly package Cordis; host it behind a `CordisRuntime` adapter. Select the concrete Android JS engine only after a proof of concept validates ESM loading, Promises, timers, cancellation, memory limits, persistence boundaries, and lifecycle behavior.

The JS bridge is typed and capability-based. Examples include workspace read/write scoped to the active workspace, user-approved file selection, notifications, and explicit action requests. Do not expose generic `eval`, unrestricted `file://` access, shell, or the raw Android object graph.

### DeepSeek Harness plugins

Use DSH Gateway as the compatibility boundary for the full Harness. DSH's gateway package documents an authenticated WebSocket endpoint and exports a shared Remote stream protocol for native Desktop callers. CordiAct should implement the compatible native client transport (or a thin tested adapter), rather than embedding DSH's Node host, subprocess, sandbox, or PTC runtime in the APK.

A remote plugin is still a CordiAct-visible plugin, but its implementation and execution live on the Harness host. Remote responses are data; any request to act on Android-local resources is submitted as a separate native action and rechecked locally.

### Compatibility classes

- **`mobile-native`**: Kotlin implementation using Android capabilities; runs locally after policy/consent.
- **`mobile-cordis`**: Cordis/JavaScript implementation using only validated mobile APIs and declared CordiAct capabilities; intended to run in an Android isolated-service process managed by Sandbox Manager, not directly in the app process.
- **`harness-hosted`**: DSH/Node implementation needing Node built-ins, subprocess, host filesystem, sandbox, PTC, or DSH host services; runs on the Harness host through Gateway.

The plugin descriptor should state its target, API version, dependencies, requested capabilities, and install/protection class. A plugin must not be advertised as portable across targets without tests. Unsupported requirements fail closed with an actionable explanation.

## Sandbox Manager

Sandbox Manager is a protected first-class system plugin that manages execution profiles, process health, deadlines, crash recovery, and user-visible sandbox status. The native kernel/host remains the final reference monitor: it validates identity and authorizes every capability request outside plugin code. The planned local boundary for Cordis/JS plugins is a non-exported Android isolated-service process behind typed Binder IPC. A fixed-probe isolated Android service passes 13 tests on API 29/35 emulators and prior fixed-probe tests passed on vivo API 36. A QuickJS-NG signed-script PoC now passes 19 tests on each emulator API; the same CI APK pair also passed 19 tests on vivo API 36. The raw-frame set is bounded. The PoC is not wired to production dispatch or general plugin code; the production Sandbox Manager remains fail-closed. Trusted bundled Kotlin plugins remain trusted app code—not “sandboxed.” Remote Harness isolation is owned by the remote host. If the device cannot provide the requested local isolation, the plugin is remote-only or disabled; there is no silent in-process fallback. V1 policy is accepted: local Cordis/JS must be signed and bundled in the APK; third-party Harness plugins stay remote. Actual local sandbox launch remains disabled until Android isolation gates pass; see [ADR 0002](adr/0002-sandbox-manager-design.md).

## Request flow

1. Native UI or another plugin submits a typed request to the plugin/action registry.
2. The native policy gate evaluates workspace scope, target, requested capabilities, risk, and required confirmation.
3. The plugin manager routes to an allowed native plugin, local Cordis plugin, or authenticated DSH Gateway plugin.
4. Events/results return through a common typed event model. Remote work cannot bypass local Android permission checks.
5. The workspace audit trail records plugin identity/version, execution target, policy decision, provenance, and result.

## Dual-runtime costs and acceptance

Running Kotlin and JavaScript on-device, while connecting to a remote Node host, adds real costs: duplicated toolchains and debugging, a versioned cross-runtime bridge, lifecycle/cancellation complexity, plugin compatibility fragmentation, security review at each boundary, and network/authentication/offline handling for Harness-hosted plugins. The plugin abstraction reduces product-level fragmentation but cannot erase differences in runtime, latency, permissions, or offline availability.

These costs are acceptable if “everything is a plugin” and access to the DSH ecosystem are central product differentiators. They are not acceptable if v1 promises every DSH plugin to run locally/offline on Android. Keep the kernel thin, ship only essential system plugins, make the JS runtime optional until the Cordis proof of concept passes, and make remote-only capabilities explicit in the UI. This allows a native-first v1 without pretending all execution targets are equivalent.

## Known constraint

The DSH experimental WebWorker runtime is useful prior art for packaging a Harness tree without Node, but it is explicitly experimental and relies on its own virtual filesystem, module loader, process shim, and browser-worker host. Treat it as a later experiment, not evidence that the full DSH runtime is Android-ready.

## Initial modules

- `app`: native shell, plugin manager/registry, unified conversation UI
- protected system plugins: Model Adapter, Session Manager, Sandbox Manager (core orchestrator registered; Android isolated runtime still gated by ADR 0002)
- `core`: plugin contracts, workspace, policy/consent, events, lifecycle, audit
- `app` Android adapters: SQLite lifecycle/consent store, runtime controller registry, scoped data/cache/artifact cleanup, startup recovery
- `plugins-android`: protected and optional native Android capability plugins (planned)
- `runtime-cordis`: embedded JS host and Cordis capability bridge (planned)
- `interaction`: Workspace/Session-scoped interaction parsing, references, command dispatch, and authorization contracts
- `model-providers`: protocol-neutral provider implementations and provider registry for DeepSeek, Anthropic, Gemini, OpenAI, and compatible endpoints
- `deepseek-harness`: isolated contract for submitting actions to a remote DeepSeek Harness; currently not consumed by the app
- `docs`: architecture decisions and plugin compatibility matrix

## Open decisions

- Which embedded JS engine passes the Cordis mobile proof of concept
- Plugin descriptor and target-specific API version negotiation
- System-plugin signature/protection and safe update model
- Gateway enrollment, credential storage, and reconnect policy
- Which DSH actions need local-native counterparts
- Offline behavior when no DSH Gateway is reachable
- Workspace synchronization strategy

See [ADR 0001: Android and DSH runtime boundaries](adr/0001-android-dsh-runtime-boundaries.md) and [ADR 0002: Sandbox Manager design](adr/0002-sandbox-manager-design.md).

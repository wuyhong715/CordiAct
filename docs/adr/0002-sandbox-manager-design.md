# ADR 0002: Sandbox Manager as a first-class plugin and security boundary

- Status: Accepted for v1 execution policy; S3 test-only fixed-script PoC is being implemented; production execution remains gated on validation and acceptance criteria
- Date: 2026-10-31

## Problem

CordiAct's “everything is a plugin” model needs a first-class place to describe, provision, monitor, stop, and clean up plugin execution environments. A file-scope policy or a JavaScript engine alone is not a security sandbox. The design must preserve the native-first product, protect workspace/model credentials/device capabilities, and keep the Node-based DeepSeek Harness ecosystem available remotely.

## Threat model

### Protect

- Model/provider credentials and authentication material
- Workspace files, sessions, messages, and private plugin data
- Android permissions and device capabilities
- Other plugins' state, grants, processes, and availability
- User and system resources (memory, CPU time, network, battery)

### Treat as potentially hostile or faulty

- User-installed or third-party interpreted plugin code
- Buggy plugins that loop forever, overproduce output, crash, or retain work after disable
- Malformed/forged plugin manifests and capability requests
- Remote Harness output and requests (untrusted data; cannot directly exercise Android APIs)

### Out of scope for v1

- A rooted/compromised device, Android kernel/SELinux exploit, compromised app signing key, or vulnerabilities in the Android runtime/JS engine that escape the OS isolation boundary
- Hard real-time guarantees or strict CPU fairness against a privileged OS process
- Reversing external side effects already completed (for example a remote message already sent)

## V1 product decision (accepted 2026-10-31)

- Local Cordis/JavaScript execution is restricted to code bundled in the signed APK and present in a host-owned exact allowlist. No post-install public third-party local plugin installation in v1.
- Third-party DeepSeek Harness plugins remain available through the remote Harness/Gateway path; they do not execute in the Android app process.
- The local sandbox execution path remains disabled until the isolated-service proof of concept and the production acceptance gates below pass. A signed bundle is provenance, not proof that in-process execution is safe.
- The `core.sandbox-manager` system plugin is registered/enabled as an orchestration service before the runtime exists; its default runtime refuses execution. No in-process fallback.

## Core security decision

**Sandbox Manager is a protected system plugin and a first-class product service; the final reference monitor remains in the tiny native kernel/host.**

The Manager owns profiles, sandbox lifecycle, quotas/deadlines, process health, crash handling, and user-visible status. It may request work, but it cannot grant itself privileges. The native kernel/host must mediate every capability operation and can reject the Manager or any plugin. The policy check cannot be delegated to an interpreted sandbox or to a self-declared plugin manifest.

## Execution classes

| Class | Code | Execution boundary | v1 rule |
| --- | --- | --- | --- |
| `trusted-system` | Signed, app-shipped Kotlin plugins (currently Model Adapter and Session Manager) | Main app UID/process; trusted app code | Allowed only from a host-owned exact catalog; not described as sandboxed |
| `mobile-isolated-js` | Interpreted Cordis/mobile plugin | Dedicated Android isolated service process + narrow Binder IPC capability broker | Candidate local untrusted-plugin target; no in-process fallback |
| `harness-remote` | Node-only DeepSeek Harness plugins/tools | DSH host and its own isolation controls; native Gateway client | Supported remotely; device-side manager cannot certify remote isolation |
| `native-third-party` | Downloaded/installed Kotlin, DEX/JAR, or `.so` code | No safe generic in-app plugin boundary | Prohibited in v1; require app-store/install/signing/security redesign before considering |

A bundled Kotlin plugin is still trusted app code even though it is represented by a plugin manifest. The plugin abstraction does not turn same-process native code into an OS security boundary.

## Platform mechanism considered

### Recommended candidate: Android isolated service process

Use a non-exported, `android:isolatedProcess="true"` service as the execution host for untrusted local interpreted code. Android documents the attribute and its service semantics; the AOSP Application Sandbox documentation describes per-app Linux UIDs and process-level kernel isolation. The isolated service must not receive app permissions, the main app's `Context`, raw database handles, provider API keys, or direct Android object references. Communication is only through a small, versioned typed Binder protocol.

Where the platform API supports named isolated service instances, assign a distinct instance/process to each mutually untrusted plugin. Otherwise, use one plugin per isolated process lifetime and terminate it before switching identity. Never share a sandbox process across unrelated third-party plugins merely to save memory. If a device/API cannot provide the required isolation, mark local execution unavailable and route to the Harness host or disable the plugin; never silently execute in the app process.

At service startup, verify the process is isolated and bind the runtime to the expected plugin/profile identity. Require a private, non-exported service component and authenticate the calling UID/session on all IPC. The host creates an unforgeable session nonce and binds it to plugin ID, version, profile, workspace, granted capabilities, and expiry.

### Why not an in-process JavaScript “sandbox”

An embedded JS engine is useful for execution compatibility, but it is not by itself the security boundary: host functions, native bindings, engine bugs, unbounded memory, or leaked handles can cross the intended boundary. It may run inside the isolated service, behind Android's process/UID boundary. In-process execution is acceptable only for code trusted as part of the signed app, not arbitrary plugins.

### Why not run third-party Kotlin/Dex in a sandbox

Loading arbitrary DEX/JAR/native code into the host gives it the app's process identity and reachable host APIs. A separate service does not make arbitrary plugin class loading safe unless a strict, audited IPC/module boundary exists. V1 will not dynamically load third-party native code. Any later native extension format requires a separate signed-package, loader, distribution-policy, and security review.

### Why not Android Virtualization Framework (AVF) for v1

AVF/pVM offers stronger isolation for security-oriented use cases, but is an optional device capability (AOSP documents ARM64 support constraints) with higher startup, packaging, image, and IPC costs. Keep it as a future high-assurance execution profile only if a concrete plugin threat justifies those costs; do not make it the baseline Android requirement.

## Capability broker

The isolated runtime starts with no ambient file, network, account, or Android-device authority. It submits typed capability requests to the native broker. The broker evaluates each operation against:

- Host-verified plugin identity, signature/hash, target, and requested capability set
- User consent and current plugin/session/workspace state
- Action policy, risk, scope, rate/size limits, and permission freshness
- Sandbox session nonce and expiry

Return only the result needed for the operation. Do not pass raw filesystem paths, database objects, long-lived tokens, Android `Context`, or general-purpose reflection/eval handles. Provider credentials stay in the Model Adapter/provider host; workspace access stays in the Session Manager/workspace host. A plugin asks for a model action or scoped data operation; it never receives the host's backing credentials or unrestricted storage.

Network is denied inside the isolated process by default. If plugins need network access, v1 should use a host proxy/broker with explicit policy and origin/rate/size controls; otherwise classify the work as `harness-remote`. File selection is a host-mediated picker operation with a scoped returned handle, not arbitrary file access.

## Manifest and profile

A host-validated sandbox profile is derived from a signed/approved plugin descriptor and policy—not trusted verbatim from plugin code. Proposed fields:

- plugin ID, version, code digest/signature, runtime target, plugin API version
- declared capabilities and capability-schema versions
- data namespace and workspace scope
- network mode (`none` by default, brokered only by explicit policy)
- execution policy: concurrency, deadline, message/output bounds, restart policy
- install class (`system` / `user`) and trust tier (host-assigned)

Do not let a plugin request a weaker isolation profile than host policy allows. Unknown capabilities or runtime versions fail closed.

## Lifecycle and revocation

Sandbox instances join the existing plugin lifecycle; they are not a second, competing lifecycle:

1. Plugin Manager validates the signed descriptor and host-owned trust class.
2. Kernel policy resolves requested capabilities and user consent.
3. Sandbox Manager allocates an isolated session and starts the runtime with a short-lived identity.
4. Each request/event is bounded, authenticated, and brokered. Host checks identity, lifecycle state, scope, and capability on every request.
5. Disable/uninstall first closes the action gate, cancels requests, closes Binder/IPC, kills the isolated process, suspends/revokes capabilities, drains registered Android resources, and then applies the existing owned-data cleanup/tombstone protocol.
6. Process death means plugin is no longer trusted as running; host revokes session grants, marks it crashed/disabled, records bounded diagnostics, and requires policy-controlled restart. No automatic restart loop.

Uninstall completion requires confirmed sandbox termination and all plugin-owned cleanup stages succeeding. If a process cannot be killed/confirmed, the tombstone remains and the UI reports incomplete cleanup; do not delete the only identity record first.

## Resource controls and honest limits

V1 should enforce what can be enforced and state what cannot:

- Hard caps on IPC frame size, response/event count, concurrent requests, and plugin-owned persistent/temporary bytes
- Wall-clock deadline with cooperative cancel followed by isolated-process termination
- JS-engine heap/stack limits if the chosen engine exposes enforceable limits; verify with adversarial tests before claiming them
- Monitor process death/memory pressure and terminate runaway work; treat Android OS memory management as a backstop, not a quota API
- No claim of strict CPU quota or protection from kernel/engine zero-days
- Bounded crash logs and no plugin secret/user-content dumps in diagnostics

## DeepSeek Harness boundary

The Android Sandbox Manager manages **local** isolated processes only. `harness-remote` plugins stay on the DSH host and require that host's configured sandbox/policy. CordiAct can display the remote runtime and require local confirmation before applying a returned action, but must not advertise a remote Harness policy as Android-enforced isolation. DSH filesystem policy sandboxes can be useful defense-in-depth, but they are not by themselves the same thing as an OS-enforced process boundary.

## Distribution and policy gate

Before enabling post-install interpreted plugin code in a Google Play build, review the then-current Play Device and Network Abuse policy and dynamic-code-loading guidance. Android's security guidance warns about tampered/substituted dynamic code; Play policy permits only constrained interpreted/VM cases and prohibits interpreted code from enabling policy violations. V1 must not fetch executable DEX/JAR/`.so` files outside Play. Signed plugin provenance, review/moderation, update/rollback, and user disclosure need an explicit product plan before public third-party plugin distribution.

## Alternatives

1. **Policy-only filesystem “sandbox”** — insufficient against arbitrary code; useful as defense-in-depth for trusted tools only.
2. **JS-engine-only isolation** — portable but not an OS boundary; rejected for untrusted code.
3. **Android isolated service + typed broker** — recommended baseline candidate for local interpreted plugins.
4. **AVF/pVM** — stronger optional future profile; not baseline.
5. **Remote-only execution** — safest initial distribution option for plugins whose local sandbox/Play policy is unproven; may trade off offline/mobile behavior.

## Phased implementation proposal

### S0 — Threat model and profile contract (this ADR)

The product decision is now set: local code must be signed and bundled; third-party Harness plugins remain remote. Keep local execution disabled until the following gates pass.

### S1 — Sandbox Manager contracts and fail-closed orchestration (implemented)

Core now defines sandbox identity/profile, bounded IPC/deadline limits, host-resolved capabilities, trusted APK-bundle verification, isolated-runtime attestation, instance/termination states, and protected `core.sandbox-manager` orchestration. App startup registers the Sandbox Manager as a protected system plugin. The default Android runtime is explicitly unavailable, so all local launch requests fail closed; no in-process fallback exists. Core tests use a deterministic fake isolated runtime to exercise admission, capability subset checks, limits, termination, revocation, and failure retention. The Android 35 emulator proof is implemented separately as a fixed probe service; it is not yet connected to production plugin dispatch.

### S2 — Android isolated-service proof of concept (passes API 29/35 emulators and vivo API 36 device)

A non-exported `android:isolatedProcess="true"` service and typed AIDL Binder probe now run in unique `Context.bindIsolatedService` instances (API 29+). It verifies the isolated UID, checks the host caller UID and per-session nonce, limits frames/deadlines, and supports only fixed `echo`, bounded `sleep`, intentional `hang`, and `crash` test operations. An independent service-main-loop watchdog kills a hung worker process. The isolated process skips all normal host `Application` initialization and receives no database/provider, credential, workspace, or capability broker.

Four instrumentation tests pass on both **API 29** (the named-isolated-instance floor) and **API 35** emulators — 4 passed / 0 skipped on each (run `41208793283`, code commit `8c3f653`): isolated UID differs from host, concurrent sessions receive distinct isolated UIDs, malformed identity/oversized frames are rejected, the deadline watchdog kills an infinite test operation, an intentional worker crash leaves the host responsive, a fresh isolated process can start afterward, and graceful termination works. The CI runner grants the disposable user access to `/dev/kvm`; software-only emulation proved too slow/unresponsive. Android rejected hyphens in `bindIsolatedService` instance names, so names are restricted to `[A-Za-z0-9_.]+`. Android also instantiates `CordiActApplication` in each process, so `Process.isIsolated()` now prevents the worker UID from initializing SQLite/providers/host plugins.

**Physical-device result:** vivo V2463A / Android 16 / API 36 / arm64-v8a passed all four tests from CI run `41208793283`; details are in [the S2 device report](../testing/sandbox-s2-physical-device.md). The production Sandbox Manager remains wired to `UnavailableIsolatedSandboxRuntime`; the probe does not load Cordis or any plugin code. No local plugin execution is enabled. Binder fuzz/resource-pressure and Doze/background tests remain open.

### S3 — JS engine inside the isolated service (selected engine; PoC validation pending)

Select QuickJS-NG v0.17.0, pinned at commit `6d46d07d04041b40f4f49eaa7fdebe44c314c699` under `third_party/quickjs-ng`. The MIT license is preserved in the APK resources. The test PoC builds the core with Android NDK and omits `quickjs-libc.c`; no module loader, Java/JNI host object, filesystem/network bridge or arbitrary source input is exposed. Only hash-pinned APK raw resources are executed by `SandboxJsPocService` in its own non-exported isolated service process. Script bytes are capped at 16 KiB, QuickJS heap at 8 MiB, stack at 256 KiB, and wall time at 5 seconds; a QuickJS interrupt callback and independent service watchdog terminate the isolated process on deadline. Fixed memory/stack/loop vectors exercise those boundaries.

This remains an isolated-service experiment, **not** a production plugin runtime: the AIDL has no code-upload method; tests execute only bundled fixed scripts; `SandboxManager` still uses `UnavailableIsolatedSandboxRuntime`; v1 third-party local plugins remain disabled. These C API bounds do not impose whole-process RSS or strict CPU quotas. The source comparison and precise gates are in [the S3 engine/PoC report](../testing/sandbox-s3-js-engine-and-poc.md). API 29/35 and API 36 results remain pending until the GitHub Actions build and same-run artifact are executed and reviewed.

### S4 — Capability broker and plugin integration

Integrate workspace/model operations as host-owned service calls; bind every permission to a short-lived sandbox identity; verify lifecycle cleanup, process death, and startup recovery. Keep network off unless proxy policy is implemented.

### S5 — Distribution review (v1 scope already chosen)

V1 local plugins are signed and bundled only; third-party DeepSeek Harness plugins remain remote. Complete Play policy review, signing/update/rollback controls, and supply-chain review before any public third-party local plugin distribution.

## Acceptance gates before production use

- **Validated:** Android 29 and 35 emulators plus vivo API 36 physical device report isolated UIDs distinct from the app; concurrent named instances also receive distinct UIDs. Plugin-level data-leak isolation is not tested because this probe exposes no plugin code or workspace data.
- Malicious test cannot read another plugin's files, session content, app preferences, API key, or Android permission; broker rejects malformed/unauthorized requests.
- **Validated on API 29/35 emulators and vivo API 36 device:** the watchdog kills an infinite fixed probe, worker crash does not crash the host, and a fresh isolated process starts afterward. Interpreter cancellation/late-event behavior remains untested because no JS engine is loaded.
- Disable/uninstall closes IPC, kills the process, revokes grants, clears owned temporary/permanent state, and survives forced process death at each cleanup checkpoint.
- **Partially validated:** oversized fixed-probe frame rejected on emulators and API 36 device; Binder fuzzing, memory pressure, and no-ANR guarantees remain untested.
- Release/signing/Play policy review is complete for the intended distribution channel.

## Evidence reviewed

- Android `<service>` manifest reference: `https://developer.android.com/guide/topics/manifest/service-element`.
- AOSP Application Sandbox: `https://source.android.com/security/app-sandbox`.
- Android dynamic code loading security guidance: `https://developer.android.com/privacy-and-security/risks/dynamic-code-loading`.
- Google Play Developer Program Policy (runtime interpreted code): `https://support.google.com/googleplay/android-developer/answer/18258653?hl=en`.
- AOSP Android Virtualization Framework: `https://source.android.com/docs/core/virtualization`.
- CordiAct's checked-out DSH `packages/experimental/webworker-runtime`, `packages/ptc-runtime/ptc-runtime-node`, and filesystem sandbox sources.

### Physical-device validation procedure

The Android 35 CI matrix job publishes the exact tested `app-debug.apk` and `app-debug-androidTest.apk` as `cordiact-device-validation-apks`. Install both APKs on the test device via `adb install -r`/`pm install -r`, then run `am instrument -w -r com.cordiact.app.test/androidx.test.runner.AndroidJUnitRunner`. Record build fingerprint/API/ABI, test output, isolated worker UID(s), and logcat crash evidence. Do not install a locally built APK; the project policy is GitHub Actions-only packaging. Remove the two test packages after validation if desired; this does not affect user data because the device is not expected to have an existing CordiAct install.

### S2.1 — Binder and lifecycle hardening (fixed probe validated)

The fixed probe admits at most one active Binder operation per isolated instance, with close and admission sharing a lock. The host uses one request permit, one worker, and one bounded handoff slot; rejected requests cannot create an unbounded backlog. The service validates request IDs, UTF-8 frame lengths, deadlines, session nonce and an independent watchdog. Tests cover malformed input, concurrent floods, repeated cancellation, close during an active call, and 24 immediate sequential calls. GitHub Actions run `41215976392` at code commit `ad44a44` passed **10/10** on API 29 and API 35 emulators, each with zero failures/skips. The same CI-built APKs passed **10/10** on vivo V2463A Android 16/API 36; see [S2.1 test report](../testing/sandbox-s21-ipc-hardening.md). These results apply only to the fixed probe, not to a Cordis interpreter or production capability broker.

Remaining security work includes binder parcel fuzzing, explicit memory/battery pressure limits, a hard wall-clock bound on every host-to-worker Binder call, Doze/background lifecycle, and proving revocation after process death. `UnavailableIsolatedSandboxRuntime` remains the production default; no JS/plugin execution is enabled by this step.

API 29's initial S2.1 run exposed a zero-queue `SynchronousQueue` handoff race: `Future.get()` returned before the worker became idle, causing a false rejection of the next sequential call. The explicit one-call permit plus bounded handoff slot resolved this on both emulator API levels and on the physical device.

### S2.2 — Fixed-probe frame boundary tests (validated, limited scope)

GitHub Actions run `41219780526` at commit `d9a93ba` passed **13/13** on both API 29 and API 35 emulators; the same CI APKs passed **13/13 twice** on vivo V2463A / Android 16 / API 36. Tests send a valid raw Binder transaction as a positive control, then four malformed frames: wrong interface token, truncated fields, oversized payload and trailing bytes. The service rejects these, also rejecting a deterministic 8,193-byte output above its 8,192-byte reply cap; 64 repeated 4,096-byte requests leave the worker responsive. The initial test run discovered that generated AIDL dispatch accepted trailing fields; the service now validates total Parcel size and exact field consumption before delegating to the generated dispatcher. See [S2.2 test report](../testing/sandbox-s22-binder-boundaries.md).

These are bounded fixed-probe mutations, **not comprehensive fuzzing or measured resource stress**. Raw Binder edge cases beyond the tested frames, CPU/heap pressure, Doze/background restrictions and JS interpreter properties remain open. The production plugin runtime remains unavailable.

### S2.3 — Bounded Binder/resource/background validation (implementation pending execution)

The current S2.3 instrumentation extends the raw-frame corpus with unknown transaction code, wrong token, truncated fields, oversized string, trailing data, bad session/nonce/request ID/operation and invalid/over-limit deadline; each is followed by a valid echo. It adds a bounded two-million-iteration CPU sample and a 2 MiB touched-heap sample with thread CPU/wall, PSS and Java heap metrics. A Doze/background test only proceeds when the pre-test DeviceIdle/light-idle states are ACTIVE and the force-idle flag is false; it temporarily forces DeviceIdle and app-inactive state, tests one isolated Binder echo, restores prior values in `finally`, and checks the original states. CI also registers an EXIT trap to clear force-idle if the emulator was not forced before the test. The set is a fixed validation corpus, not general-purpose Parcel fuzzing, a CPU quota, RSS quota or battery test. See [S2.3 report](../testing/sandbox-s23-resource-background.md).

At this revision the instrumentation and report exist locally, but the attempted workspace Gradle test did not resolve AGP 9.4.1, and GitHub Actions has not yet run because commit `b4b50ec` has not been pushed. Therefore all S2.3 outcome fields remain pending; do not describe these tests as passing.

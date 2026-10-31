# Plugin lifecycle contract (v1)

## Lifecycle states

`REGISTERING -> REGISTERED -> ENABLING -> ENABLED -> DISABLING -> DISABLED`

Any incomplete registration/enable/disable enters `FAILED` and is not routable. Uninstall first writes the durable `UNINSTALLING` tombstone, then performs cleanup. A failed uninstall keeps that tombstone and is retryable; the registry entry is deleted only after all cleanup stages succeed.

## Registration and enablement

- Plugin IDs are stable, namespaced identifiers. A duplicate registration with identical metadata is idempotent; a conflicting duplicate is rejected.
- Registration records `REGISTERING` before invoking plugin code, so interrupted work is detectable after process restart.
- The plugin manager is the sole source of lifecycle state; a plugin cannot self-enable or route actions directly.
- Enablement moves through `ENABLING`, invokes the plugin hook, then resumes runtime dispatch. Any failure quiesces and drains the runtime, revokes capabilities, and leaves a `FAILED` record.
- Disabled or transitional plugins cannot receive new actions/events.

## Orderly uninstall/revocation

The v1 ordering is:

1. Persist `UNINSTALLING` so policy/router blocks new work.
2. Quiesce incoming actions and events.
3. Invoke the plugin disable hook.
4. Cancel and drain plugin-owned jobs/callbacks.
5. Revoke all capability grants/tokens.
6. Invoke the uninstall hook without new grants.
7. Detach event subscriptions and runtime registrations.
8. Erase plugin-owned workspace data, caches, secrets, and namespaced files.
9. Remove installed plugin artifacts.
10. Delete the plugin registry entry last.

Cleanup is best-effort across all stages. Failures are aggregated; the record stays `UNINSTALLING`, the plugin remains blocked, and the user can retry. Cleanup methods and hooks must be idempotent. The host must not claim uninstall completed while any stage failed.

“Zero residue” is scoped to CordiAct-controlled resources: capability grants, active jobs/subscriptions, plugin-owned storage, credentials, package artifacts, registry/index entries. External side effects (messages already sent, remote server records, files explicitly exported elsewhere) cannot be silently reversed; remote plugins need an explicit remote-revocation/retention contract and user-visible outcome.

## Protected system plugins

`BUNDLED_SYSTEM` origin is assigned and verified by the native host/signed app metadata. A plugin cannot make itself protected by self-declaring a manifest flag. Uninstall is rejected before any hooks or cleanup run for protected plugins. Updates are a separate trusted operation; non-removable does not mean immutable.

## Persistence and crash recovery requirements

The current core API uses a `PluginRecordStore` port. Before production use, Android must provide a durable transactional store and invoke recovery at startup:

- `REGISTERING`: fail closed; run safe rollback or mark `FAILED` for repair.
- `ENABLING` / `DISABLING`: quiesce, drain, revoke transient capabilities, and normalize to `DISABLED` or `FAILED`.
- `UNINSTALLING`: resume idempotent cleanup and retain the tombstone until all steps pass.
- `FAILED`: do not route actions; expose retry/repair or uninstall.

A future schema should record completed cleanup stage IDs for large plugins, but must still recheck idempotently because crashes can happen between a side effect and its checkpoint.

## Implementation boundary

`PluginLifecycleManager` in `core` supplies a JVM-testable coordinator and ports. It is not yet the Android persistence/runtime implementation. Platform adapters must enforce workspace ownership, safe path deletion, encrypted-secret removal, action cancellation, signature verification, and atomic registry updates. Do not load third-party native code into the trusted system-plugin path without a separate trust review.

## First implementation milestone

The current `core` implementation provides a lifecycle coordinator with registration, enable/disable transitions, host-verified system-plugin provenance, fail-closed partial activation, scoped capability suspension/revocation, uninstall tombstones, ordered cleanup ports, and startup retry for pending uninstall tombstones. JVM tests use fakes to check ordering and retry behavior.

This coordinator is not yet production persistence: `PluginRecordStore`, `PluginRuntimeResources`, `PluginCapabilities`, and `PluginOwnedData` still require Android implementations. The host must persist cleanup progress transactionally and own a resource ledger for jobs, subscriptions, secrets, and files; plugin hooks alone cannot guarantee cleanup after process death. Remote uninstall additionally needs a Gateway revocation/retention response, and cannot reverse external side effects already completed.

Registration failure is also transactional from the host's perspective: a failed user-plugin registration is immediately rolled back through the uninstall cleanup pipeline; if rollback is incomplete, an `UNINSTALLING` tombstone stays blocked and retryable. A failed bundled system plugin remains in the signed APK but is quiesced, drained, detached, and marked `FAILED` for repair/update.

## Android host adapters added

The Android `app` module now provides:

- `AndroidPluginLifecycleStore`: SQLite-backed transactional lifecycle records and user-consent rows. State updates use in-place updates (not SQLite `REPLACE`, which would delete foreign-key children). Capability consent is cleared when a plugin version changes or drops a requested capability. Removing the plugin row cascades its grants.
- `AndroidPluginRuntimeResources`: a host-owned registry of runtime controllers for quiesce/resume/drain/detach; detach removes a binding only after successful cleanup so retries remain possible.
- `AndroidPluginStorage`: app-private, per-plugin data/cache roots and a separate no-backup artifact root. IDs are validated, paths are canonicalized, symlinks are deleted as links rather than traversed, and uninstall only touches the named plugin's directories.
- `AndroidPluginLifecycle`: composition root, wired into `CordiActApplication`; startup recovery runs before Activity/plugin dispatch. The current bootstrap has no trusted bundled plugin implementations, so the system-plugin verifier is intentionally closed until an APK-signed catalog is added.

Robolectric integration tests cover SQLite persistence/cascade behavior, version-bound consent reset, recovery after process restart, ordered uninstall through the Android adapters, isolation from sibling plugin data, traversal rejection, symlink-safe deletion, and protected system-plugin rejection.

The persistence layer stores CordiAct consent only. Android OS runtime permissions remain separate and must be checked at each native capability call. Remote Harness-side uninstall/revocation is not yet implemented; it needs a Gateway acknowledgement/retention contract and must not report success while unreachable.

Android runtime `stopAndDrain` now also drains the durable host resource ledger. Thus disabling a plugin cancels tracked host resources (for example scheduled work/observers) as well as in-process jobs, and suspends live consent while retaining the user's consent choice for a later explicit re-enable. A resource kind without a trusted registered cleaner deliberately blocks disable/uninstall and remains in the ledger for retry.

Current implementation boundaries: the app bootstrap has no bundled plugin implementation/catalog yet, so system-plugin admission is fail-closed. The persistent external-resource ledger and trusted cleaner SPI are implemented, but no Android `WorkManager`/alarm/observer producers exist yet; adding any such capability must register its trusted cleaner and persist ownership before creating the side effect. Workspace-wide rows are not yet owner-tagged; the current zero-residue guarantee covers CordiAct plugin-owned app data/cache, consent, tracked host resources, runtime bindings, and plugin artifacts—not arbitrary records in future shared workspace databases. The database has an explicit migration boundary; schema upgrades must add non-destructive migrations before incrementing its version.

## First bundled conversation system plugins

- `core.model-adapter` exposes provider-neutral model discovery and streaming generation. It routes by `ModelId`, rejects missing providers/models, enforces backend ownership during detach, and cancels in-flight requests when disabled/uninstalled. Provider-specific implementations are separate plugin adapters and are not yet bundled/configured.
- `core.session-manager` exposes workspace-scoped session and message APIs. The Android SQLite session store persists sessions/messages with foreign-key cleanup and transactional message/timestamp updates.
- The app-owned catalog trusts only the exact bundled IDs, version `0.1.0`, Android target, and declared capability set. Startup registers and enables both through `PluginLifecycleManager` after crash recovery; neither can be uninstalled through the ordinary plugin path.

This is the conversation service foundation, not yet a complete chat screen/provider connection. Sending a message end-to-end requires a configured provider plugin and UI orchestration.

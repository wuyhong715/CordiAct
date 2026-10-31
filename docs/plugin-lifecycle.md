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

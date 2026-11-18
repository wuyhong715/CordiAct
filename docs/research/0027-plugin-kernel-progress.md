# Plugin kernel implementation — 2026-11-17

## Implemented

- `core/plugin/PluginScope.kt`: reverse-order disposal, aggregate failures, retained retryable resources, late-acquisition cleanup, child scopes, idempotent effects.
- `PluginKernel.kt`: typed service keys; plugin/workspace/session instance identity; exact namespace visibility (NO implicit parent fallback); required-service waiting; dependency-first startup and reverse dependency teardown; restart affected closure; shutdown; status snapshots.
- Startup failure withdraws published services and rolls back effects. Incomplete cleanup blocks new startup until explicit cleanup. Conflicting providers cannot replace another owner.
- `ContextManagedPlugin.kt`: opt-in durable lifecycle bridge for dependency-free trusted plugins. Dependency-bearing plugins are supported: durable ENABLED now explicitly represents enable intent; runtimeStatus projects ACTIVE/WAITING/FAILED separately. Failed enable cancels desired runtime state.
- `PluginContributions.kt`: global tool contributions with owner validation and per-registration executor tokens. Existing execution authorization remains untouched. Workspace/session contributions explicitly rejected until registries support scoped entries.
- `interaction/PluginCommandContributions.kt`: global command contributions, registry-issued generation identity, atomic alias withdrawal, owner validation, no implicit owner enablement. Registry snapshots caller sets and rejects duplicate IDs.
- Shared runtime in `BundledConversationPluginCatalog`; Search publishes SearchService; Interaction publishes InteractionService and command registry. These remain dependency-free lifecycle roots.
- MCP connection and bridge resources now scope-owned. Tool withdrawal proceeds across individual failures; failed registrations remain tracked for retry. Cleanup errors no longer silently swallowed.
- DeepSeekBackendPlugin uses scope-owned cancellation and detach, only acquiring cleanup ownership after successful attach.

## Verification

Use isolated JVM settings at `/workspace/.verify-integration` (includes core, interaction, mcp-client and model-providers; avoids Android/AIDL setup). Targeted tests cover dependency recovery/isolation, rollback, duplicate providers, retries, closure restart, shutdown, child effects, bridge startup failure, tool/command withdrawal and stale generation protection. App Kotlin-only compile: `sh /workspace/spike/local_compile.sh --offline`. No local APK build.

## Explicit boundaries / next design gate

This is trusted in-process infrastructure, NOT a security sandbox, Node/npm compatibility or a Cordis JS runtime. No new external-code installer, no JS engine selected, no permission expansion.

The lifecycle retains existing persisted ENABLED as desired enable intent (no database schema/enum migration). RuntimeAwarePlugin supplies live runtimeStatus through manager get/all and Android inventory/action results. Runtime status is NOT persisted; reconstruction replays desired enable and computes dependencies afresh. Inventory suppresses tool listings for non-ACTIVE instances; action reasons explain WAITING/FAILED. Explicit disable withdraws desire so later provider recovery cannot reactivate the consumer. UI presentation of these added fields is not implemented in this backend slice.

Scoped tool/command registries, versioned external SDK/distribution, arbitrary UI contributions, automatic config hot-reload and async hook scheduling remain outside this slice. Hooks are synchronous/serialized and must not recursively mutate the kernel or wait on another thread doing so. Resources are runtime-only; durable reconstruction still uses existing plugin factories.

No commit/push/CI release or device install performed in this slice.

## 2026-11-18 lifecycle follow-up

Removed the temporary requiredServices bridge rejection. Added recovery test spanning durable wait, provider availability/loss, new manager/kernel reconstruction, resumed provider and explicit disable. ContextManagedPlugin registration flag is volatile for live status reads. Kept existing inventory constructor positional arguments compatible by appending runtimeStatus.

Verification after follow-up: focused core/interaction tests and App Kotlin-only compile passed. A cross-module nullable smart-cast compile error was corrected with let before the successful compile. No actual process-kill/device restart test, no UI changes, no APK build or publication.

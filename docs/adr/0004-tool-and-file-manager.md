# ADR 0004: Tool Manager and Workspace File Manager

- Status: Accepted for this implementation slice
- Date: 2026-11-01
- Owners: CordiAct core / Android host
- Depends on: [ADR 0003 — Workspace-first design](0003-workspace-first-design.md)

## Context

CordiAct now has a protected Workspace system plugin and a plugin lifecycle manager. The next backend capabilities are tool management and workspace file access. Reference implementations show useful pieces but not a directly reusable design: DeepSeek Harness separates its runtime tool registry from plugin bundle/profile management; ETA and the Android Harness separate tool declarations, runtime capability gates, and file backends.

CordiAct must preserve its product boundary: Workspace is the first-class owner; there is no Assistant entity. Conversations, tool preferences, and files are scoped to a persisted Workspace. The backend must close its own loop before provider or UI integration.

## Decision Summary

1. Add two protected, bundled system plugins: `core.tool-manager` and `core.file-manager`.
2. Tool Manager owns a static, host-approved catalog and durable workspace-level enabled/disabled overrides. It does not load, install, or execute user-provided code.
3. A tool is callable only after dispatch-time validation of registration, workspace existence/non-archived state, workspace preference, and owning plugin lifecycle state. Catalog visibility is not authorization.
4. File Manager owns private files rooted under the validated active Workspace. It exposes typed operations through a backend port. It does not expose arbitrary absolute paths, shared storage, SAF, shell commands, or cross-workspace access.
5. Every write validates its workspace and path at use time, enforces size bounds, rejects symlink escapes, and uses same-directory temporary files plus atomic replacement where supported. Conflict-aware writes use a SHA-256 revision precondition.
6. Workspace preferences use a new table in the existing shared Workspace/session SQLite database and are migrated transactionally. User files live under Workspace roots, never in the database or plugin-owned storage.
7. Tool Manager disable is a workspace-level deny switch, not the generic plugin lifecycle disable operation. Protected system plugins remain managed only by the host lifecycle.

## Goals

- Provide stable core contracts for listing, enabling, disabling and resolving tools per Workspace.
- Ensure all tool calls pass one fail-closed dispatch policy before reaching an executor.
- Provide a bounded, testable workspace file API usable by a future model/runtime adapter.
- Keep policy in `core`, Android persistence and filesystem mechanics in `app`.
- Keep the current CI-only APK packaging policy unchanged.

## Non-goals

- Dynamic tool/plugin package installation, code loading, scripting, MCP, or network tools.
- Model provider integration, prompt construction, conversation UI, tool settings UI, or user consent UI.
- Access to shared storage, document-picker URI persistence, media stores, or arbitrary filesystem paths.
- Cross-workspace file access, permanent Workspace deletion, automatic cleanup of Workspace data, or an Assistant profile.
- General-purpose shell, executable-file launching, archive extraction, symlink creation, or recursive deletion.

## System Boundaries and Dependencies

`WorkspaceManagerService` is the authority for workspace existence and private root resolution. `PluginLifecycleManager` remains the authority for plugin state and runtime admission. Tool Manager composes with both; it must not cache an authorization decision across calls. File Manager uses the same Workspace authority for every operation and an injectable file backend for filesystem behavior.

Dependency graph:

- Workspace Manager -> owns Workspace IDs, archive state, root resolution.
- Tool Manager -> depends on Workspace Manager; reads plugin lifecycle state for each invocation.
- File Manager -> depends on Workspace Manager; provides only file operations within one resolved Workspace root.
- Future conversation/model runtime -> asks Tool Manager for available definitions and invokes tools through its guarded dispatch API; it receives a File Manager facade bound to the request workspace when required.

The Tool Manager and File Manager do not depend on each other. Tool metadata may declare an owning plugin, but that does not grant filesystem access. A tool requiring files must be explicitly bound to the File Manager API and workspace identity.

## Core Model

### Tool definition

A host-defined `ToolDefinition` has:

- stable validated `id` (lowercase ASCII ID), `name`, `description`, and schema/version metadata;
- `ownerPluginId` naming a registered plugin;
- required capability identifiers (empty by default);
- a typed executor registration stored in memory by the host, never deserialized from disk;
- default-enabled policy, restricted to trusted host registration.

Definition metadata may be exposed to a future model adapter. Executor implementation and host-only policy must not be serialized into the database or returned as model-visible JSON.

### Tool preference

`ToolPreference` is a tri-state workspace override: `INHERIT`, `ENABLED`, or `DISABLED`. `INHERIT` resolves to the host definition's default. Unknown tool IDs are rejected; they are not persisted as forward-compatible arbitrary names. Tool enablement is per Workspace and never follows model selection or another Workspace.

### Invocation context

Every invocation carries a `workspaceId`, tool ID, typed argument value, and request/cancellation context. The dispatcher re-reads current definitions and state, validates the Workspace is active (not archived), checks the Tool Manager protected plugin is enabled, checks preference, checks the owner plugin exists and is `ENABLED`, verifies declared capabilities using the host policy, then calls the registered executor. Missing dependencies, stale IDs, malformed input, disabled state, or revoked capabilities fail closed before executor invocation.

## Core APIs

Core API names may follow repository conventions but must preserve the following contract:

- `ToolManagerService.list(workspaceId): List<ToolDescriptor>` returns catalog metadata and effective enabled state; it validates the workspace and excludes executors/secrets.
- `ToolManagerService.setEnabled(workspaceId, toolId, enabled): ToolDescriptor` writes a tri-state override (setting to the host default may normalize to `INHERIT`). It rejects unknown tools and archived/nonexistent workspaces.
- `ToolManagerService.resolveEnabled(workspaceId): List<ToolDescriptor>` returns currently dispatchable descriptors after lifecycle/capability checks. It is advisory for composition only; dispatch revalidates.
- `ToolDispatcher.invoke(workspaceId, toolId, arguments, requestContext): ToolResult` repeats every authorization check and then invokes the registered executor.
- `ToolDefinitionRegistry.register(definition, executor)` accepts only host registrations, rejects duplicate IDs, invalid schemas, missing owner plugin IDs, or invalid capability IDs; registrations are process-local and recreated on startup.

The initial slice may provide a small built-in registry with no production tools, but includes a deterministic test executor. It must not fabricate UI/provider behavior. Catalog descriptions and schemas are immutable after registration for the lifetime of the host process.

### File Manager API

A typed `WorkspaceFileService` is workspace-bound per operation and provides:

- `list(workspaceId, relativeDirectory, cursor?, limit?)`;
- `read(workspaceId, relativePath, offset?, maxBytes?)`, returning bytes/text metadata and a SHA-256 revision;
- `write(workspaceId, relativePath, content, expectedRevision?)`, creating or replacing a regular file and returning the new revision;
- `delete(workspaceId, relativePath, expectedRevision?)`, limited to one regular file (no recursive directory delete);
- `move(workspaceId, sourceRelativePath, destinationRelativePath, expectedRevision?)`, confined to the same Workspace and refusing overwrite unless explicitly supported by a preconditioned API.

Keep request and result types in `core`, with byte arrays/UTF-8 handled explicitly. Initial implementation limits: path <= 4096 UTF-8 bytes, one file <= 10 MiB, one read <= 1 MiB, directory page <= 500 entries, relative path depth <= 64, and one write call <= 10 MiB. Reject NUL, absolute paths, empty/root target for mutations, `.`/`..` segments, backslash separators, invalid UTF-8 where text is expected, and unsupported file kinds. Listing may return an opaque next cursor bound to the requested Workspace/path; clients must not be able to use it to traverse elsewhere.

### File backend

`WorkspaceFileBackend` is an injectable core port implemented by Android app-private storage. The port should expose typed operations rather than a raw `File` capability, so callers cannot bypass checks. The host obtains the root only through `WorkspaceManagerService.resolveRoot(workspaceId)` for each operation.

The Android backend must:

- canonicalize root and resolved parent/target and verify strict containment with path-component semantics, not string-prefix matching;
- reject symbolic links at every existing path component and verify canonical containment immediately before mutation;
- create parents only after proving the parent remains under root; never follow a dangling or external link;
- write a same-directory temporary file with exclusive creation, flush/sync it, then atomic move/replace; clean only its own temp file on failure;
- calculate revisions as lowercase hex SHA-256 of file bytes; enforce `expectedRevision` before destructive replacement/deletion/move and report conflict rather than overwriting stale content;
- avoid exposing absolute device paths in results/errors; avoid logging file contents;
- never chmod broadly, execute files, follow symlinks, recurse into directories for destructive operations, or touch files outside this Workspace.

Operations are synchronous in this slice because existing core ports are synchronous; the future runtime adapter must dispatch them off the main thread. A later coroutine migration is out of scope.

## Persistence and Migration

Use the existing `AndroidConversationSessionStore` SQLite database (currently schema version 2), because it already owns Workspace identity. Increase its schema version to 3. Add a table similar to:

`workspace_tool_preferences(workspace_id TEXT NOT NULL, tool_id TEXT NOT NULL, enabled INTEGER NOT NULL CHECK(enabled IN (0,1)), updated_at INTEGER NOT NULL, PRIMARY KEY(workspace_id, tool_id), FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE)`

Absence of a row means `INHERIT`; `enabled` stores explicit true/false only. Add a store port in core and implement it using the same SQLiteOpenHelper. Migration v2->v3 creates the table inside a transaction. Fresh installs create it in `onCreate`. Preserve v1->v2 behavior and all existing rows/foreign keys. Do not persist tool definitions, executable handlers, external URI grants, file contents, or per-call results.

Workspace files remain in app-private directories under the existing workspace root layout. Archiving a Workspace prevents new tool/file operations through normal validation but preserves its files and preferences for restore. No permanent delete operation is added.

## System Plugin Integration

Add stable specs `core.tool-manager` and `core.file-manager`, both Android native, protected and verified through `BundledConversationPluginCatalog`. Tool Manager depends on `core.workspace-manager`; File Manager depends on `core.workspace-manager`. They may be constructed with shared Workspace and SQLite services in `BundledConversationPlugins` and registered through the existing host lifecycle path.

These plugins must not be marked non-disableable by relying solely on static `PluginSpec`; their protections must be explicit in the host's bundled allowlist and UI/API policy. The app must register and enable them after Workspace Manager, and before any future dispatch adapter is exposed. Failed registration leaves capabilities unavailable; startup continues fail-closed, matching existing plugin host handling.

Disabling either system plugin via generic lifecycle is host-controlled; normal user-level tool disable only writes a workspace preference. Tool Manager cannot disable itself or Workspace Manager. File Manager is a service plugin, not an API that mutates its own lifecycle state.

## Errors and Failure Semantics

Use typed errors or stable error codes for `WORKSPACE_NOT_FOUND`, `WORKSPACE_ARCHIVED`, `TOOL_NOT_REGISTERED`, `TOOL_DISABLED`, `OWNER_PLUGIN_UNAVAILABLE`, `CAPABILITY_DENIED`, `INVALID_ARGUMENT`, `PATH_OUTSIDE_WORKSPACE`, `SYMLINK_NOT_ALLOWED`, `FILE_NOT_FOUND`, `NOT_A_REGULAR_FILE`, `SIZE_LIMIT_EXCEEDED`, `REVISION_CONFLICT`, and `STORAGE_FAILURE`. Do not return partial success for a failed write/move. A denied call must not invoke the executor or mutate files. Avoid leaking host absolute paths, database details, or file content through error strings.

## Threat Model

Treat model-produced tool IDs, JSON arguments, paths and cursors as untrusted. Also consider stale Workspace IDs, archived workspaces, plugin disable/revocation during a multi-step request, malicious symlink placement, path traversal, oversized reads/writes/listings, duplicate tool registration, storage failure, interrupted writes, and preference persistence failure.

In-process filesystem TOCTOU cannot be eliminated perfectly with `java.io.File`; reduce exposure by rejecting symlinks, resolving immediately before use, using same-directory temp files and atomic moves, and keeping the root private to the app. If later features permit untrusted concurrent local writers or shared storage, replace this backend with a stronger descriptor-relative API rather than weakening checks.

## Verification and Acceptance Criteria

Core tests must cover:

- stable tool ID/schema/owner validation and duplicate registration rejection;
- default/inherit, enable, disable, per-workspace isolation, unknown tool rejection, archived workspace rejection;
- enabled catalog does not imply dispatch; disabled Tool Manager, disabled/missing owner plugin, missing capability and archived workspace all prevent executor execution;
- state changes between list/resolve and invoke are caught at invoke time;
- plugin lifecycle dependency prevents disabling Workspace Manager while managers depend on it.

Android persistence tests must cover fresh schema v3, v2->v3 migration, preservation of workspace/session/message rows, preference round-trip, default absence/inherit, and foreign-key cascade behavior on explicitly supported database cleanup only.

File tests must cover create/read/list/write/delete/move; traversal and absolute path rejection; symlink escapes and symlink-parent rejection; archived/missing workspace rejection; revision conflict; size/page/path/depth limits; atomic-write failure preserving original file; and no cross-workspace reads/writes. Use temporary roots and deterministic fault injection, not device shared storage.

Verification: run `./gradlew :core:test` and app unit tests when dependencies resolve; run full CI after push. Do not report success if AGP/dependency resolution blocks tests. No local APK packaging.

## Rollout Sequence

1. Land this design artifact and core typed contracts/tests.
2. Implement Tool Manager service, preferences, dispatch gate and system plugin.
3. Implement File Manager service, path guard, backend and unit tests.
4. Implement SQLite v3 migration and Android backend/persistence integration tests.
5. Register both plugins in the protected host catalog and startup order; verify dependency/lifecycle failure handling.
6. Run core and app tests; record failures. Keep UI/provider integration for a later slice after this backend is self-consistent.

## Open Implementation Details

- Exact package/file names may follow current repository organization.
- If existing plugin dependency rules require a slightly different host startup order, preserve the invariant that Workspace is enabled before managers and managers before dispatch.
- SAF import/export, user-facing consent, UI, and provider wire formats require separate design and are intentionally excluded.

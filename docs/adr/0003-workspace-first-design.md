# ADR 0003: Workspace-First Product and DeepSeek Harness Integration

- Status: Proposed
- Date: 2026-11-01
- Scope: Design only; this ADR does not authorize implementation.

## Context

CordiAct has no Assistant entity. Workspace is the primary product entity. Conversation records, workspace files, and agent configuration belong to a Workspace, not to a selected model. A model/provider is an execution choice and must not select, clone, move, or redefine Workspace-owned data.

The application already has protected bundled conversation plugins and a session store. `ConversationSession` contains a `workspaceId`, but Android persistence currently stores it as an unconstrained string. There is no Workspace registry/table, Workspace system plugin, Workspace selection flow, or database foreign key validating that a referenced Workspace exists. `MainActivity` is still a placeholder.

DeepSeek Harness (DSH) is a host-side agent runtime with remote Gateway/client integration. Its Workspace subsystem describes a durable record around a stable Workspace ID, canonical working-directory path, display title, and ordered Session membership. Membership is reconciled against persisted Session headers and their `cwd`. The subsystem is host-side and optional to the agent-loop spine; its startup depends on storage-domain and session-persistence facilities, and it must not mistake an unavailable persistence peer for an empty registry. DSH's WorkspaceController exposes operations over its registry projection (for example default initialization, ordering, and archiving); it is not equivalent to a generic Android local-filesystem CRUD API.

Sources reviewed 2026-11-01:

- DSH Workspace subsystem: <https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/workspace.md>
- DSH WorkspaceController: <https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/api/workspace-controller/src/index.ts>
- DSH model selection: <https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/ui-model-selection/README.md>
- CordiAct Android conversation persistence: `app/src/main/java/com/cordiact/app/conversation/AndroidConversationSessionStore.kt`
- CordiAct conversation contracts: `core/src/main/kotlin/com/cordiact/core/conversation/ConversationContracts.kt`
- CordiAct Harness adapter contract: `deepseek-harness/src/main/kotlin/com/cordiact/harness/deepseek/DeepSeekHarnessAdapter.kt`
- Existing Android/DSH boundary decision: `docs/adr/0001-android-dsh-runtime-boundaries.md`

## Decision

1. Workspace is a protected, bundled system plugin and the root of product data ownership. It is not a model preset, Assistant, or disposable conversation grouping.
2. Every Session must reference one existing Workspace through a non-null foreign-key relationship. A session cannot be created, loaded, moved, or resumed by passing an arbitrary workspace ID.
3. Workspace files and agent configuration are Workspace-scoped. A model/provider change changes only the execution selection for a request or Session; it never changes Workspace identity, file root, agent configuration, or conversation ownership.
4. CordiAct's on-device Workspace identity/registry is distinct from a DSH Host Workspace. When DSH is used, create an explicit per-Workspace, per-Harness-host execution binding. A remote `cwd`/DSH workspace ID is a mapped execution location, never the local Workspace primary key.
5. DSH runs behind a versioned remote Harness Gateway adapter. Do not port the Node host into Android, call undocumented endpoints, or make a provider's ordinary chat/completions endpoint a substitute for Harness. Keep the current `DeepSeekHarnessAdapter.submit(ActionRequest)` action-submission seam distinct from a future conversation stream/client seam.
6. Workspace initialization is a startup dependency of Session Manager and all workspace-scoped UI. If Workspace persistence is unavailable or recovery is incomplete, fail closed; do not render an empty workspace list or permit unscoped conversations.

## Product and data model

### Workspace

Minimum durable fields:

- `workspaceId`: stable opaque UUID, immutable and never derived from model, provider, display name, or path.
- `name`: user-visible title; rename does not change identity.
- `createdAt`, `updatedAt`, `archivedAt` (nullable), and a monotonic revision for conflict detection.
- `storageKind` and a root handle. The initial Android implementation should use an app-private per-workspace directory. An optional user-selected Storage Access Framework tree may be added later with persisted URI permission; do not store transient content URIs as if they were filesystem paths.
- Workspace-owned agent configuration documents/revisions and Workspace file metadata.
- Optional remote execution bindings, keyed by a stable Harness-host identity, with remote Workspace ID/canonical remote working directory and sync state. Credentials are referenced from protected credential storage, never embedded in Workspace JSON or files.

### Session, messages, and workspace documents

- `ConversationSession.workspaceId` is `NOT NULL REFERENCES workspaces(workspace_id)`. Keep it immutable in v1; moving a session is a separate explicit migration operation, not a side effect of selecting a model.
- Messages and attachments inherit workspace ownership through their parent session. Queries and mutations must always include the active workspace/session scope.
- Session metadata may store a selected provider/model identifier, but it cannot own or redirect the Workspace root or Workspace agent documents.
- Agent configuration belongs to a Workspace and is versioned. Each Session should pin the configuration revision (or record an explicit “follow workspace latest” policy) so an edit cannot silently rewrite the meaning of existing conversations.
- Treat agent configuration as data, not executable code. Any future executable/plugin manifest still goes through the verifier, capability policy, and sandbox boundary.

## Workspace system plugin boundary

Provide a minimal protected `WorkspaceManagerService` through the existing system-plugin lifecycle, with operations conceptually equivalent to:

- `initializeDefaultIfEligible`, `create`, `get`, `list(includeArchived)`, `rename`, `archive`, and `restore`.
- `resolveExisting(workspaceId)` for Session Manager; failure is explicit (`NotFound`, `Archived`, `StorageUnavailable`, `PermissionLost`), never an implicit create.
- Workspace-scoped file/config handles such as `openRead`, `writeAtomically`, `list`, and `delete`. Handles carry Workspace identity and capability scope; do not expose arbitrary absolute paths to plugins.
- `bindRemoteExecution(workspaceId, hostId, remoteWorkspaceId, remoteWorkingDirectory)` and state inspection as a later Harness integration capability. It must not silently sync or overwrite files.

The system plugin is non-uninstallable and cannot be disabled while dependent Sessions/plugins exist. Lifecycle recovery must restore/validate the Workspace registry before Session Manager is registered and enabled. Startup dependency failure leaves dependent plugins pending/blocked with a visible repair state.

Workspace deletion is not a normal v1 action. Archive first. If permanent deletion is later offered, require explicit confirmation, show affected conversations/files, define export/retention behavior, and perform a transactional cascade or tombstone; never orphan records.

## DeepSeek Harness integration plan

### Separate ownership from execution

Keep the on-device Workspace record authoritative for CordiAct identity, local files, local agent documents, and Session ownership. A DSH Host has its own directory and Workspace projection. Store an explicit mapping from `(local workspaceId, hostId)` to `(remote workspaceId, canonical remote cwd)`; do not equate IDs or infer a mapping from a selected model.

The Gateway adapter is responsible for host authentication, protocol/version negotiation, remote Session creation/resume, event streaming, cancellation, reconnect/resume semantics, and remote capability/error mapping. Conversation-generation transport and Harness action submission are separate APIs. A Workspace may be used with multiple providers or Harness hosts without cloning its local records.

### File synchronization is an explicit design gate

DSH agent tools execute against the Host's filesystem; Android SAF/app-private files are not automatically visible to that Host. Before enabling tools that read/write Workspace files, choose and test a sync contract:

- **Recommended first integration:** explicit import/upload into a per-workspace remote execution directory, and explicit download/export of changed files. Track file revisions/hashes and report conflicts. Only the user-approved file subset is mirrored.
- Do not claim transparent local/remote filesystem identity. Do not silently use an entire phone directory as the remote `cwd`.
- A true live bidirectional sync engine is out of v1 scope until conflict semantics, deletion propagation, offline behavior, quotas, and capability boundaries are specified.

Agent configuration documents should be Workspace-owned on-device data. If DSH requires equivalent instructions on the Host, upload a versioned, read-only execution snapshot through the same explicit sync boundary. Model switching must not regenerate or relocate that snapshot; a deliberate Workspace config revision does.

### Model selection

Use DSH's exact provider ID and catalog model ID where supported. Model selection is session/request execution state (or an explicit Workspace default only if product policy later requires one), not Workspace identity. Persist credential references in a protected credential store, not in Workspace files, conversation rows, remote cwd, logs, or exported agent config. An unavailable model fails that request without moving the Session or changing Workspace-owned configuration.

## Android storage and security constraints

- Initial root: `noBackupFilesDir` or another app-private durable root, partitioned by opaque Workspace ID. Export/import is explicit. Decide backup policy before claiming workspace portability.
- SAF support is a later storage-provider implementation. Persist URI grants, detect revocation, and surface repair; never fall back to a different folder silently.
- Canonicalize filesystem paths and reject traversal; use atomic temp-write + rename, bounded file sizes, and explicit quotas. For SAF, enforce document-tree ancestry via granted URI handles rather than string path-prefix tests.
- Scope every repository/file query by Workspace ID; add cross-workspace denial tests. Never trust a caller-supplied workspace ID without resolving it through the manager.
- Remote host IDs, remote cwd paths, and credentials are separate fields with validation. Do not put secrets in logs or in agent config files.

## Migration and bootstrap

The current conversation database has sessions with a string `workspace_id` but no Workspace table/FK. Ship a versioned database migration before any Workspace-first UI:

1. Create the Workspace registry and per-workspace storage metadata.
2. Create or recover one deterministic migration/default Workspace only through an explicit one-time migration marker.
3. Attach existing sessions to that Workspace only when the old value is the known legacy default. Unknown IDs must be quarantined/reported for repair, not silently merged.
4. Rebuild the sessions table with the Workspace foreign key while preserving session/message IDs, order, timestamps, and message bodies; run SQLite integrity checks in tests.
5. Bootstrap/recover the Workspace system plugin before Session Manager. If the migration or storage is unavailable, block conversation routing and surface a repair state rather than treating the database as empty.

Default Workspace creation should be idempotent and separate from Session/message creation. A first-run Workspace is not an implicit Assistant and contains no model-specific preset.

## Phased implementation and gates

### W0: product/data contract

Approve this ADR, decide default Workspace first-run UX, archive/delete policy, backup/export policy, and initial app-private storage root. Freeze IDs and schema ownership before UI work.

### W1: protected Workspace plugin and persistence

Implement core contracts, Android Workspace registry/storage provider, lifecycle registration/recovery, migration, and tests. No conversation UI or model provider integration in this phase.

Acceptance: create/list/rename/archive/restore survives process restart; default initialization is idempotent; invalid/archived IDs are rejected; plugin disable/uninstall is blocked; migration preserves all messages; unavailable persistence blocks dependent plugins.

### W2: bind Session Manager to Workspace

Replace arbitrary string-only workspace association with validated manager/repository operations and a real FK. Implement workspace-scoped session/message queries and cross-workspace denial tests.

Acceptance: no session without an existing active Workspace; session's workspace never changes when a model is selected; restart/recovery returns only sessions belonging to the requested Workspace.

### W3: Workspace-first native UX

Build Workspace chooser/list/create/rename/archive and Workspace home before the conversation view. The home can then show that Workspace's conversations, files, and agent configuration. No Assistant entity or assistant picker is introduced.

### W4: Workspace files and agent config

Implement scoped file handles, versioned config documents, import/export, and explicit trust treatment. Test traversal, URI permission loss, atomic-write interruption, quota, and cross-workspace isolation.

### W5: DSH Gateway execution binding

Pin a supported DSH/Gateway protocol version, implement handshake/auth/reconnect/cancel/stream tests, create explicit local-to-remote Workspace binding, and implement opt-in file snapshot sync. Only then expose Harness-backed generation/actions; action execution remains behind existing policy/confirmation and sandbox boundaries.

## Explicit non-goals for v1

- No Assistant entity, assistant list, or model-specific workspace.
- No automatic Workspace switching, cloning, or file/config relocation when provider/model changes.
- No phone-local Node/DSH Host port.
- No implicit whole-directory upload, opaque two-way sync, or unverified direct protocol calls.
- No production plugin/script execution merely because the Harness Gateway is connected.

## Decisions still needed before W1

- On first launch, auto-create a clearly named default Workspace or require the user to create one before entry?
- Is app-private storage sufficient for v1, or is user-selected SAF storage required from the first release?
- Should Workspace default model be supported, with Session override, or should model selection be session-only in v1?
- Which files may be mirrored to a Harness Host, and is upload/download manual or approval-gated?
- What are the backup/export, archive, and permanent deletion policies?

No implementation is authorized by this proposal. Resolve these product decisions before coding W1.

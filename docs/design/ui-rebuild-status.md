
### Batch 1 — sidebar (commit `22bcbb2`, on `feat/ui-rebuild-quality`)

Scope was restricted to the conversation drawer; the settings page is batch 2. A settings
entry is present in the fixed footer with a batch-2 toast. Session rename/archive is
intentionally not exposed because `SessionManagerService` has no such API.

- New `ConversationSidebar` + `WorkspaceNameEditor` (standalone, no ViewModel dependency),
  `model/SidebarModels.kt` (pure search/sort/name validation).
- ViewModel builds the sidebar from real workspace/session services, keeps only
  `SessionKind.USER`, serialises navigation, and calls the caller's close callback only
  after the selection is committed. Failed create/rename keeps the dialog and its input.
- CI run `42546966606` (build job): 786 unit tests, 0 failures, 0 skipped, including 12
  `ConversationSidebarTest` cases; `cordiact-debug-apk` produced (24,463,044 bytes,
  SHA256 `4170a1b8…f99c4d8a`). Copy for manual testing:
  `/sdcard/Download/Moxw/CordiAct-ui-batch1-sidebar-22bcbb2.apk`.
- Not installed and not exercised on a device by the assistant; device acceptance is the
  user's. No main merge, no release.

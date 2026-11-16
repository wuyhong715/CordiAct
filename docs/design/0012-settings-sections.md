# Secondary settings section language — 2026-11-16

## Implemented slice
Shared SettingsSectionBar: floating sampled frost, moving selected pill, 48dp targets, tab semantics, native text selector for large text/narrow available width. No navigation on short pages.
Host: source remains separate from fixed chrome; measured bottom padding + masked progressive edge. Three independent saveable scroll states. Page composable stays mounted on section changes, preserving policy drafts/results. IME hides navigation. Object editors hide navigation until explicit completion/cancel, preventing loss of non-saveable secret drafts.
- Search: Services(global), Policy(workspace mode/order/enabled/local fetch), Diagnostics(explicit quota-consuming test + counts). Service editing remains supported. Unsaved policy explains why service editing is temporarily blocked; never silently save.
- MCP: Servers(configuration/enable/auth) and Diagnostics(reconnect/tools). Two real sections, not fake workspace-override controls.
- SSH: Hosts and Connection checks. Only existing real test/probe actions, not fictional active tunnel lists.
Short pages unchanged; permissions/security and provider editor remain existing pages. These are not claimed migrated; security approval/discard flow requires independent handling.

## Boundaries
Existing page-level deep links open the default section; section-specific deep link parsing and error badges are not implemented in this slice. Rotation/process recreation does not retain secret input. Switching tabs within an open page retains non-secret draft state; leaving routes follows existing dirty/discard checks. Header scope comes from existing host and per-page labels. No backend or approval policy changes.

## Verify
`sh /workspace/spike/local_compile.sh --console=plain` passed. New component check covers section selection semantics/callback, CI existing app settings tests cover facades. Device appearance, keyboard transition, font scaling and scrolling frame rate still require real-device acceptance.

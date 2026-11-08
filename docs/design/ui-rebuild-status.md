# CordiAct UI rebuild — foreground checkpoint

## Isolation and scope

- Base: `77f8afe`, branch `feat/ui-rebuild-quality`, separate worktree `/workspace/CordiAct-ui-rebuild`.
- Original `/workspace/CordiAct` and its untracked UI/drawables were not edited.
- New `experience/` package is written from scratch. No old UI source or demo state is imported.
- Theme defaults light, uses original Canvas glyphs, avoids content blur and full-height dialogs.

## Implemented first slice

- Edge-to-edge lifecycle-owned Compose root, workspace/session drawer, persisted model selection.
- Three keep-alive pager windows, per-session composer drafts, keyboard/inset handling.
- Conversation turns, Markdown, selectable output, inline reasoning, grouped tool activity.
- Raw 10px history-drag unlock, footer-follow scrolling and explicit jump-to-latest.
- Actual InteractionService submission, serialized provider event channel, call-id tool reducer.
- IO-owned store/plugin calls, per-session event routing and terminal-state rejection of late events.
- One-shot inline approvals; no UI action silently enables workspace-wide trust.
- Compact tool inspector and model picker, capped at 45% of available viewport.
- Real main/tool timeline and causal turn list; latest response source/render preview.
- Markwon 4.6.2 core + table/strike/task extensions; intentionally no remote image/HTML/network plugin.

## Backend prerequisite corrected

`submitWithTools` previously forwarded each provider round's Completed, so UI ended at the
first tool round. It now emits one terminal only after the whole turn and persistence,
sums usage, propagates round failures, and owns a cancellable active-round wait. Added
regressions for multi-round completion, provider failure, and cancellation/late callback.
This is an actual mechanism change, not a prompt workaround.

## Verification commands

- `sh scripts/verify-ui-jvm.sh :core:test :subagent:test :mcp-client:test --offline --console=plain`
- `sh scripts/check-ui-kotlin-local.sh --offline --console=plain`
- `sh scripts/check-ui-test-kotlin-local.sh --offline --console=plain` — TYPECHECK ONLY.
- Android component suite: `./gradlew :app:testDebugUnitTest --tests 'com.cordiact.app.experience.ui.*'`
  on GitHub Actions x86 runner; local execution blocked by x86 AAPT2 on ARM.

The local compile helper temporarily moves AIDL sources and injects Java-only compile stubs,
restoring AIDL on exit. It does not produce an APK or prove Android resources, packaging,
layout, touch, visual fidelity, font-scale behavior, or real-device startup.

## Not yet delivered / do not claim finished

- GitHub Actions APK build, runtime component execution, install/start and screenshot visual review.
- Sub-agent and background job lifecycle lanes, selection-to-tree Gantt hit-testing, telemetry cost.
- Real artifact registry, screenshot/Diff/WebView display and screen-control mapping.
- Full @/#// candidate providers, secret-token editing behavior, slash-command approval routing.
- Provider configuration/editor and complete service/permission settings; model picker uses already
  configured providers and keys, never invented settings.
- Full message Fork/retry menu, history paging and drawer edge-vs-pager gesture tuning. Sheet handles support downward-drag dismissal.
- Streaming tail fade, full spring stretch indicator, reduced-motion support and screenshots/goldens.
- Persisting runtime trajectory metadata across process death (history doesn't fabricate tool spans).

User authorized commit/push to `feat/ui-rebuild-quality` and GitHub Actions on 2026-11-08;
no merge to main, publication, or provider inference call is authorized. Build and runtime
results will be appended below. APK packaging remains CI-only.

## Verified before first push

- JVM: core 511 + model-providers 74 + interaction 16 + subagent 57 + mcp-client 54 + UI reducer 13 = **725 tests, zero failures**.
- Main UI and 7 Robolectric Compose component test sources compiled successfully.
- Local Android resource execution failed at x86 AAPT2; no runtime/visual claim is made.
- Sheets now close with Android Back or downward drag on their handles.
- Safe Markdown also restricts explicitly tapped links to http/https (no intent/file/javascript actions).

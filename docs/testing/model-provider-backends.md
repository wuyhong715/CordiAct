# Model Provider Backend Validation

Date: 2026-11-01

## Scope

This change adds DeepSeek-compatible Chat Completions (existing backend retained), configurable Anthropic Messages SSE, Gemini `streamGenerateContent` SSE and `generateContent` JSON handling, OpenAI Responses SSE, provider metadata persistence, per-provider encrypted API keys, and provider registry lifecycle wiring.

## Test coverage added

- `model-deepseek/src/test/kotlin/com/cordiact/model/deepseek/ConfigurableModelBackendTest.kt`: loopback request/response checks for all four protocol formats, protocol auth headers, Gemini JSON mode, missing credentials, endpoint policy, default DeepSeek model, provider registry add/remove, and cancellation of an open stream.
- `app/src/test/java/com/cordiact/app/conversation/BundledConversationPluginCatalogTest.kt`: protected provider-registry catalog registration and provider metadata persistence without credentials.
- Existing `DeepSeekBackendTest` conversation-turn tests continue to cover user-message persistence, assistant reply write-back on completion, and cancellation delegation.

## Local verification

Command:

```text
./gradlew :model-deepseek:test --console=plain --no-daemon
```

Result: blocked before compilation. Root build configuration could not resolve `com.android.application:com.android.application.gradle.plugin:9.4.1` from Google, Maven Central, or Gradle Plugin Portal. No Kotlin source or test was compiled/executed by this attempt.

The app unit test task was also attempted:

```text
./gradlew :app:testDebugUnitTest --console=plain --no-daemon
```

It failed at the same root AGP plugin resolution step, before project evaluation or compilation. A final rerun of both module and app test tasks on 2026-11-01 produced the same result. `git diff --check` passed during implementation review.

APK builds are not run locally; project policy assigns APK packaging to GitHub Actions.

## Remote verification

- Commit `d5449bfd2a7d591402b3a046fd2170aa11ca1e81` was pushed to `feat/tool-file-system-plugins`; PR #4 targets `main`: https://github.com/wuyhong715/CordiAct/pull/4 (open, not merged).
- CI run `41309023742` passed its `repository-check`; Android build run `41309023685` failed. The next PR head, `86539432b7331fe30a486e687f19cef72682fb9a`, was pushed to record the first run's results and triggered run `41309794058`; its `repository-check` passed, but Android build, API 29, and API 35 jobs failed.
- Workflow logs for run `41309794058` identify the build failure: Kotlin compilation at `ConfigurableModelBackend.kt:233` reported `Unresolved reference 'emitDelta'` and could not infer the lambda type. The API 29/35 jobs failed at `:model-deepseek:compileKotlin` for the same source error before instrumentation tests ran.
- Corrected the Gemini `generateContent` JSON response branch to emit `ModelEvent.Delta` directly. Existing `geminiGenerateContentJsonResponseIsAdaptedWhenEndpointUsesGenerateContent` exercises this path. The fix awaits a fresh CI run; local Gradle cannot resolve AGP 9.4.1, so local tests do not verify it.
- Do not consider the implementation verified until the new head's build and required tests pass.

## Security boundaries

Provider metadata is stored separately from SQLite and contains no key field. API keys are resolved through Android Keystore-backed AES-GCM storage. Missing keys fail before network connection. App provider endpoints require HTTPS. The public config constructors do not expose loopback overrides; loopback HTTP can only be constructed through module-internal test factories, and the app registry rejects test-only endpoints on load/registration. Endpoint userinfo/fragments and credential-like query parameters are rejected. Provider errors do not include response bodies or credentials.

Official API references checked on 2026-11-01 (provider pages do not declare publication dates):

- https://api-docs.deepseek.com/api/create-chat-completion
- https://docs.anthropic.com/en/api/messages-streaming
- https://ai.google.dev/api/generate-content
- https://platform.openai.com/docs/guides/streaming-responses

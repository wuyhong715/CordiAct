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

- Commit `d5449bfd2a7d591402b3a046fd2170aa11ca1e81` was pushed to `feat/tool-file-system-plugins`; remote ref was confirmed to match the local HEAD.
- PR #4: https://github.com/wuyhong715/CordiAct/pull/4 (open, targeting `main`).
- GitHub Actions run `41309023742` (`CI`) completed successfully; its `repository-check` passed.
- Android build run `41309023685` completed with failure. Its `build`, `Android isolated-service proof (API 29)`, and `Android isolated-service proof (API 35)` checks all concluded `failure`.
- Available check annotations only report process/shell exit code 1 and do not include the underlying failure. The authorized token can read check status and annotations, but GitHub's job/run log download endpoints return HTTP 401 for this credential; unauthenticated log access returns 404. Therefore the CI failure cause could not be diagnosed or repaired in this run. Do not treat code or tests as verified by CI.

## Security boundaries

Provider metadata is stored separately from SQLite and contains no key field. API keys are resolved through Android Keystore-backed AES-GCM storage. Missing keys fail before network connection. App provider endpoints require HTTPS. The public config constructors do not expose loopback overrides; loopback HTTP can only be constructed through module-internal test factories, and the app registry rejects test-only endpoints on load/registration. Endpoint userinfo/fragments and credential-like query parameters are rejected. Provider errors do not include response bodies or credentials.

Official API references checked on 2026-11-01 (provider pages do not declare publication dates):

- https://api-docs.deepseek.com/api/create-chat-completion
- https://docs.anthropic.com/en/api/messages-streaming
- https://ai.google.dev/api/generate-content
- https://platform.openai.com/docs/guides/streaming-responses

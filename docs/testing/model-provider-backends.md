# Model Provider Backend Validation

Date: 2026-11-01

## Scope

This change adds DeepSeek-compatible Chat Completions (existing backend retained), configurable Anthropic Messages SSE, Gemini `streamGenerateContent` SSE and `generateContent` JSON handling, OpenAI Responses SSE, provider metadata persistence, per-provider encrypted API keys, and provider registry lifecycle wiring.

## Test coverage added

- `model-deepseek/src/test/kotlin/com/cordiact/model/deepseek/ConfigurableModelBackendTest.kt`: loopback request/response checks for all four protocol formats, protocol auth headers, Gemini JSON mode, missing credentials, endpoint policy, default DeepSeek model, provider registry add/remove, and cancellation of an open stream.
- `app/src/test/java/com/cordiact/app/conversation/BundledConversationPluginCatalogTest.kt`: protected provider-registry catalog registration and provider metadata persistence without credentials.
- Existing `DeepSeekBackendTest` conversation-turn tests continue to cover user-message persistence, assistant reply write-back on completion, and cancellation delegation. The bundled DeepSeek provider is now a `ConfigurableModelBackend` preset (`DeepSeekPreset`); it shares one SSE transport with every other protocol, and the shared backend reports token usage in `ModelEvent.Completed`.

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
- On the fix commit `cb56cb212082f7c746dc2ecc7e14421bad23b28e`, run `41310961774` compiled the sources and ran `:model-deepseek:test`: 13 tests completed, one failed in `streamsEveryProtocolAndUsesItsExpectedRequestAndAuthentication` at `ConfigurableModelBackendTest.kt:173`. The assertion incorrectly compared the literal `test-secret` with the complete `Authorization` value (`Bearer test-secret`); the production auth header was correct. API 29 and API 35 isolated-service proof jobs both passed, and `repository-check` passed.
- Corrected the test case model/assertion to independently specify the authentication header name and value (`Authorization` / `Bearer test-secret`, `x-api-key` / `test-secret`, `x-goog-api-key` / `test-secret`).
- On PR head `bedc1d97694df917edef3e9eb0ff87486fdd73ac`, GitHub Actions CI run `41312606783` passed; Android build run `41312606740` passed `Build and test`, uploaded `cordiact-debug-apk` and `cordiact-device-validation-apks`, and passed isolated-service proof on API 29 and API 35. Each platform report shows 16 `IsolatedServiceSandboxRuntimeTest` + 4 `SandboxJsPocServiceTest` cases, with zero failures/errors/skips. The build job includes the repository Gradle test suite, including the corrected model backend tests.
- Local Gradle still cannot resolve AGP 9.4.1, so local verification is unavailable; successful CI is the verification source. The PR remains open and unmerged.

## Security boundaries

Provider metadata is stored separately from SQLite and contains no key field. API keys are resolved through Android Keystore-backed AES-GCM storage. Missing keys fail before network connection. App provider endpoints require HTTPS. The public config constructors do not expose loopback overrides; loopback HTTP can only be constructed through module-internal test factories, and the app registry rejects test-only endpoints on load/registration. Endpoint userinfo/fragments and credential-like query parameters are rejected. Provider errors do not include response bodies or credentials.

Official API references checked on 2026-11-01 (provider pages do not declare publication dates):

- https://api-docs.deepseek.com/api/create-chat-completion
- https://docs.anthropic.com/en/api/messages-streaming
- https://ai.google.dev/api/generate-content
- https://platform.openai.com/docs/guides/streaming-responses

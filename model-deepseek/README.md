# DeepSeek Model Backend

This module provides a protected Android-native provider plugin for the existing `core.model-adapter` service.

- Provider id: `deepseek`
- Default model: `deepseek-flash`
- Default endpoint: `https://api.deepseek.com/chat/completions`
- Transport: streaming Chat Completions over SSE; cancellation disconnects the HTTP request.
- Implementation: `DeepSeekPreset` builds a `ModelProviderConfig`, so the bundled provider runs on the same `ConfigurableModelBackend` as every other protocol. There is no DeepSeek-specific HTTP/SSE code path.
- Credentials: supplied by the host through `ApiKeyProvider`; the Android app stores the key encrypted with an Android Keystore AES-GCM key. No key is bundled and no key-configuration UI exists yet.
- Conversation turn: `ConversationTurnService` appends the user message, sends the persisted session history through `ModelAdapterService`, forwards deltas, and stores only a completed assistant reply. Per-turn sampling overrides are passed through untouched; null means the provider defaults apply.

The app exposes `CordiActApplication.deepSeekApiKeyStore.setApiKey(value)` for a future settings surface. A missing key is reported as a typed model failure and does not send a request. Tests use a loopback-only HTTP endpoint and synthetic credentials.

Run module tests with `./gradlew :model-deepseek:test --console=plain` once the Android Gradle Plugin configured by the root project is available to Gradle.

## Multiple Protocols

`ConfigurableModelBackend` implements these text-streaming protocols behind the existing `ModelBackend` contract:

- `OPENAI_CHAT_COMPLETIONS` for DeepSeek-compatible/custom Chat Completions endpoints.
- `ANTHROPIC_MESSAGES` for Claude Messages SSE (`x-api-key`, `anthropic-version`).
- `GEMINI_GENERATE_CONTENT` for Gemini `streamGenerateContent?alt=sse`; the endpoint must contain `{model}` and keys are sent in `x-goog-api-key`.
- `OPENAI_RESPONSES` for Responses API semantic SSE events.

Use `ModelProviderRegistryService.addProvider(ModelProviderConfig(...))` to add a provider with a unique lowercase provider id, custom display name, protocol, endpoint, and model ids. Configurations persist as non-secret metadata in Android app preferences. Then call `CordiActApplication.providerApiKeyStore.set(providerId, key)` to set a key, and `modelProviders.removeProvider(providerId)` to revoke a provider; removal also deletes its key. A missing key fails before opening a connection. DeepSeek's `deepseek` id remains reserved and its existing `deepSeekApiKeyStore` stays compatible.

`ModelProviderPresets` provides default Anthropic, Gemini and OpenAI Responses endpoints. App provider configurations must use HTTPS. Plain HTTP loopback is accepted only by module-internal test configuration and cannot be enabled by persisted app provider settings. Query parameters named like credentials and userinfo/fragments are rejected. Keys are never part of `ModelProviderConfig`, logged, or persisted in SQLite.

The adapters currently support text-only `SYSTEM`, `USER`, and `ASSISTANT` messages. Tool/function-call messages and multimodal content are deliberately rejected/not implemented. Cancellation disconnects the request and suppresses terminal events after cancellation. SSE lines and individual events are bounded to 4 MiB.

## Sampling Parameters

`GenerationParameters(temperature, maxOutputTokens, topP)` carries provider-neutral sampling settings. Values are validated at construction (`temperature` 0..2, `topP` 0..1, `maxOutputTokens` 1..1,000,000) so an invalid value never reaches the network.

Two layers combine, and per-request values win field by field:

1. `ModelProviderConfig.defaults` — persistent per-provider defaults, saved with the provider metadata.
2. `ModelGenerationRequest.parameters` — per-turn overrides, reachable from `ConversationTurnService.submit(..., parameters = ...)` and from `InteractionIntegrationService.submitOrdinary/dispatch(..., parameters = ...)`.

Each protocol maps them into its own body shape: `temperature` + `max_tokens`/`max_output_tokens`/`maxOutputTokens`, and `top_p` (Gemini uses `generationConfig.topP`). Anthropic always sends `max_tokens`, falling back to 1024 when neither layer sets it. Adjust a stored provider in place with `ModelProviderRegistryService.updateProvider(config)`; re-adding the same id is unnecessary, and the stored credential is preserved because the provider id does not change.

No UI exists for these yet; the API is the current surface.

## API References

- DeepSeek Chat Completions: https://api-docs.deepseek.com/api/create-chat-completion and https://api-docs.deepseek.com/
- Anthropic Messages streaming: https://docs.anthropic.com/en/api/messages-streaming
- Gemini `generateContent` / `streamGenerateContent`: https://ai.google.dev/api/generate-content
- OpenAI Responses streaming: https://platform.openai.com/docs/guides/streaming-responses

These official references were checked on 2026-11-01; provider schemas can evolve, so compatibility tests remain the source-level contract.

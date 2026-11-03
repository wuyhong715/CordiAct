# Model Providers

This module contains the protocol-neutral provider backend and registry for the protected `core.model-adapter` service. Its bundled DeepSeek preset is one provider among several; protocol handling lives in `ConfigurableModelBackend`.

- Provider protocols: DeepSeek Chat Completions, Anthropic Messages, Gemini generate/streamGenerateContent, OpenAI Responses, and custom-compatible endpoints.
- Default bundled provider: `deepseek` (`deepseek-flash`, `https://api.deepseek.com/chat/completions`).
- Transport: protocol-specific JSON/SSE request and response handling behind the shared `ModelBackend` contract.
- Credentials: supplied by the host through `ApiKeyProvider`; the Android app stores the key encrypted with an Android Keystore AES-GCM key. No key is bundled and no key-configuration UI exists yet.
- Conversation turns: `ConversationTurnService` appends the user message, submits persisted session history, forwards deltas, and stores a completed assistant reply. Per-turn sampling overrides pass through untouched; null means provider defaults apply.

The app exposes `CordiActApplication.deepSeekApiKeyStore.setApiKey(value)` for a future settings surface. A missing key is reported as a typed model failure and does not send a request. Tests use a loopback-only HTTP endpoint and synthetic credentials.

Run module tests with `./gradlew :model-providers:test --console=plain`.

## Protocols

`ConfigurableModelBackend` implements these text-generation formats behind the existing `ModelBackend` contract:

- `OPENAI_CHAT_COMPLETIONS` for DeepSeek-compatible Chat Completions endpoints
- `ANTHROPIC_MESSAGES` for Anthropic Messages
- `GEMINI` for Gemini `generateContent` and `streamGenerateContent`
- `OPENAI_RESPONSES` for OpenAI Responses
- Custom-compatible endpoints through configurable provider metadata

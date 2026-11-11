# Provider-first model discovery and unified credentials

Implemented in the working tree on 2026-11-11. No APK packaging, commit, push or PR is implied.

## User flow

The model picker groups models under provider names. Connect a provider with an API URL, API key and protocol (OpenAI Chat Completions / Responses, Anthropic Messages, Gemini). Provider name is optional and defaults to the URL host. No comma-separated/manual model ID field remains. The explicit Connect action sends the key only to the entered HTTPS origin and lists available models. A refresh action on a provider replaces its list only after a successful complete response.

`ModelProviderConnection` accepts a base URL, `/models` URL or the corresponding generation endpoint. Bare origins use `/v1`, or `/v1beta` for Gemini. Custom gateway path prefixes are preserved. Discovery uses GET `/models` with protocol-specific headers; Anthropic and Gemini pagination is supported. Gemini embedding-only entries are filtered. OpenAI-compatible inventories cannot reliably advertise generation/attachment/reasoning capabilities, so those capabilities are not guessed from names. A gateway without a usable model-list API fails explicitly; there is no hidden manual-model fallback.

Limits: HTTPS only, no userinfo/credential query parameters, no redirects, maximum 32 pages, 1024 unique model IDs, 2 MiB/page, 60s overall budget checked between reads (individual connect/read waits bounded). Error messages never include remote response bodies or entered URLs/keys. List IDs are validated before saving. Existing model capabilities survive refresh only for IDs still present; new IDs do not acquire inferred capabilities. Concurrent provider edits win over stale discovery results.

## Credential boundary

- User/Agent credentials: existing approval-gated `CredentialVaultService`, `credentials` table and security audit remain unchanged.
- Application credentials: core `SystemCredentialStore` -> Android `AndroidSystemCredentials` -> `system_credentials` table in the SAME no-backup `security/vault.db`; same `AndroidVaultCrypto` and Keystore master key/versioning. Model/search adapters contain no crypto or storage implementation.
- Host-only namespace is not a new Agent credential scope, is never listed/exported by credential tools, and is not wired into their service. Provider HTTP use does not request a model approval to obtain the model's own key.
- AES-GCM AAD binds system credential domain, id and format version; IDs distinguish `model:<provider>` and `search:<provider>`. Plaintext byte buffers are wiped after use. Strings exist only at the legacy HTTP/key-provider boundary, never in config/chat/logs.
- Keystore key creation is serialized; decrypting a missing vault key fails rather than generating a replacement.

## Migration

Database v1 -> v2 adds `system_credentials` without dropping credential/audit records. Old model prefs (`model_credentials`), old DeepSeek preference/alias and old search prefs (`cordiact.search.credentials`) are READ-ONLY compatibility sources. There is a single write destination. First read decrypts the old entry, writes the vault, decrypt-verifies it, then synchronously clears the old preference. Failed crypto/write leaves the old entry intact. Restart after a successful copy but failed cleanup retries cleanup; existing vault values win, and corruption never triggers a fallback to stale secrets. Setting/replacing/deleting a key clears its legacy source to prevent resurrection. Unrelated legacy Keystore aliases are intentionally retained.

The separate AndroidDeepSeekApiKeyStore was removed. Bundled DeepSeek backend registration was removed from app composition; existing DeepSeek credentials create a normal `deepseek` provider with the legacy model configuration, while fresh installs have no empty built-in provider. Search DeepSeek may explicitly reuse the normal model provider key as before, through the same unified store, not an old DeepSeek path.

## Reproduction and evidence

JVM suite (existing isolated Gradle settings avoid Android SDK/AGP resolution):

```sh
cd /workspace/.verify-integration
./gradlew :core:test :model-providers:test :search-providers:test --console=plain
```

Verified: core 523, model-providers 102, search-providers 70 tests, all zero failures/errors. Added 20 JVM tests: 6 credential store, 8 discovery HTTP, 6 registry discovery/refresh tests. Coverage includes AES-GCM/AAD isolation, migration retry and failure preservation, no old-key resurrection, pagination, invalid/oversized/empty lists, redirect rejection, configuration/key rollback, capability retention and concurrent-edit safety.

Targeted app Kotlin compile passed:

```sh
cd /workspace/CordiAct
sh /workspace/spike/local_compile.sh -I /workspace/.provider-isolated-compile.init.gradle --console=plain
```

The temporary init script excludes only `com/cordiact/app/ui/**`, the pre-existing untracked UI directory. No project build file or user UI/icon file is changed by that exclusion. The unfiltered build is blocked by existing DynamicActivityPill.kt animation type errors and ScreenshotView.kt unresolved coil/AsyncImage dependencies. Full app unit/instrumentation compilation is additionally blocked by the existing generated-Java/AIDL output layout (`app/build/generated/aidl_source_output_dir/debug/compileDebugAidl/out` missing); this is not represented as a test pass.

Added `app/src/androidTest/java/com/cordiact/app/security/SystemCredentialMigrationTest.kt`: database v1 preservation, actual legacy Keystore model/search ciphertext migration, persistence/replacement/deletion, and host namespace exclusion from Agent listings. These Android tests are WRITTEN, NOT EXECUTED/validated on a device. No production endpoint/key was used for discovery and no APK was built locally. End-to-end UI/Keystore acceptance and CI APK validation remain pending.

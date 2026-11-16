# GPT image generation — first slice (2026-11-16)

## User flow
1. Settings → Models → add provider → GPT 图像生成.
2. Enter provider name, HTTPS base URL (e.g. `https://api.openai.com/v1`) and API key.
3. Discover available `gpt-image-*` IDs, or manually specify an ID actually supported by the provider.
4. Save. Image models do not appear in the conversation model selector.
5. In a conversation using a tool-capable model, request image creation. `list_image_models` lists provider/model IDs; `generate_image` creates one PNG.
6. The file is saved at workspace root as `generated-<UUID>.png`. A persistent gallery at the end of the conversation provides a bounded-resolution preview, enlarge and Android document-picker export.

## Protocol and boundaries
- Uses exactly one POST to `/images/generations`; sends model, prompt, n=1, size, quality, output_format=png.
- Expects `data[0].b64_json`; GPT Images returns Base64 without requesting `response_format`.
- URL-only responses are rejected, not fetched (avoid untrusted URL/SSRF and expiring-link-only artifacts).
- One image per call, maximum decoded size 10 MiB; response bounded at 15 MiB. PNG signature is checked.
- Generation has a 240-second socket read timeout under a 300-second tool deadline. Redirects and automatic retries are disabled. A timeout/cancellation can still incur provider charges; do not automatically resubmit.
- Generated images are written through the existing workspace file service. `generate_image` is WORKSPACE_WRITE, denied in read-only mode. `list_image_models` is PURE.
- Credentials remain in the existing credential store. Prompts/keys/provider error bodies are not included in image results or preview index.
- Workspace file is the deliverable. App-private copies and metadata are a durable display cache filtered by workspace AND session, not a new source of workspace authority.
- Existing stored config round-trips the new enum protocol using the existing provider JSON store.

## Not included
Image editing/reference-image input, multi-image batches, video, remote-job recovery, cancellation at the provider, automatic model fallback, token/price estimation. Generated images are not automatically attached to the next model turn. Preview cache deletion/retention UI is not implemented in this slice.

## Evidence
- Official protocol reference checked: https://developers.openai.com/api/docs/guides/image-generation . No assertion that a specific proxy/model ID is currently available.
- `./gradlew -p /workspace/.verify-integration :model-providers:test --tests '*OpenAiImageGeneratorTest' --tests '*ToolCallStreamTest' --tests '*ModelDiscoveryTest' --console=plain`: 19 tests, zero failures (4 image + 7 tool stream + 8 discovery).
- `sh /workspace/spike/local_compile.sh --console=plain`: app compileDebugKotlin passed. This is Kotlin compilation, NOT an APK build, installation or device acceptance.
- `git diff --check`: passed.
- No live paid image generation; no device rendering/export acceptance yet. APK packaging remains GitHub Actions-only.

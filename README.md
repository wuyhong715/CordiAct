# CordiAct

CordiAct is an original native Android application and workspace-centric action runtime designed to integrate with the [DeepSeek Harness](https://github.com/DeepSeek-AI/DeepSeek-V3) ecosystem while developing its own product capabilities.

> Project status: early architecture / bootstrap phase.

## Direction

- Native Android experience rather than a thin web wrapper
- Workspace as the primary unit for context, files, tasks, and extensions
- Explicit action-level policies and inspectable execution state
- Extensible plugin and workflow runtime
- A unified conversation entry point for users and agents
- DeepSeek Harness compatibility through adapters, not source-code coupling

## Repository principles

1. Keep CordiAct's product and domain code original and independently maintainable.
2. Integrate external runtimes through stable interfaces and documented adapters.
3. Treat user data, credentials, and action permissions as first-class boundaries.
4. Build and package Android artifacts in GitHub Actions; local environments are for development and verification.

## Planned modules

- `app`: native Android application shell and unified conversation UI
- `core`: workspace, action policy, event, and runtime contracts
- `integration`: DeepSeek Harness adapters
- `model-deepseek`: DeepSeek-first model backend plus configurable Anthropic Messages, Gemini, OpenAI Responses and custom protocol providers
- `plugin-api`: versioned extension API
- `docs`: architecture decisions and contribution notes

## Development

The Android project will be added after the initial architecture is settled. CI is intentionally configured to validate repository structure now and will become the Android build pipeline when the Gradle project lands.

## License

Apache-2.0. See [LICENSE](LICENSE).

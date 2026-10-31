# CordiAct architecture — initial decisions

## Product boundary

CordiAct is an independent Android product. Cordis is the kernel foundation; DeepSeek Harness is an ecosystem/runtime integration target. Neither relationship should turn the product repository into a direct fork.

## Core concepts

- **Workspace**: durable boundary for files, conversations, tools, policies, and extension state.
- **Action**: a typed operation with input, capability requirements, progress, result, and audit event.
- **Policy**: explicit decision layer evaluated before an action runs.
- **Runtime adapter**: translates between CordiAct contracts and an external harness/provider.
- **Plugin**: versioned extension that contributes actions, views, or workflow nodes without bypassing policy.

## Initial boundaries

The Android UI must not directly own provider-specific execution logic. UI requests enter the core runtime; the runtime resolves policy and adapter; events are streamed back to the UI.

## Open decisions

- Cordis integration surface and version pin
- DeepSeek Harness adapter protocol
- Offline/local model boundary
- Plugin packaging and trust model
- Workspace synchronization strategy

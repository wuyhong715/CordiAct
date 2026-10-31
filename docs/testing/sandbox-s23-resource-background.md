# S2.3 — Bounded Binder, resource, and idle validation

- Status: implementation present; GitHub Actions and physical-device results pending
- Date: 2026-11-01
- Baseline: `feadcd2` (S2.2); this report is not evidence of a passing run until the result table below is filled from CI/device artifacts.

## Scope and procedures

This step extends only the fixed, non-exported S2 `SandboxProbeService`; it does not connect the service to production `SandboxManager` or enable plugin execution.

### Bounded raw Binder field corpus

`rawAidlFrameMutationsAreRejectedWithoutWorkerDeath` sends a valid raw transaction as a positive control, followed by a fixed set of 11 rejected cases: unknown transaction code, wrong interface token, truncated fields, 65,536-character input, trailing bytes, wrong session, malformed nonce, invalid request ID, unsupported operation, negative deadline, and deadline above the service limit. After each rejected frame the isolated worker must remain alive and complete a normal `echo` request.

This is a deterministic boundary corpus, not random or coverage-guided Binder fuzzing. It does not claim exhaustive Parcel parser safety, Binder kernel robustness, or all possible primitive/string encodings.

### Bounded CPU/heap samples

- CPU probe: 2,000,000 fixed integer iterations (service hard bound 5,000,000); records worker-thread CPU milliseconds, wall milliseconds, and checksum. The client deadline is 5,000 ms and the test rejects a wall-time result above that bound.
- Heap probe: allocates and touches 2,048 KiB at one byte per 4 KiB page (service hard bound 4,096 KiB). It samples Android PSS and Java heap-used bytes before and during the allocation. The log includes the device API and raw metrics. The test then checks the worker is responsive.
- These are small, bounded pressure samples, not a CPU quota or process-wide memory quota. PSS includes runtime/process overhead and varies by platform; the JS/native allocator is not part of S2.3.

### Background / Doze sample

The instrumentation test reads the initial `mForceIdle` and `am get-inactive` values, temporarily forces DeviceIdle and marks `com.cordiact.app` inactive, then checks one existing isolated Binder echo. A `finally` block restores the exact observed values and asserts the restored state. The CI runner adds an EXIT trap that issues `cmd deviceidle unforce` only when the emulator was not force-idle before the test. For physical-device validation, run instrumentation inside an outer shell trap with the same before/after state capture; do not alter battery-optimization exemptions or persistent Settings.

## Verification results

| Environment | Commit / workflow run | Binder corpus | CPU / heap metrics | Forced idle + inactive | Cleanup | Evidence |
| --- | --- | --- | --- | --- | --- | --- |
| GitHub Actions API 29 x86_64 | pending | pending | pending | pending | pending | pending |
| GitHub Actions API 35 x86_64 | pending | pending | pending | pending | pending | pending |
| vivo V2463A Android 16 / API 36 / arm64-v8a | pending; CI APK required | pending | pending | pending | pending | pending |

The workspace-only command `./gradlew test --console=plain --no-daemon` did not reach test execution: the local environment could not resolve configured plugin `com.android.application:9.4.1`. This is a local dependency-resolution block, not a passing test and not evidence of a source failure. APK assembly is restricted to GitHub Actions.

## Limits / next gate

- The mutation cases change a fixed set of Binder fields; there is no raw-byte mutation engine, randomized fuzzing, long-duration soak, CPU quota enforcement, OOM test, battery drain measurement, or Doze maintenance-window test.
- This step still executes only fixed Kotlin probe operations. It does not load JavaScript, native plugin modules, or third-party code.
- Update this report with artifact-derived test counts, actual metrics, the workflow run and commit IDs, API 36 raw output, and proof of restored idle/package state before treating S2.3 as validated.

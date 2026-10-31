# Sandbox S2.2: fixed-probe Binder frame boundaries

- **Code commit:** `d9a93ba` (raw-frame/output tests from `3b1b588`, valid-frame control from `597fe21`).
- **CI:** GitHub Actions run `41219780526`: build succeeded; API 29 and API 35 emulator instrumentation each **13/13 passed**, zero failures and skips in the XML reports.
- **Physical device:** vivo V2463A, Android 16/API 36, `arm64-v8a`. The same CI-built APK pair passed `am instrument -w -r com.cordiact.app.test/androidx.test.runner.AndroidJUnitRunner` **13/13 on two consecutive runs**. No APK was built locally.
- **APK SHA-256:** `app-debug.apk` = `a12115d1c04b7ce5a6af6d6ca81304cb0e50f890fb028c861949fb4028b840e6`; `app-debug-androidTest.apk` = `2608b589b2250e913ab2bee7fa9f1fb3b4390967598bc5ef126a03bf0c3ee79d`. Hashes were compared against the files staged on the phone before install.

## What the new tests prove

1. A **valid raw executeProbe transaction** succeeds, proving the mutation suite reaches the intended Binder method rather than an unrelated code. Wrong interface token, truncated frame, 65,536-character input and trailing extra data are rejected while the isolated worker stays responsive. These are four deterministic mutations, **not exhaustive fuzzing**.
2. A fixed `oversized-output` probe generates 8,193 bytes and the 8,192-byte service reply guard rejects it; a subsequent valid call succeeds. This operation is deterministic and does not execute scripts.
3. Sixty-four consecutive 4,096-byte input/echo calls succeed and leave no active probe. This is bounded request repetition, **not a measured CPU, battery or heap stress test**.
4. The prior ten IPC/lifecycle tests continue to pass: host/worker UID separation, concurrent instance isolation, nonce/input/deadline checks, single in-flight request, rejection under concurrency, repeated cancellation, active-call close, 24-call handoff, watchdog kill, crash containment and fresh process.

## Defect found and repair

The first run with a valid raw-frame control (`41218823949`, `597fe21`) revealed that generated AIDL dispatch accepted trailing bytes on API 29 and API 35. The service now checks host caller identity, enforces a 12 KiB total Parcel limit, validates the interface token and expected parameter count for each known transaction, requires zero unconsumed bytes, restores the Parcel cursor and only then delegates to the generated AIDL dispatcher. The repaired run (`41219780526`) passes on both emulator APIs and the phone.

## Post-test state and remaining gates

Only temporary CordiAct app/test installs were made; both packages and staged APKs were removed, and the loopback-only transfer server was stopped. Production `SandboxManager` still uses `UnavailableIsolatedSandboxRuntime` and does not run JS, Cordis or plugin code. Raw mutation coverage is limited to four frame shapes on one method; unknown-code fuzzing, measured CPU/heap/battery pressure, process-lifecycle behavior under Doze/background restrictions, and a real JS engine remain open.

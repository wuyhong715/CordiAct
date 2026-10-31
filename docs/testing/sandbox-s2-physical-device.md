# Sandbox S2 physical-device validation

- Result: **PASS — 4/4 tests**
- Date: 2026-10-31 (China Standard Time)
- Device: vivo V2463A, Android 16 / API 36, `arm64-v8a`
- APK source: GitHub Actions run `41208793283`, commit `8c3f653` (not locally built)
- Instrumentation: `com.cordiact.app.test/androidx.test.runner.AndroidJUnitRunner`

## Tests

1. `isolatedProcessHasDistinctUidAndOnlyRunsFixedProbeOperations` — passed
   - Service confirmed `Process.isIsolated()` and a UID distinct from the app.
   - Fixed echo/sleep payloads returned correctly.
   - Wrong per-session nonce and oversized frame were rejected.
2. `watchdogKillsInfiniteProbeWithoutKillingHostAndNewSessionCanStart` — passed
   - Hung fixed probe was terminated by its deadline watchdog.
   - Isolated process died; a fresh isolated session then started and responded.
3. `workerCrashIsContainedToItsIsolatedProcess` — passed
   - Intentional isolated worker crash did not terminate the instrumentation host.
4. `simultaneousSandboxSessionsReceiveDifferentIsolatedUids` — passed
   - Two simultaneous isolated service instances had distinct UIDs and independent responses.

## Device install/cleanup

Android 16's Package Manager could not read the APK directly from shared Downloads (FUSE SELinux denial). Staging the CI APKs in `/data/local/tmp` and installing through Shizuku succeeded. Both `com.cordiact.app.test` and the previously absent `com.cordiact.app` test install were uninstalled after the run; temporary APKs and the downloaded archive were removed. No CordiAct package remains installed from this validation.

## Scope and limits

This proves the fixed-operation Android isolated-service probe on one physical API 36 device, plus CI emulators at API 29 and API 35. The probe executes only fixed `echo`, `sleep`, `hang`, and `crash` operations. It does **not** load Cordis, JavaScript, plugin code, a capability broker, or user data, and it is not wired to production `SandboxManager`. Binder fuzzing, resource pressure, Doze/background process behavior, and an actual JS engine remain untested.

## S2.1 status

The preceding 4/4 result covers S2 only. S2.1 was rerun with APKs from CI run `41215976392` (code commit `ad44a44`): **10/10 passed** on this vivo API 36 device. See [the S2.1 report](sandbox-s21-ipc-hardening.md). Both test packages and temporary APKs were removed after verification.


## S3 / S2.3 device rerun status

Do not treat the earlier S2.1/S2.2 10/10 or 13/13 runs as S3 validation. The S3 CI artifact was staged on the vivo API 36 device, but Android 16 showed the lock-screen installer and the app/test packages remained absent. Installation and the 19-test S3 run are still pending device unlock; see [the S3 CI/device status](sandbox-s3-js-engine-and-poc.md).


## S3 physical-device completion

The API 36 run initially paused at the lock-screen installer. After user unlock, CI artifact `13056996615` passed 19/19 on vivo V2463A / Android 16 / API 36. Packages and staged APKs were removed afterward. Details: [S3 PoC report](sandbox-s3-js-engine-and-poc.md).

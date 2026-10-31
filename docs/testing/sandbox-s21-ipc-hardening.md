# Sandbox S2.1 fixed-probe IPC hardening

- **Scope:** Non-exported Android isolated-service fixed probe (`echo`, `sleep`, `hang`, `crash`) behind typed AIDL. It does not execute Cordis, JavaScript or plugin code.
- **Code:** `ad44a44` (`5fd3c8e` introduced the limits/tests; `ad44a44` resolved an API 29 host worker handoff race).
- **CI:** GitHub Actions run `41215976392`: build succeeded; API 29 isolated-service proof **10/10 passed** and API 35 **10/10 passed**, zero failures or skips in both XML reports.
- **Physical device:** vivo V2463A, Android 16/API 36, `arm64-v8a`. The two APKs came from that CI run, not a local build. `am instrument -w -r com.cordiact.app.test/androidx.test.runner.AndroidJUnitRunner` returned `OK (10 tests)`.
- **APK SHA-256:** `app-debug.apk`: `b58009b5053519dc68295ccf99ecbd3fc0c8dd1e92093e432a0af3a7886de781`; `app-debug-androidTest.apk`: `a5b01dc91382f8eed9d9ec78b8286ea8c4e98fa0130d7b18b01411424265e59d`.

## Verified behaviors

1. Isolated service UID differs from the host UID; concurrent named instances have different isolated UIDs.
2. Wrong nonce, malformed request ID, unsupported operation, invalid deadline and oversized frame are rejected; subsequent valid requests still work.
3. Service admits one active Binder operation per instance. Excess concurrent calls are rejected; the host also uses one permit, one worker and one bounded handoff slot.
4. Twenty-four immediate sequential calls complete despite worker handoff timing; this catches the earlier API 29 failure.
5. Repeated cancellation prevents a late success; closing with an active request ends the process and blocks subsequent calls.
6. Watchdog stops a hanging probe; an isolated worker crash leaves the host responsive, and a fresh isolated session starts.

## Cleanup

The app and test packages were installed only for this test and then removed. The two APKs staged in `/data/local/tmp/` were deleted and the loopback-only transfer server was stopped. Package and temporary-file absence were checked on the device. The CI artifact remains available in GitHub Actions.

## Limits

S2.1 alone did not test raw Binder parcels. S2.2 now covers four deterministic malformed raw frame shapes, but not comprehensive parcel fuzzing, app-wide memory or CPU pressure, or Doze/background lifecycle interruptions. Production `SandboxManager` still uses `UnavailableIsolatedSandboxRuntime`; no local plugin execution is enabled. See [S2.2 results](sandbox-s22-binder-boundaries.md); remaining gates include broader Binder fuzzing, resource pressure measurements and background interruption/recovery before S3 engine selection.

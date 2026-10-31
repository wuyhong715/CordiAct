# S3 — JS engine decision and isolated fixed-script PoC

- Date: 2026-11-01
- Status: implementation in working tree; CI compilation and device execution are pending. Do not call this PoC validated until API 29/35 CI and device evidence is recorded.
- Scope: only APK-bundled, hash-pinned test scripts executed by the dedicated Android isolated service. No third-party plugin path is enabled.

## Engine selection

**Selected for the S3 experiment: QuickJS-NG v0.17.0**, pinned as a Git submodule at `third_party/quickjs-ng` commit `6d46d07d04041b40f4f49eaa7fdebe44c314c699` (upstream release commit dated 2026-09-18). The current upstream README describes QuickJS-NG as a fork intended to resume work after the original QuickJS project went dormant. The pinned source exposes runtime APIs for `JS_SetMemoryLimit`, `JS_SetMaxStackSize`, `JS_SetInterruptHandler`, and `JS_SetModuleLoaderFunc`. Its upstream CMake core source target consists of `dtoa.c`, `libregexp.c`, `libunicode.c`, and `quickjs.c`; `quickjs-libc.c` is appended only when its optional libc feature is enabled. CordiAct deliberately omits `quickjs-libc.c` and provides no module normalizer/loader.

The MIT license is included at `app/src/main/assets/licenses/quickjs-ng-MIT.txt`. The upstream license requires preserving the copyright and permission notice; this packaged copy accompanies the source submodule. Upstream versions/tags were inspected on 2026-11-01. The selected pinned release is recent enough to be actively maintained at the time of this decision, but that is not a security guarantee; future upgrades require review and tests.

### Comparison

| Candidate | License / maintenance evidence | Android ABI and resource controls | Decision |
| --- | --- | --- | --- |
| QuickJS-NG v0.17.0 | MIT. Official upstream release/tag at commit `6d46d07d04041b40f4f49eaa7fdebe44c314c699`, dated 2026-09-18; project README explicitly describes the maintained fork. | C source, no upstream prebuilt Android ABI promise was found. CordiAct builds from the pinned source through Android NDK for `arm64-v8a` and `x86_64`; this project's matrix will prove API 29/35 and the vivo API 36 ABI only if CI/device runs pass. The C API provides engine allocator limit, stack limit and interrupt callback; native/process/JVM memory and CPU are not hard bounded by those APIs. | Chosen for this test PoC because explicit per-runtime engine heap/stack limits and an interrupt callback are available, and omitting the libc integration avoids default file/process helpers. The Android isolation boundary remains the security boundary. |
| Mozilla Rhino 1.9.2 | Majority MPL-2.0; pure Java engine. Upstream release tag `Rhino1_9_2_Release` points to commit `b3d405fe2ef4948929629b23d85ba7eef76a2c2a`, dated 2026-10-27; project source has active interpreter work. | Java avoids a JNI/NDK ABI matrix and can use Android runtime memory management. `Context`/`ContextFactory` expose instruction-observer callbacks useful for cooperative instruction checks, but no comparable documented hard per-context heap limit was identified in the reviewed upstream API. This does not mean Rhino cannot be resource-managed by process termination. | Not chosen for S3 because the immediate PoC acceptance criteria require an explicit engine heap boundary as well as interruption/stack control; retain as a future alternative if native supply-chain/ABI burden dominates. |

### Primary sources checked (retrieved 2026-11-01)

- QuickJS-NG upstream repository/README: https://github.com/quickjs-ng/quickjs
- QuickJS-NG v0.17.0 release: https://github.com/quickjs-ng/quickjs/releases/tag/v0.17.0
- Pinned QuickJS-NG API: https://github.com/quickjs-ng/quickjs/blob/6d46d07d04041b40f4f49eaa7fdebe44c314c699/quickjs.h
- Pinned QuickJS-NG implementation: https://github.com/quickjs-ng/quickjs/blob/6d46d07d04041b40f4f49eaa7fdebe44c314c699/quickjs.c
- Pinned QuickJS-NG build source list: https://github.com/quickjs-ng/quickjs/blob/6d46d07d04041b40f4f49eaa7fdebe44c314c699/CMakeLists.txt
- Pinned QuickJS-NG MIT license: https://github.com/quickjs-ng/quickjs/blob/6d46d07d04041b40f4f49eaa7fdebe44c314c699/LICENSE
- Rhino 1.9.2 release and source: https://github.com/mozilla/rhino/releases/tag/Rhino1_9_2_Release; https://github.com/mozilla/rhino/blob/b3d405fe2ef4948929629b23d85ba7eef76a2c2a/LICENSE.txt; https://github.com/mozilla/rhino/blob/b3d405fe2ef4948929629b23d85ba7eef76a2c2a/rhino/src/main/java/org/mozilla/javascript/Context.java; https://github.com/mozilla/rhino/blob/b3d405fe2ef4948929629b23d85ba7eef76a2c2a/rhino/src/main/java/org/mozilla/javascript/ContextFactory.java
- Android NDK ABI guide: https://developer.android.com/ndk/guides/abis
- Android `<service>` manifest reference: https://developer.android.com/guide/topics/manifest/service-element
- Android `Context.bindIsolatedService`: https://developer.android.com/reference/android/content/Context#bindIsolatedService(android.content.Intent,int,java.lang.String,java.util.concurrent.Executor,android.content.ServiceConnection)

## Current PoC structure

- `app/src/main/java/com/cordiact/app/sandbox/SandboxJsPocService.kt` is a distinct, non-exported, `android:isolatedProcess="true"` service in process `:sandbox_js_poc`. It refuses non-isolated startup. `CordiActApplication` already returns early for `Process.isIsolated()`, so it does not create the host plugin/database graph in this UID.
- `Context.bindIsolatedService` gives each instrumentation session a unique instance. The Binder caller must be the package UID. The PoC protocol accepts no source text, plugin identity, URI, file path, network request, or host capability; it exposes only four fixed test vectors and two attestation reads. Incoming frame size is capped at 4 KiB and exact argument consumption is checked before generated AIDL dispatch. Only one JS evaluation is admitted at a time.
- Four JS files are APK raw resources; the service checks each complete byte sequence against a fixed SHA-256 before execution:
  - normal: `sandbox_s3_fixed.js`, `702e554c1431094f8e5c4c7260f545cdb80b6ff5a53eab8902375123d2866953`;
  - timeout: `sandbox_s3_timeout.js`, `2d63ea6d8fbc0786bad2ca1e6d4d42082ab8ff34e9f8e921474d53041a1f561c`;
  - memory limit: `sandbox_s3_memory.js`, `5c4a8c8e4ffc4962729c54893b1e59875f35b65c6d74e6d9ce36768299f476b4`;
  - recursion/stack: `sandbox_s3_stack.js`, `a7c2ee51c346aa4a3fd8d53de56d72f75c7a034dccbec8785e6155c44cd99d15`.
- CMake compiles only the pinned QuickJS-NG core and CordiAct JNI bridge. The standard `quickjs-libc.c` is absent, no module loader/normalizer is installed, and JS executes as global script—not a module. The global `eval` and `Function` names are overwritten with `undefined`. No Java object, Android `Context`, JNI host function, Java bridge, app data handle, socket, or capability broker is exposed.
- Bounds in source: script bytes ≤16 KiB; QuickJS heap ≤8 MiB; QuickJS stack ≤256 KiB; caller wall deadline 100–5,000 ms; QuickJS interrupt callback checks a monotonic elapsed-realtime deadline; an independent service handler kills the isolated worker at deadline +300 ms if native evaluation does not return. Any timeout kill is confined to this dedicated isolated process; host process is expected to survive.
- NDK build ABI filters are `arm64-v8a` and `x86_64`. Android NDK r27.2.12479018 and CMake 3.22.1 are installed only by GitHub Actions. No local APK is built.

## Results (pending)

| Validation | Required result | Evidence |
| --- | --- | --- |
| Static normal script | exact expected JSON, `hostFree=true`, isolated UID differs from host | pending CI/API 29+35 and device |
| Memory stress | QuickJS returns a bounded error for a 16 MiB allocation attempt under 8 MiB engine cap; worker remains usable | pending |
| Stack stress | QuickJS returns a bounded error under 256 KiB stack cap; worker remains usable | pending |
| Infinite-loop vector | caller deadline and isolated service death within watchdog allowance; host process survives | pending |
| Build | API 29 + API 35 emulator instrumentation on CI-built APKs; x86_64; clean NDK/CMake configure | pending |
| Device | same CI run's APKs on vivo V2463A Android 16/API 36/arm64-v8a, then package and temporary-file cleanup | pending; cannot run before CI artifact exists |

Local `./gradlew test --console=plain --no-daemon` did not reach task execution because configured Android Gradle Plugin `com.android.application:9.4.1` could not be resolved in this workspace. No local APK or native Android build has been made. Exact source compilation and instrumented outcomes therefore remain unverified until Actions runs.

## Limitations and production gates

- This proves only that a fixed, APK-signed script can run in a dedicated Android isolated service if CI/device tests pass. It does **not** make the engine a sandbox or certify arbitrary JavaScript.
- QuickJS allocator and stack limits are not total process RSS limits; native allocations outside its allocator, JVM/runtime allocations, JNI buffers, OS memory pressure, thermal throttling, battery use and strict CPU quotas are not measured or guaranteed. The service process kill remains the final deadline response.
- No comprehensive JS conformance/fuzzing, raw Binder fuzz, native memory sanitizer, native crash corpus, security review or side-channel analysis has been run.
- No dynamic plugin/module loading exists. No APK source/network loading, `import`, std/os modules, DEX/JAR/`.so` loading, reflection, or host bridge is allowed in this PoC. Never pass arbitrary script text to this service.
- Production `SandboxManager` still defaults to `UnavailableIsolatedSandboxRuntime`; S3 is test-only and not connected to dispatch. Third-party local plugins remain disabled in v1. No claim of production-ready sandbox or Play-policy approval follows from this PoC.
- Before production integration: finish all CI/device tests; re-review exact NDK/native outputs and supply-chain; test process death, cancellation, concurrency, malformed Binder calls and lifecycle cleanup; add broader QuickJS fuzzer and adversarial resource corpus; verify updated state on API 29/35/API36; complete capability broker, lifecycle revocation, data isolation, release signing and Play policy review.

## Verification update — 2026-11-01

- **CI run:** `41238872330`, code commit `44b8443`. Build plus API 29 and API 35 emulator jobs all succeeded. Each instrumentation XML report shows **19 tests, 0 failures, 0 errors, 0 skipped**.
- **Covered in both emulator suites:** all prior S2/S2.1/S2.2 fixed-probe checks; raw-frame mutations; output/input bounds; 64 maximum-sized calls; bounded CPU/heap pressure metrics; force-idle probe and restoration; signed APK resource digest; fixed QuickJS script runs only in isolated UID; QuickJS heap/stack stress; infinite-loop deadline kills only the worker.
- **JS repair:** QuickJS-NG's `JS_Eval` requires an accessible NUL sentinel at `input_len`. JNI byte-array storage does not guarantee one. JNI now copies the already hash-verified source into a bounded `source_length + 1` allocation, terminates it, and passes the exact original length to `JS_Eval`. The app-side source cap is 16 KiB. The source resource remains SHA-256 `85db124b2d9ae593502570434ee947c4941f4693610c60cb7571fc3f892d2f86`.
- **Host-free assertion:** JavaScript's built-in `eval` and `Function` are language intrinsics, not Android host bridges. The sample checks only host-injected names (`java`, `os`, `std`, `process`, `require`); JNI replaces global `eval` and `Function` with `undefined` for this PoC.
- **Doze test:** the test now exercises only `cmd deviceidle force-idle`, checks the probe response, and restores the original idle state. It no longer attempts `am set-inactive` on a foreground instrumentation package; no application standby state is changed.
- **Physical device:** vivo V2463A / Android 16 / API 36 / `arm64-v8a` passed **19/19** using APK artifact `13056996615` from the same CI run. Both APK SHA-256 values were verified before installation. The first install attempt met the lock-screen package-installer gate; after the user unlocked the phone, installation and instrumentation succeeded. Both temporary packages were then uninstalled and staged APKs removed.
- **No local APK build:** both staged APKs are from the successful GitHub Actions run. Production dispatch remains on `UnavailableIsolatedSandboxRuntime`; the PoC is not a production plugin execution path.

Earlier planning text describes the state before this verification update; CI API 29/35 and vivo API 36 validation are now complete.


### S3 physical-device completion — 2026-11-01

After the user unlocked the vivo V2463A, the exact CI APK pair installed and `am instrument -w -r com.cordiact.app.test/androidx.test.runner.AndroidJUnitRunner` returned `OK (19 tests)` on Android 16/API 36 / arm64-v8a. Tests included fixed QuickJS resource digest/evaluation in the isolated UID, timeout and memory/stack vectors, host isolation, Binder bounds, and force-idle state restoration. Both packages were uninstalled, staged files removed, and the temporary loopback server stopped. The earlier “pending unlock” line above is superseded by this completion result.

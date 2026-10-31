#!/usr/bin/env bash
set -uo pipefail
set +e
prior_force_idle="$(adb shell dumpsys deviceidle | sed -n 's/.*mForceIdle=\(true\|false\).*/\1/p' | head -n1 | tr -d '\r')"
case "$prior_force_idle" in
  true|false) ;;
  *) echo "Cannot determine emulator force-idle state; refusing Doze instrumentation" >&2; exit 1 ;;
esac
restore_device_idle() {
  if [ "$prior_force_idle" = "false" ]; then
    adb shell cmd deviceidle unforce >/dev/null 2>&1 || true
  fi
}
trap restore_device_idle EXIT
./gradlew connectedDebugAndroidTest --no-daemon --stacktrace --info
test_exit=$?
mkdir -p app/build/sandbox-validation
adb logcat -d -s CordiActS23:I > "app/build/sandbox-validation/api-$1.log" 2>&1 || true
exit "$test_exit"

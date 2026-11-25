#!/usr/bin/env bash
# Source/type check against the actual SDK and AndroidX classes; does not run Android or build an APK.
set -euo pipefail
root=$(cd -- "$(dirname -- "$0")/.." && pwd -P)
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}"
: "${JAVA17_HOME:?Set JAVA17_HOME to a JDK 17 installation}"
: "${GRADLE_USER_HOME:?Set GRADLE_USER_HOME to the populated Gradle cache}"
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
cache=$GRADLE_USER_HOME/caches/modules-2/files-2.1
sdk=$ANDROID_HOME/platforms/android-36/android.jar
harness=$root/deepseek-harness/build/libs/deepseek-harness.jar
test -r "$sdk" && test -r "$harness"
work=$(mktemp -d "${TMPDIR:-/tmp}/cordiact-follow-compile.XXXXXXXX")
# Keep the unique output directory (including downloaded public deps) for inspection.
printf 'Verification work directory: %s\n' "$work"
fetch() {
    local relative=$1 target=$2
    curl --fail --silent --show-error --location --max-time 30 \
        "https://dl.google.com/dl/android/maven2/$relative" -o "$target"
}
fetch_aar() {
    local relative=$1 name=$2
    fetch "$relative" "$work/$name.aar"
    mkdir "$work/$name"
    (cd "$work/$name" && "$JAVA17_HOME/bin/jar" xf "$work/$name.aar" classes.jar)
    cp "$work/$name/classes.jar" "$work/$name.jar"
}
fetch_aar androidx/test/core/1.6.1/core-1.6.1.aar test-core
fetch_aar androidx/test/ext/junit/1.2.1/junit-1.2.1.aar test-ext-junit
fetch androidx/lifecycle/lifecycle-common-jvm/2.8.7/lifecycle-common-jvm-2.8.7.jar "$work/lifecycle-common.jar"
shopt -s nullglob
compiler=("$cache"/org.jetbrains.kotlin/{kotlin-compiler-embeddable,kotlin-stdlib,kotlin-script-runtime,kotlin-reflect,kotlin-daemon-embeddable}/*/*/*.jar
    "$cache"/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/*/*/*.jar "$cache"/org.jetbrains/annotations/*/*/*.jar)
libs=("$cache"/org.jetbrains.kotlin/kotlin-stdlib/2.4.21/*/*.jar "$cache"/junit/junit/4.13.2/*/*.jar
    "$cache"/org.hamcrest/hamcrest-core/*/*/*.jar)
compiler_cp=$(IFS=:; printf '%s' "${compiler[*]}")
lib_cp=$(IFS=:; printf '%s' "${libs[*]}")
androidx="$work/test-core.jar:$work/test-ext-junit.jar:$work/lifecycle-common.jar"
main="$root/app/src/main/java/com/cordiact/app/deepseek"
debug="$root/app/src/debug/java/com/cordiact/app/deepseek"
test_src="$root/app/src/test/java/com/cordiact/app/deepseek"
device="$root/app/src/androidTest/java/com/cordiact/app/deepseek"
compile=("$JAVA_HOME/bin/java" -cp "$compiler_cp" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
    -Werror -no-stdlib -no-reflect -jdk-home "$JAVA17_HOME" -jvm-target 17)
"${compile[@]}" -classpath "$sdk:$harness:$lib_cp" -d "$work/adapter.jar" "$main"/*.kt "$debug"/*.kt
"${compile[@]}" -Xfriend-paths="$work/adapter.jar" -classpath "$work/adapter.jar:$sdk:$harness:$lib_cp:$androidx" \
    -d "$work/verification.jar" "$test_src"/*.kt "$device"/*.kt
"$JAVA17_HOME/bin/java" -cp "$work/verification.jar:$work/adapter.jar:$sdk:$harness:$lib_cp" org.junit.runner.JUnitCore \
    com.cordiact.app.deepseek.AndroidFollowRuntimeTest com.cordiact.app.deepseek.ForegroundFollowScopeTest
printf 'Instrumentation source compiled; device tests not run.\n'

#!/usr/bin/env bash
# Source/type check against the actual SDK and AndroidX classes; does not run Android or build an APK.
# To run offline, point FOLLOW_ANDROIDX_CACHE at a previous work directory with the three originals.
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
    local relative=$1 target=$2 expected=$3
    if [[ -n ${FOLLOW_ANDROIDX_CACHE:-} ]]; then
        cp -- "$FOLLOW_ANDROIDX_CACHE/${target##*/}" "$target"
    else
        curl --fail --silent --show-error --location --max-time 30 \
            "https://dl.google.com/dl/android/maven2/$relative" -o "$target"
    fi
    printf '%s  %s\n' "$expected" "$target" | sha256sum --check --status || {
        printf 'Unexpected AndroidX digest: %s\n' "$relative" >&2
        return 1
    }
}
fetch_aar() {
    local relative=$1 name=$2 expected=$3
    fetch "$relative" "$work/$name.aar" "$expected"
    mkdir "$work/$name"
    (cd "$work/$name" && "$JAVA17_HOME/bin/jar" xf "$work/$name.aar" classes.jar)
    cp "$work/$name/classes.jar" "$work/$name.jar"
}
fetch_aar androidx/test/core/1.6.1/core-1.6.1.aar test-core \
    7a7af31c21785ddb7c4319c838847e6d4d9b5bb531f3a951481ec3b98780bf51
fetch_aar androidx/test/ext/junit/1.2.1/junit-1.2.1.aar test-ext-junit \
    7b146a73c545b983430d2fae0081df67c76722c19a9abd2904e131696a8c5b20
fetch androidx/lifecycle/lifecycle-common-jvm/2.8.7/lifecycle-common-jvm-2.8.7.jar "$work/lifecycle-common.jar" \
    61c873a7327c946ec033c310bb98f3f92eeabcede0e1a5200ab8a1896483c7bf
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
# Exercise the exact device TLS fixture on the host JVM as well (not an Android provider result).
cat > "$work/HostTlsProof.java" <<'EOF'
import com.cordiact.app.deepseek.FollowLoopbackTlsFixture;
class HostTlsProof {
    public static void main(String[] args) {
        var fixture = FollowLoopbackTlsFixture.INSTANCE;
        fixture.acceptExactPinAndAlpn();
        fixture.rejectWrongPinAndMissingAlpn();
        System.out.println("Host JVM loopback TLS fixture passed");
    }
}
EOF
"$JAVA17_HOME/bin/java" -cp "$work/verification.jar:$harness:$lib_cp" "$work/HostTlsProof.java"
printf 'Instrumentation source compiled; device tests not run.\n'

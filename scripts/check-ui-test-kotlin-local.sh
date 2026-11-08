#!/bin/sh
# TYPECHECK ONLY: AAPT2 cannot run on this ARM host. No resource/runtime validation implied.
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
sh scripts/check-ui-kotlin-local.sh "$@"
mkdir -p app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources
cp app/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar \
   app/build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar
sh scripts/check-ui-kotlin-local.sh :app:compileDebugUnitTestKotlin -x :app:processDebugResources "$@"

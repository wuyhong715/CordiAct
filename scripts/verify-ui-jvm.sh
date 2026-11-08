#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
./gradlew -p verification/ui test :model-providers:test :interaction:test "$@"

#!/usr/bin/env bash
# language: Bash, file: build.sh, target: host with Android SDK + NDK r28 + JDK 21
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
OUT="$ROOT/app/build/outputs/apk/release/app-release.apk"

need() { printf '  %-22s %s\n' "$1" "$2"; }
fail=0

echo "== preflight =="

if command -v gradle >/dev/null 2>&1; then
    need "gradle" "$(gradle --version 2>/dev/null | awk '/^Gradle/{print $2}')"
elif [ -x "$ROOT/gradlew" ]; then
    need "gradlew" "present"
elif [ -f "$HOME/.gradle/wrapper/dists/gradle-8.11.1-bin" ]; then
    need "wrapper dist" "cached"
else
    need "gradle" "MISSING (~130 MB download)"
    fail=1
fi

if [ -f "$SDK/platforms/android-35/android.jar" ]; then
    need "android.jar" "android-35"
else
    need "android.jar" "MISSING at $SDK/platforms/android-35/android.jar"
    fail=1
fi

NDK_DIR="$SDK/ndk/28.0.13004108"
if [ -d "$NDK_DIR" ]; then
    need "ndk" "$NDK_DIR"
else
    need "ndk" "MISSING (r28, ~2.5 GB extracted)"
    fail=1
fi

if [ -x "$SDK/build-tools" ] || ls "$SDK"/build-tools/*/aapt2 >/dev/null 2>&1; then
    need "build-tools" "present"
else
    need "build-tools" "MISSING"
    fail=1
fi

JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' 'NR==1{print $2}' | cut -d. -f1)"
if [ "${JAVA_MAJOR:-0}" -ge 17 ] && [ "${JAVA_MAJOR:-0}" -le 21 ]; then
    need "jdk" "$JAVA_MAJOR (supported)"
else
    need "jdk" "$JAVA_MAJOR (need 17-21; Gradle 8.11 rejects newer)"
    fail=1
fi

if [ "$fail" -ne 0 ]; then
    echo
    echo "preflight failed: toolchain incomplete, nothing to build with"
    exit 2
fi

echo
echo "== build =="
cd "$ROOT"
if [ -x ./gradlew ]; then
    ./gradlew --no-daemon :app:assembleRelease
else
    gradle --no-daemon :app:assembleRelease
fi

echo
echo "== verify =="
"$SDK"/build-tools/*/zipalign -c -P 16 -v 4 "$OUT" >/dev/null && echo "  16 KB alignment  OK"
"$SDK"/build-tools/*/apksigner verify --print-certs "$OUT" | head -3
echo "  artifact         $OUT ($(du -h "$OUT" | cut -f1))"

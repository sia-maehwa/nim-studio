#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
GRADLE_VERSION="8.9"
SDK_API="35"
BUILD_TOOLS_VERSION="35.0.0"
LOG_FILE="$PROJECT_ROOT/build-log.txt"

fail() {
    printf 'APK build: %s\n' "$1" >&2
    exit 1
}

exec > >(tee "$LOG_FILE") 2>&1

command -v java >/dev/null 2>&1 || fail "JDK 17+가 필요합니다. java를 설치하고 PATH를 확인하세요."

JAVA_VERSION_OUTPUT="$(java -version 2>&1 || true)"
JAVA_VERSION_TOKEN="${JAVA_VERSION_OUTPUT#*version \"}"
JAVA_MAJOR="${JAVA_VERSION_TOKEN%%[!0-9]*}"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ ]] || fail "Java 버전을 판독하지 못했습니다: $JAVA_VERSION_OUTPUT"
(( JAVA_MAJOR >= 17 )) || fail "JDK 17 이상이 필요합니다. 현재 Java major version: $JAVA_MAJOR"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
    for candidate in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" "${PREFIX:-}/share/android-sdk"; do
        if [[ -n "$candidate" && -d "$candidate" ]]; then
            SDK_ROOT="$candidate"
            break
        fi
    done
fi
[[ -n "$SDK_ROOT" && -d "$SDK_ROOT" ]] || fail "Android SDK를 찾지 못했습니다. ANDROID_SDK_ROOT를 지정하세요."

GRADLE_BIN=""
if command -v gradle >/dev/null 2>&1; then
    GRADLE_VERSION_OUTPUT="$(gradle --version 2>&1 || true)"
    if [[ "$GRADLE_VERSION_OUTPUT" == *"Gradle $GRADLE_VERSION"* ]]; then
        GRADLE_BIN="$(command -v gradle)"
    fi
fi

if [[ -z "$GRADLE_BIN" ]]; then
    CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/nim-studio-distributions"
    DIST_DIR="$CACHE_ROOT/gradle-$GRADLE_VERSION"
    GRADLE_BIN="$DIST_DIR/bin/gradle"
    if [[ ! -x "$GRADLE_BIN" ]]; then
        command -v curl >/dev/null 2>&1 || fail "Gradle $GRADLE_VERSION를 받으려면 curl이 필요합니다."
        command -v unzip >/dev/null 2>&1 || fail "Gradle 배포 파일을 풀려면 unzip이 필요합니다."
        mkdir -p "$CACHE_ROOT"
        TEMP_DIR="$(mktemp -d "$CACHE_ROOT/download.XXXXXX")"
        trap 'rm -rf "$TEMP_DIR"' EXIT
        curl -fL --retry 2 "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$TEMP_DIR/gradle.zip"
        unzip -q "$TEMP_DIR/gradle.zip" -d "$TEMP_DIR"
        rm -rf "$DIST_DIR"
        mv "$TEMP_DIR/gradle-$GRADLE_VERSION" "$DIST_DIR"
        rm -rf "$TEMP_DIR"
        trap - EXIT
    fi
fi

"$GRADLE_BIN" -p "$PROJECT_ROOT" testDebugUnitTest assembleDebug assembleRelease

DEBUG_APK="$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
RELEASE_APK="$PROJECT_ROOT/app/build/outputs/apk/release/app-release.apk"
[[ -f "$DEBUG_APK" ]] || fail "Gradle가 성공했지만 debug APK를 찾지 못했습니다: $DEBUG_APK"
[[ -f "$RELEASE_APK" ]] || fail "Gradle가 성공했지만 release APK를 찾지 못했습니다: $RELEASE_APK"
printf '\nAPK 생성 완료:\n%s\n%s\n' "$DEBUG_APK" "$RELEASE_APK"

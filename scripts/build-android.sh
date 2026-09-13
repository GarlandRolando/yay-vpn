#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
command -v gradle >/dev/null || { echo 'Install Gradle 8.13 or use the included GitHub Actions workflow.'; exit 1; }
if [ ! -f "$PROJECT_DIR/android/app/libs/libbox.aar" ]; then bash "$PROJECT_DIR/scripts/build-core.sh"; fi
cd "$PROJECT_DIR/android"
gradle --no-daemon :app:assembleDebug :app:lintDebug
printf '%s\n' 'Test APK: android/app/build/outputs/apk/debug/app-debug.apk'
printf '%s\n' 'Use a signed release build for distribution. See docs/BUILD-ANDROID.md.'

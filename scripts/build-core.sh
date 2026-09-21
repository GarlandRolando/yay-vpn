#!/usr/bin/env bash
set -euo pipefail
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE_COMMIT=54ed58499d7063136ed52dabf87d179d252425d0
ENGINE_DIR="$PROJECT_DIR/.build/sing-box"
command -v go >/dev/null || { echo 'Install Go 1.24.10 first.'; exit 1; }
command -v git >/dev/null || { echo 'Install git first.'; exit 1; }
: "${ANDROID_HOME:?Set ANDROID_HOME to your Android SDK directory}"
: "${ANDROID_NDK_HOME:?Set ANDROID_NDK_HOME to Android NDK 28.0.13004108}"
mkdir -p "$PROJECT_DIR/.build" "$PROJECT_DIR/android/app/libs"
if [ ! -d "$ENGINE_DIR/.git" ]; then git clone --branch v1.12.12 --depth 1 https://github.com/SagerNet/sing-box.git "$ENGINE_DIR"; fi
cd "$ENGINE_DIR"
if [ "$(git rev-parse HEAD)" != "$ENGINE_COMMIT" ]; then echo 'Unexpected engine revision. Stop and inspect .build/sing-box.'; exit 1; fi
python3 "$PROJECT_DIR/scripts/apply-auto-engine.py" "$ENGINE_DIR"
go test -race ./common/yayauto
go test ./protocol/group
go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.8
go install github.com/sagernet/gomobile/cmd/gobind@v0.1.8
GOBIN_PATH="$(go env GOPATH)/bin"
export PATH="$GOBIN_PATH:$PATH"
# Upstream builder supplies correct Java package, native name and build flags.
go run ./cmd/internal/build_libbox -target android -platform android/arm64,android/amd64
cp libbox.aar "$PROJECT_DIR/android/app/libs/libbox.aar"
mkdir -p "$PROJECT_DIR/third-party"
cp LICENSE "$PROJECT_DIR/third-party/sing-box-LICENSE.txt"
printf '%s\n' 'Native engine built. Android app source can now be compiled.'

